package com.metafore.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compass 85d16fea — a real server's CapabilityStatement is about 2 MB (HAPI's: 2,087,096 bytes), twice the broker's packet
 * limit. The edge sends back only what the platform captures (fhirVersion, software.name, rest[].resource[].type).
 */
class FhirMetadataSummaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A statement shaped like HAPI's: 150 resource types, each with hundreds of search parameters and operations. */
    private static String bigStatement() throws Exception {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("resourceType", "CapabilityStatement").put("fhirVersion", "4.0.1").put("status", "active");
        s.putObject("software").put("name", "HAPI FHIR Server").put("version", "8.0");
        ArrayNode resources = s.putArray("rest").addObject().put("mode", "server").putArray("resource");
        for (int i = 0; i < 150; i++) {
            ObjectNode r = resources.addObject().put("type", "Type" + i);
            ArrayNode params = r.putArray("searchParam");
            for (int j = 0; j < 120; j++) {
                params.addObject().put("name", "param-" + j).put("type", "token")
                    .put("documentation", "Search by this field. ".repeat(6));
            }
        }
        return MAPPER.writeValueAsString(s);
    }

    private static Map<String, Object> run(String body) throws Exception {
        HttpResponse<String> resp = new HttpResponse<>() { // concrete: the JDK interface is not mockable
            public int statusCode() { return 200; }
            public String body() { return body; }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpRequest request() { return null; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("https://fhir.example.org/baseR4"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
        return new FhirTemplateExecutor(req -> resp).execute("fhir.server.metadata",
            Map.of("base_url", "https://fhir.example.org/baseR4"), Map.of());
    }

    @Test
    void theStatementIsOverTheBrokersLimitAndTheReplyIsNot() throws Exception {
        String statement = bigStatement();
        assertTrue(statement.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024,
            "the untrimmed statement is over the broker's 1 MB: " + statement.length());
        Map<String, Object> reply = run(statement);
        String published = MAPPER.writeValueAsString(reply);
        assertTrue(published.getBytes(StandardCharsets.UTF_8).length < 16 * 1024, "the reply is small: " + published.length());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePlatformsThreeCapturePathsStillReadTheSameValues() throws Exception {
        Map<String, Object> reply = run(bigStatement());
        assertEquals("success", reply.get("status"));
        JsonNode data = MAPPER.valueToTree(((List<Map<String, Object>>) reply.get("data")).get(0));
        assertEquals("4.0.1", data.get("fhirVersion").asText());
        assertEquals("HAPI FHIR Server", data.get("software").get("name").asText());
        JsonNode resources = data.get("rest").get(0).get("resource");
        assertEquals(150, resources.size());
        assertEquals("Type0", resources.get(0).get("type").asText());
        assertEquals("Type149", resources.get(149).get("type").asText());
    }

    @Test
    void aStatementWithoutThoseFieldsGivesAnEmptyShapeNotAnError() throws Exception {
        JsonNode out = FhirTemplateExecutor.summarize(MAPPER.readTree("{\"resourceType\":\"CapabilityStatement\"}"));
        assertFalse(out.has("fhirVersion"));
        assertFalse(out.has("software"));
        assertEquals(0, out.get("rest").size());
    }
}
