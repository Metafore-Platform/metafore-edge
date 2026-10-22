package com.metafore.keycloak.email;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.keycloak.email.EmailException;
import org.keycloak.models.UserModel;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GraphEmailSenderProviderTest {

    private static HttpServer server;
    private static int port;

    private static final String VALID_TOKEN_RESPONSE =
            "{\"access_token\":\"test-token-abc\",\"expires_in\":3600}";
    private static final String ACCEPTED_RESPONSE = "";

    private AtomicInteger tokenCallCount;
    private AtomicReference<String> lastSendMailBody;
    private int tokenStatus;
    private int sendMailStatus;
    // Per-call override: if non-empty, handler pops statuses from here instead of sendMailStatus
    private Queue<Integer> sendMailStatusQueue;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setupHandlers() {
        tokenCallCount = new AtomicInteger(0);
        lastSendMailBody = new AtomicReference<>("");
        tokenStatus = 200;
        sendMailStatus = 202;
        sendMailStatusQueue = new ConcurrentLinkedQueue<>();

        server.createContext("/tenant-ok/oauth2/v2.0/token", exchange -> {
            tokenCallCount.incrementAndGet();
            byte[] body = tokenStatus == 200
                    ? VALID_TOKEN_RESPONSE.getBytes(StandardCharsets.UTF_8)
                    : "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.createContext("/v1.0/users/", exchange -> {
            lastSendMailBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Integer queuedStatus = sendMailStatusQueue.poll();
            int status = queuedStatus != null ? queuedStatus : sendMailStatus;
            exchange.sendResponseHeaders(status, 0);
            exchange.getResponseBody().close();
        });
    }

    @AfterEach
    void removeHandlers() {
        server.removeContext("/tenant-ok/oauth2/v2.0/token");
        server.removeContext("/v1.0/users/");
    }

    private GraphEmailSenderProviderFactory factoryWithLocalServer() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        GraphEmailSenderProviderFactory f = new GraphEmailSenderProviderFactory(client) {
            @Override
            public String getAccessToken() throws EmailException {
                // override token URL to hit local server
                return fetchTokenLocal();
            }
        };
        return f;
    }

    // We can't easily override the private token URL, so we test via a testable subclass.
    // For the factory, we test isConfigured() and TokenCache directly.

    @Test
    void notConfigured_throwsEmailException() {
        GraphEmailSenderProviderFactory f = new GraphEmailSenderProviderFactory();
        // init() not called, fields are null
        assertFalse(f.isConfigured());
        GraphEmailSenderProvider provider = f.create(null);
        UserModel user = Mockito.mock(UserModel.class);
        Mockito.when(user.getEmail()).thenReturn("user@example.com");

        assertThrows(EmailException.class, () ->
                provider.send(Collections.emptyMap(), user, "Subject", "text", "html"));
    }

    @Test
    void tokenCache_isReusedWithinExpiry() {
        GraphEmailSenderProviderFactory.TokenCache cache =
                new GraphEmailSenderProviderFactory.TokenCache("tok", System.currentTimeMillis() + 60_000);
        assertTrue(cache.isValid());
        assertEquals("tok", cache.accessToken);
    }

    @Test
    void tokenCache_expiredIsInvalid() {
        GraphEmailSenderProviderFactory.TokenCache cache =
                new GraphEmailSenderProviderFactory.TokenCache("tok", System.currentTimeMillis() - 1);
        assertFalse(cache.isValid());
    }

    @Test
    void sendMailPayload_htmlPreferredOverText() throws Exception {
        // Use a factory stub whose getAccessToken() returns a fixed token
        // and whose httpClient posts to our local server
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        GraphEmailSenderProviderFactory f = new StubFactory(client, "fixed-token",
                "sender@example.com", "", "",
                "http://localhost:" + port);
        GraphEmailSenderProvider p = new GraphEmailSenderProvider(f);
        UserModel user = Mockito.mock(UserModel.class);
        Mockito.when(user.getEmail()).thenReturn("dest@example.com");

        p.send(Collections.emptyMap(), user, "Hello", "plain text", "<b>rich</b>");

        String body = lastSendMailBody.get();
        assertTrue(body.contains("\"contentType\":\"HTML\""), "expects HTML content type");
        assertTrue(body.contains("<b>rich</b>"), "expects html body");
        assertTrue(body.contains("dest@example.com"), "expects recipient");
        assertTrue(body.contains("sender@example.com"), "expects sender");
        assertFalse(body.contains("fixed-token"), "token must not appear in send payload");
    }

    @Test
    void sendMail_nonSuccessStatusThrowsEmailException() {
        sendMailStatus = 500;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        GraphEmailSenderProviderFactory f = new StubFactory(client, "tok",
                "s@example.com", "", "",
                "http://localhost:" + port);
        GraphEmailSenderProvider p = new GraphEmailSenderProvider(f);
        UserModel user = Mockito.mock(UserModel.class);
        Mockito.when(user.getEmail()).thenReturn("u@example.com");

        assertThrows(EmailException.class, () ->
                p.send(Collections.emptyMap(), user, "S", "t", "h"));
    }

    // Stub factory that bypasses real token fetch and uses a local test HTTP server base URL
    private static class StubFactory extends GraphEmailSenderProviderFactory {
        private final String fixedToken;
        private final String senderAddr;
        private final String displayNameVal;
        private final String replyToVal;
        private final String baseUrl;

        StubFactory(HttpClient client, String token, String sender,
                    String displayName, String replyTo, String baseUrl) {
            super(client);
            this.fixedToken = token;
            this.senderAddr = sender;
            this.displayNameVal = displayName;
            this.replyToVal = replyTo;
            this.baseUrl = baseUrl;
        }

        @Override
        String getAccessToken() { return fixedToken; }

        @Override
        boolean isConfigured() { return true; }

        @Override
        String getSender() { return senderAddr; }

        @Override
        String getDisplayName() { return displayNameVal; }

        @Override
        String getReplyTo() { return replyToVal; }

        @Override
        String getGraphBaseUrl() { return baseUrl; }
    }

    @Test
    void sendMail_401_invalidatesTokenAndRetries() throws Exception {
        // First call → 401, second call → 202
        sendMailStatusQueue.add(401);
        sendMailStatusQueue.add(202);

        AtomicInteger tokenFetchCount = new AtomicInteger(0);
        List<String> usedTokens = Collections.synchronizedList(new ArrayList<>());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // Override getAccessToken() to return different tokens per call, track invocations
        GraphEmailSenderProviderFactory f = new StubFactory(client, null,
                "sender@example.com", "", "", "http://localhost:" + port) {
            @Override
            String getAccessToken() {
                return "token-" + tokenFetchCount.incrementAndGet();
            }
        };

        // Capture tokens used in requests by reading the Authorization header from lastSendMailBody
        // (we actually verify via doSend call count using the status queue size draining)
        GraphEmailSenderProvider p = new GraphEmailSenderProvider(f);
        UserModel user = Mockito.mock(UserModel.class);
        Mockito.when(user.getEmail()).thenReturn("u@example.com");

        p.send(Collections.emptyMap(), user, "S", "t", "h");

        assertTrue(sendMailStatusQueue.isEmpty(), "both queued statuses consumed — two HTTP calls made");
        assertEquals(2, tokenFetchCount.get(), "getAccessToken called twice (initial + after 401)");
    }

    @Test
    void sendMail_401_persistentFails_throwsEmailException() {
        // Both calls → 401: should throw after one retry
        sendMailStatusQueue.add(401);
        sendMailStatusQueue.add(401);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        GraphEmailSenderProviderFactory f = new StubFactory(client, "fixed-tok",
                "sender@example.com", "", "", "http://localhost:" + port);
        GraphEmailSenderProvider p = new GraphEmailSenderProvider(f);
        UserModel user = Mockito.mock(UserModel.class);
        Mockito.when(user.getEmail()).thenReturn("u@example.com");

        assertThrows(EmailException.class, () ->
                p.send(Collections.emptyMap(), user, "S", "t", "h"));
        assertTrue(sendMailStatusQueue.isEmpty(), "both queued statuses consumed — two HTTP calls made");
    }

    private String fetchTokenLocal() throws EmailException {
        // not used in current tests; placeholder
        throw new EmailException("local token not wired");
    }
}
