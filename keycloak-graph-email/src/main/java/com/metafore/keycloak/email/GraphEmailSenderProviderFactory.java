package com.metafore.keycloak.email;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.email.EmailSenderProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.keycloak.email.EmailException;

public class GraphEmailSenderProviderFactory implements EmailSenderProviderFactory {

    private static final Logger LOG = Logger.getLogger(GraphEmailSenderProviderFactory.class);

    static final String PROVIDER_ID = "graph";

    private static final String TOKEN_URL_TEMPLATE =
            "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String azureTenantId;
    private String clientId;
    private String clientSecret;
    private String sender;
    private String displayName;
    private String replyTo;

    private final HttpClient httpClient;
    private volatile TokenCache tokenCache;

    public GraphEmailSenderProviderFactory() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // package-private: allows injecting a mock HTTP client in tests
    GraphEmailSenderProviderFactory(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public GraphEmailSenderProvider create(KeycloakSession session) {
        return new GraphEmailSenderProvider(this);
    }

    @Override
    public void init(Config.Scope config) {
        azureTenantId = config.get("azure-tenant-id");
        clientId      = config.get("client-id");
        clientSecret  = config.get("client-secret");
        sender        = config.get("sender");
        displayName   = config.get("display-name", "");
        replyTo       = config.get("reply-to", "");
        LOG.infof("Graph email provider initialised (configured: %b, sender: %s)",
                isConfigured(), sender != null ? sender : "<not set>");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    String getGraphBaseUrl() {
        return "https://graph.microsoft.com";
    }

    void invalidateToken() {
        tokenCache = null;
    }

    boolean isConfigured() {
        return azureTenantId != null && !azureTenantId.isEmpty()
                && clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty()
                && sender != null && !sender.isEmpty();
    }

    String getSender()      { return sender; }
    String getDisplayName() { return displayName; }
    String getReplyTo()     { return replyTo; }

    String getAccessToken() throws EmailException {
        TokenCache cache = tokenCache;
        if (cache != null && cache.isValid()) {
            return cache.accessToken;
        }
        return refreshToken();
    }

    HttpClient getHttpClient() {
        return httpClient;
    }

    private synchronized String refreshToken() throws EmailException {
        // double-checked: another thread may have refreshed while we waited
        if (tokenCache != null && tokenCache.isValid()) {
            return tokenCache.accessToken;
        }
        String tokenUrl = String.format(TOKEN_URL_TEMPLATE, azureTenantId);
        String body = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&scope=https%3A%2F%2Fgraph.microsoft.com%2F.default";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new EmailException("Graph email: token request failed (" + resp.statusCode() + ")");
            }
            JsonNode node = MAPPER.readTree(resp.body());
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null || accessToken.isEmpty()) {
                throw new EmailException("Graph email: token response missing access_token");
            }
            long expiresIn = node.path("expires_in").asLong(3600);
            tokenCache = new TokenCache(accessToken, System.currentTimeMillis() + (expiresIn - 60) * 1000);
            return accessToken;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EmailException("Graph email: token request error: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    static final class TokenCache {
        final String accessToken;
        private final long expiresAt;

        TokenCache(String accessToken, long expiresAt) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }

        boolean isValid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }
}
