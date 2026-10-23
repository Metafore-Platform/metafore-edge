package com.metafore.keycloak.email;

import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.UserModel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class GraphEmailSenderProvider implements EmailSenderProvider {

    private static final String SEND_MAIL_PATH = "/v1.0/users/%s/sendMail";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphEmailSenderProviderFactory factory;

    GraphEmailSenderProvider(GraphEmailSenderProviderFactory factory) {
        this.factory = factory;
    }

    @Override
    public void send(Map<String, String> config, UserModel user, String subject,
                     String textBody, String htmlBody) throws EmailException {
        send(config, user.getEmail(), subject, textBody, htmlBody);
    }

    @Override
    public void send(Map<String, String> config, String address, String subject,
                     String textBody, String htmlBody) throws EmailException {
        if (!factory.isConfigured()) {
            throw new EmailException("Graph email: not configured — set platform email settings");
        }
        String payload = buildPayload(address, subject, textBody, htmlBody);
        String url = factory.getGraphBaseUrl() + String.format(SEND_MAIL_PATH, encode(factory.getSender()));

        int status = doSend(factory.getAccessToken(), url, payload);
        if (status == 401) {
            // Token was revoked or rotated before its cached expiry — drop cache and retry once
            factory.invalidateToken();
            status = doSend(factory.getAccessToken(), url, payload);
        }
        if (status < 200 || status >= 300) {
            // log status only — never log response body (may contain token fragments) or payload
            throw new EmailException("Graph email: sendMail returned " + status);
        }
    }

    private int doSend(String token, String url, String payload) throws EmailException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            return factory.getHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString())
                    .statusCode();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EmailException("Graph email: send error: " + e.getMessage());
        }
    }

    @Override
    public void close() {}

    private String buildPayload(String toAddress, String subject,
                                String textBody, String htmlBody) throws EmailException {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode message = root.putObject("message");
            message.put("subject", subject);

            ObjectNode body = message.putObject("body");
            if (htmlBody != null && !htmlBody.isEmpty()) {
                body.put("contentType", "HTML");
                body.put("content", htmlBody);
            } else {
                body.put("contentType", "Text");
                body.put("content", textBody != null ? textBody : "");
            }

            ArrayNode toRecipients = message.putArray("toRecipients");
            toRecipients.addObject().putObject("emailAddress").put("address", toAddress);

            ObjectNode fromEmail = message.putObject("from").putObject("emailAddress");
            fromEmail.put("address", factory.getSender());
            if (!factory.getDisplayName().isEmpty()) {
                fromEmail.put("name", factory.getDisplayName());
            }

            String replyTo = factory.getReplyTo();
            if (replyTo != null && !replyTo.isEmpty()) {
                ArrayNode replyToArr = message.putArray("replyTo");
                replyToArr.addObject().putObject("emailAddress").put("address", replyTo);
            }

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new EmailException("Graph email: failed to build payload: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
