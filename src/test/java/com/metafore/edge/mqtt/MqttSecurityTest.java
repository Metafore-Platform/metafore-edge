package com.metafore.edge.mqtt;

import org.apache.camel.component.paho.PahoComponent;
import org.apache.camel.component.paho.PahoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** How the edge signs in to the broker: its certificate (no username), or a username and password — never anonymously. */
class MqttSecurityTest {

    private static final String TLS_URL = "ssl://emqx.metafore-dev.svc.cluster.local:8883";
    private static final String TCP_URL = "tcp://emqx:1883";

    @TempDir Path dir;
    TestPki pki;
    Path ca;
    Path cert;
    Path key;

    @BeforeEach
    void pair() throws Exception {
        pki = new TestPki("test-mqtt-ca");
        ca = pki.writeCa(dir, "ca.crt");
        Path[] p = pki.writePair(dir, "edge", "acme/acme-managed", TestPki.ec(), false);
        cert = p[0];
        key = p[1];
    }

    private Map<String, String> certEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("METAFORE_MQTT_TLS_ENABLED", "true");
        env.put("METAFORE_MQTT_TLS_CA_CERT", ca.toString());
        env.put("METAFORE_MQTT_TLS_CERT", cert.toString());
        env.put("METAFORE_MQTT_TLS_KEY", key.toString());
        return env;
    }

    private static MqttSecurity of(Map<String, String> env, String url) {
        return MqttSecurity.fromEnv(env, url, "acme", "acme-managed");
    }

    private static String refusal(Map<String, String> env, String url) {
        return assertThrows(IllegalStateException.class, () -> of(env, url)).getMessage();
    }

    // ── certificate sign-in ─────────────────────────────────────────────────

    @Test
    void withACertificateNoUsernameAndNoPasswordIsSent() {
        MqttSecurity security = of(certEnv(), TLS_URL);
        PahoConfiguration config = security.component().getConfiguration();
        assertTrue(security.certificateMode());
        assertEquals("acme/acme-managed", security.certificateName());
        assertNull(config.getUserName(), "the broker maps the certificate's CN to the username");
        assertNull(config.getPassword());
        assertEquals(TLS_URL, config.getBrokerUrl());
        assertInstanceOf(ReloadingSslSocketFactory.class, config.getSocketFactory());
        assertInstanceOf(SSLSocketFactory.class, config.getSocketFactory(), "paho refuses a non-SSL factory for ssl://");
        assertTrue(config.isAutomaticReconnect());
    }

    @Test
    void theComponentEveryRouteInheritsIsOneSharedPahoComponent() {
        PahoComponent component = of(certEnv(), TLS_URL).component();
        assertSame(component.getConfiguration().getSocketFactory(), component.getConfiguration().getSocketFactory());
        assertEquals(TLS_URL, component.getConfiguration().getBrokerUrl());
    }

    @Test
    void aCertificateForAnotherNameIsRefused() throws Exception {
        Path[] other = pki.writePair(dir, "other", "acme/another-controller", TestPki.ec(), false);
        Map<String, String> env = certEnv();
        env.put("METAFORE_MQTT_TLS_CERT", other[0].toString());
        env.put("METAFORE_MQTT_TLS_KEY", other[1].toString());
        String why = refusal(env, TLS_URL);
        assertTrue(why.contains("acme/another-controller") && why.contains("acme/acme-managed"), why);
    }

    @Test
    void theOldInsecureSwitchIsRefused() {
        Map<String, String> env = certEnv();
        env.put("METAFORE_MQTT_TLS_INSECURE", "true");
        assertTrue(refusal(env, TLS_URL).contains("METAFORE_MQTT_TLS_INSECURE is refused"));
    }

    @Test
    void certAndKeyGoTogetherAndNeedTlsOnAnSslUrl() {
        Map<String, String> noKey = certEnv();
        noKey.remove("METAFORE_MQTT_TLS_KEY");
        assertTrue(refusal(noKey, TLS_URL).contains("go together"));
        Map<String, String> noCert = certEnv();
        noCert.remove("METAFORE_MQTT_TLS_CERT");
        assertTrue(refusal(noCert, TLS_URL).contains("go together"));
        Map<String, String> off = certEnv();
        off.put("METAFORE_MQTT_TLS_ENABLED", "false");
        assertTrue(refusal(off, TLS_URL).contains("METAFORE_MQTT_TLS_ENABLED is not true"));
        assertTrue(refusal(certEnv(), TCP_URL).contains("ssl:// BROKER_URL"));
    }

    @Test
    void anUnreadableOrNonCertificateFileStopsTheEdge() throws Exception {
        Map<String, String> missing = certEnv();
        missing.put("METAFORE_MQTT_TLS_KEY", dir.resolve("nope.key").toString());
        assertTrue(refusal(missing, TLS_URL).contains("is not a readable file"));
        TestPki.write(dir.resolve("junk.crt"), "not a certificate");
        Map<String, String> junk = certEnv();
        junk.put("METAFORE_MQTT_TLS_CERT", dir.resolve("junk.crt").toString());
        assertTrue(refusal(junk, TLS_URL).contains("not a readable certificate"));
    }

    // ── never anonymous ─────────────────────────────────────────────────────

    @Test
    void withNeitherACertificateNorCredentialsTheEdgeDoesNotStart() {
        assertTrue(refusal(new HashMap<>(), TCP_URL).contains("never connects anonymously"));
        Map<String, String> onlyUser = new HashMap<>(Map.of("METAFORE_MQTT_USERNAME", "acme/acme-managed"));
        assertTrue(refusal(onlyUser, TCP_URL).contains("never connects anonymously"));
        Map<String, String> onlyPassword = new HashMap<>(Map.of("METAFORE_MQTT_PASSWORD", "pw"));
        assertTrue(refusal(onlyPassword, TCP_URL).contains("never connects anonymously"));
    }

    @Test
    void aPasswordSignInNeedsTheUsernameOfThisEdgeAndNeverShowsThePassword() {
        Map<String, String> env = new HashMap<>(Map.of("METAFORE_MQTT_USERNAME", "acme/someone-else",
            "METAFORE_MQTT_PASSWORD", "s3cr3t-pw"));
        String why = refusal(env, TCP_URL);
        assertTrue(why.contains("acme/acme-managed"), why);
        assertFalse(why.contains("s3cr3t-pw"), "no secret in a message");
    }

    @Test
    void aPasswordSignInSendsTheUsernameAndPassword() {
        Map<String, String> env = new HashMap<>(Map.of("METAFORE_MQTT_USERNAME", "acme/acme-managed",
            "METAFORE_MQTT_PASSWORD", "pw"));
        MqttSecurity security = of(env, TCP_URL);
        PahoConfiguration config = security.component().getConfiguration();
        assertFalse(security.certificateMode());
        assertEquals("acme/acme-managed", config.getUserName());
        assertEquals("pw", config.getPassword());
        assertNull(config.getSocketFactory());
    }

    @Test
    void aPasswordSignInOverTlsChecksTheBrokerAgainstTheConfiguredCa() {
        Map<String, String> env = new HashMap<>(Map.of("METAFORE_MQTT_USERNAME", "acme/acme-managed",
            "METAFORE_MQTT_PASSWORD", "pw", "METAFORE_MQTT_TLS_ENABLED", "true", "METAFORE_MQTT_TLS_CA_CERT", ca.toString()));
        PahoConfiguration config = of(env, TLS_URL).component().getConfiguration();
        assertEquals("acme/acme-managed", config.getUserName());
        assertInstanceOf(ReloadingSslSocketFactory.class, config.getSocketFactory());
        env.remove("METAFORE_MQTT_TLS_CA_CERT");
        assertNull(of(env, TLS_URL).component().getConfiguration().getSocketFactory(), "the JVM trust store");
    }

    @Test
    void tlsEnabledOverAPlainUrlIsRefused() {
        Map<String, String> env = new HashMap<>(Map.of("METAFORE_MQTT_USERNAME", "acme/acme-managed",
            "METAFORE_MQTT_PASSWORD", "pw", "METAFORE_MQTT_TLS_ENABLED", "true"));
        assertTrue(refusal(env, TCP_URL).contains("not ssl://"));
    }

    @Test
    void everyRoutesPahoEndpointInheritsTheSecurityFromTheComponent() throws Exception {
        org.apache.camel.CamelContext context = new org.apache.camel.impl.DefaultCamelContext();
        context.addComponent("paho", of(certEnv(), TLS_URL).component());
        // the form every route builds: topic plus ?brokerUrl=<the same url>
        org.apache.camel.component.paho.PahoEndpoint endpoint = (org.apache.camel.component.paho.PahoEndpoint)
            context.getEndpoint("paho:control/acme/acme-managed/routes?brokerUrl=" + TLS_URL);
        assertInstanceOf(ReloadingSslSocketFactory.class, endpoint.getConfiguration().getSocketFactory());
        assertNull(endpoint.getConfiguration().getUserName());
        assertNull(endpoint.getConfiguration().getPassword());
        assertEquals(TLS_URL, endpoint.getConfiguration().getBrokerUrl());
    }
}
