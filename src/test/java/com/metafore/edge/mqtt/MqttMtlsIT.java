package com.metafore.edge.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The edge signs in to a real EMQX with the platform chart's own certificate listener and topic rules
 * ({@code scripts/mtls-broker.sh start}; CI job {@code mtls} of development.yml). Runs under {@code mvn verify} and only
 * when {@code MTLS_DIR} is set (the script exports it); without a broker it is skipped. The CI job fails if it was skipped.
 *
 * <p>What it proves, against the real broker: a client certificate is the sign-in and the broker names the client by the
 * certificate's common name on {@code ssl:default}; the topic rules confine that name to its own topics; a certificate from
 * another CA, no certificate, and an untrusted broker are all refused; and the same factory presents whatever is on disk at
 * the next connect.
 */
class MqttMtlsIT {

    private static String dir;
    private static String broker;
    private static String api;
    private static String apiAuth;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final List<MqttClient> open = new ArrayList<>();

    @BeforeAll
    static void broker() {
        dir = System.getenv("MTLS_DIR");
        assumeTrue(dir != null && !dir.isBlank(), "MTLS_DIR is not set: no broker to test against (scripts/mtls-broker.sh start)");
        broker = System.getenv("MTLS_BROKER");
        api = System.getenv("MTLS_API");
        apiAuth = "Basic " + Base64.getEncoder().encodeToString(
            (System.getenv("MTLS_API_KEY") + ":" + System.getenv("MTLS_API_SECRET")).getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void close() {
        for (MqttClient c : open) {
            try {
                if (c.isConnected()) {
                    c.disconnect(1000);
                }
                c.close(true);
            } catch (MqttException ignored) {
                // already gone
            }
        }
    }

    // ── helpers ──

    private Path file(String name) { return Path.of(dir, name); }

    /** The edge's settings exactly as a deployment would give them, through MqttSecurity. */
    private ReloadingSslSocketFactory factory(String cert, String key, String ca, String tenant, String controller) {
        Map<String, String> env = Map.of("METAFORE_MQTT_TLS_ENABLED", "true",
            "METAFORE_MQTT_TLS_CA_CERT", ca, "METAFORE_MQTT_TLS_CERT", cert, "METAFORE_MQTT_TLS_KEY", key);
        return (ReloadingSslSocketFactory) MqttSecurity.fromEnv(env, broker, tenant, controller)
            .component().getConfiguration().getSocketFactory();
    }

    private ReloadingSslSocketFactory edgeFactory() {
        return factory(file("edge.crt").toString(), file("edge.key").toString(), file("ca.crt").toString(), "acme", "acme-managed");
    }

    private MqttClient connect(String clientId, javax.net.SocketFactory factory) throws MqttException {
        MqttClient client = new MqttClient(broker, clientId, new MemoryPersistence());
        open.add(client);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setSocketFactory(factory);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        // no setUserName / setPassword: the certificate is the sign-in
        client.connect(options);
        return client;
    }

    /** A refusal only means something while the broker is up and takes the right certificate: prove that first. */
    private void brokerAcceptsTheRealEdge() throws Exception {
        MqttClient edge = connect(id("control"), edgeFactory());
        assertTrue(edge.isConnected());
        edge.disconnect();
    }

    private static String id(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }

    private JsonNode clientRow(String clientId) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create(api + "/api/v5/clients?clientid=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)))
            .header("Authorization", apiAuth).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode data = JSON.readTree(r.body()).get("data");
        assertEquals(1, data.size(), "the broker should list exactly this client: " + r.body());
        return data.get(0);
    }

    private static CountDownLatch collect(MqttClient client, String filter, List<String> topics) throws MqttException {
        CountDownLatch first = new CountDownLatch(1);
        IMqttMessageListener listener = (topic, message) -> {
            topics.add(topic);
            first.countDown();
        };
        client.subscribe(filter, 0, listener);
        return first;
    }

    // ── the sign-in ──

    @Test
    void theBrokerNamesTheEdgeByItsCertificatesCommonNameOnTheCertificateListener() throws Exception {
        String clientId = id("edge");
        connect(clientId, edgeFactory());
        JsonNode row = clientRow(clientId);
        assertEquals("acme/acme-managed", row.get("username").asText(), "peer_cert_as_username = cn");
        assertEquals("ssl:default", row.get("listener").asText());
        assertTrue(row.get("connected").asBoolean());
    }

    @Test
    void aSec1KeyAsCertManagerWritesItConnectsToo() throws Exception {
        String clientId = id("edge-sec1");
        connect(clientId, factory(file("edge-sec1.crt").toString(), file("edge-sec1.key").toString(),
            file("ca.crt").toString(), "acme", "acme-managed"));
        assertEquals("acme/acme-managed", clientRow(clientId).get("username").asText());
    }

    @Test
    void aCertificateFromAnotherCaIsRefusedEvenWithTheRightName() throws Exception {
        brokerAcceptsTheRealEdge();
        ReloadingSslSocketFactory stranger = factory(file("stranger.crt").toString(), file("stranger.key").toString(),
            file("ca.crt").toString(), "acme", "acme-managed");
        assertThrows(MqttException.class, () -> connect(id("stranger"), stranger));
    }

    @Test
    void noClientCertificateIsRefused() throws Exception {
        brokerAcceptsTheRealEdge();
        ReloadingSslSocketFactory trustOnly = new ReloadingSslSocketFactory(file("ca.crt"), null, null);
        assertThrows(MqttException.class, () -> connect(id("anonymous"), trustOnly));
    }

    @Test
    void anEdgeThatDoesNotTrustTheBrokersCaRefusesIt() throws Exception {
        brokerAcceptsTheRealEdge();
        ReloadingSslSocketFactory wrongCa = factory(file("edge.crt").toString(), file("edge.key").toString(),
            file("stranger-ca.crt").toString(), "acme", "acme-managed");
        assertThrows(MqttException.class, () -> connect(id("edge"), wrongCa));
    }

    // ── the topic rules, as the platform chart ships them, applied to the certificate's name ──

    @Test
    void theEdgeReachesOnlyItsOwnTopics() throws Exception {
        // the platform's own certificate (CN "platform", not an <tenant>/<controller> name, so no MqttSecurity here)
        MqttClient platform = connect(id("platform"),
            new ReloadingSslSocketFactory(file("ca.crt"), file("platform.crt"), file("platform.key")));
        List<String> platformSaw = new CopyOnWriteArrayList<>();
        collect(platform, "telemetry/#", platformSaw);
        collect(platform, "response/#", platformSaw);

        MqttClient edge = connect(id("edge"), edgeFactory());
        List<String> edgeSaw = new CopyOnWriteArrayList<>();
        CountDownLatch commanded = collect(edge, "control/acme/acme-managed/#", edgeSaw);

        // what the edge may publish reaches the platform ...
        edge.publish("telemetry/acme/acme-managed/heartbeat", "{}".getBytes(StandardCharsets.UTF_8), 0, false);
        edge.publish("response/acme/acme-managed/a-1", "{}".getBytes(StandardCharsets.UTF_8), 0, false);
        // ... what it may not, the broker drops (deny_action = ignore)
        edge.publish("telemetry/acme/other-managed/heartbeat", "{}".getBytes(StandardCharsets.UTF_8), 0, false);
        edge.publish("response/acme/other-managed/a-1", "{}".getBytes(StandardCharsets.UTF_8), 0, false);
        edge.publish("control/acme/acme-managed/routes", "{}".getBytes(StandardCharsets.UTF_8), 0, false);
        // the platform commands this edge, and another one
        platform.publish("control/acme/other-managed/routes", "{}".getBytes(StandardCharsets.UTF_8), 0, false);
        platform.publish("control/acme/acme-managed/routes", "{}".getBytes(StandardCharsets.UTF_8), 0, false);

        assertTrue(commanded.await(10, TimeUnit.SECONDS), "the edge should receive its own command");
        Thread.sleep(2000);   // time for anything the broker wrongly let through
        assertEquals(List.of("control/acme/acme-managed/routes"), edgeSaw, "only this edge's own command");
        assertTrue(platformSaw.containsAll(List.of("telemetry/acme/acme-managed/heartbeat", "response/acme/acme-managed/a-1")),
            "the platform should receive the edge's own telemetry and reply: " + platformSaw);
        assertEquals(2, platformSaw.size(), "nothing the edge was not allowed to publish may arrive: " + platformSaw);
    }

    @Test
    void theEdgeCannotSubscribeOutsideItsOwnCommandTopic() throws Exception {
        MqttClient edge = connect(id("edge"), edgeFactory());
        for (String filter : List.of("control/#", "control/acme/other-managed/#", "telemetry/#", "response/#", "#")) {
            MqttException refused = assertThrows(MqttException.class, () -> edge.subscribe(filter, 0), filter);
            assertEquals(MqttException.REASON_CODE_SUBSCRIBE_FAILED, refused.getReasonCode(), filter);
        }
        edge.subscribe("control/acme/acme-managed/#", 0);   // its own is granted
    }

    // ── a renewal ──

    @Test
    void theNextConnectPresentsWhateverIsOnDiskWithoutANewFactory(@TempDir Path tmp) throws Exception {
        Path cert = tmp.resolve("tls.crt");
        Path key = tmp.resolve("tls.key");
        Files.copy(file("edge.crt"), cert);
        Files.copy(file("edge.key"), key);
        ReloadingSslSocketFactory factory = factory(cert.toString(), key.toString(), file("ca.crt").toString(), "acme", "acme-managed");

        String first = id("edge-a");
        MqttClient a = connect(first, factory);
        assertEquals("acme/acme-managed", clientRow(first).get("username").asText());
        a.disconnect();

        // the platform rewrites the mounted Secret (here: with another certificate the CA signed, so the name the
        // broker reads is visibly different) and the same factory is used for the next connect
        Files.copy(file("other.crt"), cert, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(file("other.key"), key, StandardCopyOption.REPLACE_EXISTING);
        String second = id("edge-b");
        connect(second, factory);
        assertEquals("acme/other-managed", clientRow(second).get("username").asText(), "the new file was read at this connect");
    }
}
