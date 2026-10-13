package com.metafore.edge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * task 828b1be6 — FhirTemplateExecutor unit tests.
 *
 * Verifies:
 * - Unknown template is refused (UnknownTemplateException)
 * - Known template with valid params dispatches correctly
 * - Injected &_include=* or /../ in a value is URL-encoded (not injected)
 * - Unknown search parameter key is refused (UnknownParameterException)
 * - Missing connection.base_url is refused
 */
class FhirTemplateExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> CONNECTION =
        Map.of("base_url", "https://hapi.fhir.org/baseR4");

    /** Concrete HttpResponse for tests — avoids mocking the sealed JDK interface. */
    private static HttpResponse<String> fakeResponse(int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public String body() { return body; }
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpRequest request() { return null; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("https://hapi.fhir.org/baseR4"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    // ── template whitelist ────────────────────────────────────────────

    @Test
    void unknownTemplateIsRefused() {
        FhirTemplateExecutor executor = new FhirTemplateExecutor();
        assertThrows(
            FhirTemplateExecutor.UnknownTemplateException.class,
            () -> executor.execute("fhir.medication.read", CONNECTION, Map.of())
        );
    }

    @Test
    void allowedTemplatesAreKnown() {
        assertTrue(FhirTemplateExecutor.ALLOWED_TEMPLATES.contains("fhir.patient.read"));
        assertTrue(FhirTemplateExecutor.ALLOWED_TEMPLATES.contains("fhir.patient.search"));
        assertTrue(FhirTemplateExecutor.ALLOWED_TEMPLATES.contains("fhir.server.metadata"));
    }

    // ── connection.base_url guard ─────────────────────────────────────

    @Test
    void missingBaseUrlIsRefused() {
        FhirTemplateExecutor executor = new FhirTemplateExecutor();
        assertThrows(
            IllegalArgumentException.class,
            () -> executor.execute("fhir.patient.read", Map.of(), Map.of("patient_id", "1"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> executor.execute("fhir.patient.read", null, Map.of("patient_id", "1"))
        );
    }

    // ── base_url validation (validateAndNormalizeBaseUrl) ─────────────

    @Test
    void httpPlaintextRemoteHostIsRefused() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FhirTemplateExecutor.validateAndNormalizeBaseUrl("http://evil.example.com/fhir"));
        assertTrue(ex.getMessage().contains("https"),
            "error must mention https requirement: " + ex.getMessage());
    }

    @Test
    void httpLocalhostIsAllowed() {
        // http allowed only for localhost (test / local dev)
        String result = FhirTemplateExecutor.validateAndNormalizeBaseUrl("http://localhost:8080/fhir");
        assertEquals("http://localhost:8080/fhir", result);
    }

    @Test
    void userinfoInUrlIsRefused() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FhirTemplateExecutor.validateAndNormalizeBaseUrl("https://user:pass@host.example.com/fhir"));
        assertTrue(ex.getMessage().toLowerCase().contains("userinfo"),
            "error must mention userinfo: " + ex.getMessage());
    }

    @Test
    void queryStringInUrlIsRefused() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FhirTemplateExecutor.validateAndNormalizeBaseUrl("https://host.example.com/fhir?foo=bar"));
        assertTrue(ex.getMessage().contains("query"),
            "error must mention query string: " + ex.getMessage());
    }

    @Test
    void fragmentInUrlIsRefused() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> FhirTemplateExecutor.validateAndNormalizeBaseUrl("https://host.example.com/fhir#section"));
        assertTrue(ex.getMessage().contains("fragment"),
            "error must mention fragment: " + ex.getMessage());
    }

    @Test
    void trailingSlashIsNormalized() {
        String result = FhirTemplateExecutor.validateAndNormalizeBaseUrl("https://hapi.fhir.org/baseR4/");
        assertFalse(result.endsWith("/"), "trailing slash must be stripped: " + result);
        assertEquals("https://hapi.fhir.org/baseR4", result);
    }

    // ── search parameter whitelist ────────────────────────────────────

    @Test
    void unknownSearchParamIsRefused() {
        FhirTemplateExecutor executor = new FhirTemplateExecutor();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("family", "Smith");
        params.put("_include", "Patient:organization");
        assertThrows(
            FhirTemplateExecutor.UnknownParameterException.class,
            () -> executor.execute("fhir.patient.search", CONNECTION, params)
        );
    }

    @Test
    void injectedAmpersandInValueIsUrlEncoded() throws Exception {
        // A value like "Smith&_include=*" must arrive at the server as
        // "Smith%26_include%3D*" — the & cannot split the query string.
        // We verify this by capturing the URL that the HttpClient would send.
        HttpResponse<String> fakeResp =
            fakeResponse(200, "{\"resourceType\":\"Bundle\",\"entry\":[]}");
        FhirHttpSend mockSender = mock(FhirHttpSend.class);
        when(mockSender.send(any(HttpRequest.class))).thenReturn(fakeResp);

        FhirTemplateExecutor executor = new FhirTemplateExecutor(mockSender);
        Map<String, Object> params = Map.of("family", "Smith&_include=*");
        executor.execute("fhir.patient.search", CONNECTION, params);

        // Capture the request URI and verify the injection is encoded
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockSender).send(captor.capture());
        String uri = captor.getValue().uri().toString();
        assertFalse(uri.contains("&_include"),
            "Injected & must be URL-encoded, not treated as a param separator. URI: " + uri);
        assertTrue(uri.contains("%26") || uri.contains("Smith"),
            "family value should appear (encoded) in URI: " + uri);
    }

    @Test
    void injectedPathTraversalInPatientIdIsEncoded() throws Exception {
        // A value like "/../admin" must be encoded, not cause a path traversal.
        HttpResponse<String> fakeResp =
            fakeResponse(200, "{\"resourceType\":\"Patient\",\"id\":\"example\"}");
        FhirHttpSend mockSender = mock(FhirHttpSend.class);
        when(mockSender.send(any(HttpRequest.class))).thenReturn(fakeResp);

        FhirTemplateExecutor executor = new FhirTemplateExecutor(mockSender);
        Map<String, Object> params = Map.of("patient_id", "/../admin");
        executor.execute("fhir.patient.read", CONNECTION, params);

        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockSender).send(captor.capture());
        String uri = captor.getValue().uri().toString();
        assertFalse(uri.contains("/../"),
            "Path traversal must be URL-encoded. URI: " + uri);
    }

    // ── success path ──────────────────────────────────────────────────

    @Test
    void patientReadSuccessWrapsResource() throws Exception {
        HttpResponse<String> fakeResp = fakeResponse(200,
            "{\"resourceType\":\"Patient\",\"id\":\"example\",\"name\":[]}");
        FhirHttpSend mockSender = mock(FhirHttpSend.class);
        when(mockSender.send(any(HttpRequest.class))).thenReturn(fakeResp);

        FhirTemplateExecutor executor = new FhirTemplateExecutor(mockSender);
        Map<String, Object> result = executor.execute(
            "fhir.patient.read", CONNECTION, Map.of("patient_id", "example"));

        assertEquals("success", result.get("status"));
        assertEquals("run_template", result.get("action"));
        assertEquals("fhir.patient.read", result.get("template_id"));
        assertEquals(1, result.get("row_count"));
    }

    @Test
    void patientSearchSuccessWrapsBundle() throws Exception {
        HttpResponse<String> fakeResp = fakeResponse(200,
            "{\"resourceType\":\"Bundle\","
            + "\"entry\":[{\"resource\":{\"resourceType\":\"Patient\",\"id\":\"1\"}}]}");
        FhirHttpSend mockSender = mock(FhirHttpSend.class);
        when(mockSender.send(any(HttpRequest.class))).thenReturn(fakeResp);

        FhirTemplateExecutor executor = new FhirTemplateExecutor(mockSender);
        Map<String, Object> result = executor.execute(
            "fhir.patient.search", CONNECTION, Map.of("family", "Smith"));

        assertEquals("success", result.get("status"));
        assertEquals("run_template", result.get("action"));
        assertEquals("fhir.patient.search", result.get("template_id"));
        assertEquals(1, result.get("row_count"));
    }

    @Test
    void serverMetadataSuccessWrapsCapabilityStatement() throws Exception {
        HttpResponse<String> fakeResp = fakeResponse(200,
            "{\"resourceType\":\"CapabilityStatement\",\"fhirVersion\":\"4.0.1\","
            + "\"status\":\"active\",\"kind\":\"instance\"}");
        FhirHttpSend mockSender = mock(FhirHttpSend.class);
        when(mockSender.send(any(HttpRequest.class))).thenReturn(fakeResp);

        FhirTemplateExecutor executor = new FhirTemplateExecutor(mockSender);
        Map<String, Object> result = executor.execute(
            "fhir.server.metadata", CONNECTION, Map.of());

        assertEquals("success", result.get("status"));
        assertEquals("run_template", result.get("action"));
        assertEquals("fhir.server.metadata", result.get("template_id"));
        assertEquals(1, result.get("row_count"));

        // URI must be {base_url}/metadata
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockSender).send(captor.capture());
        assertTrue(captor.getValue().uri().toString().endsWith("/metadata"),
            "URI must end with /metadata: " + captor.getValue().uri());
    }
}
