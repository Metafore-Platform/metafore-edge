package com.metafore.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * task 828b1be6 — FHIR template registry.
 *
 * <p>Executes registered FHIR read/search templates at the edge.
 * Three templates are supported:
 * <ul>
 *   <li>{@code fhir.patient.read} — GET {base_url}/Patient/{patient_id}</li>
 *   <li>{@code fhir.patient.search} — GET {base_url}/Patient?{whitelisted params}</li>
 *   <li>{@code fhir.server.metadata} — GET {base_url}/metadata (CapabilityStatement)</li>
 * </ul>
 *
 * <p>Security invariants:
 * <ul>
 *   <li>Server URL comes from {@code connection.base_url} only — never from
 *       an environment variable or default. Missing → {@link IllegalArgumentException}.</li>
 *   <li>Search parameter keys are validated against {@link #SEARCH_PARAM_WHITELIST}.
 *       Unknown keys → {@link UnknownParameterException}. Values are
 *       {@code URLEncoder.encode}'d, so a value like {@code Smith&_include=*}
 *       becomes {@code Smith%26_include%3D*} — the {@code &} cannot split the
 *       query string.</li>
 *   <li>Template IDs are validated against {@link #ALLOWED_TEMPLATES}.
 *       Unknown IDs → {@link UnknownTemplateException}.</li>
 * </ul>
 */
/**
 * Functional interface wrapping {@link HttpClient#send} so tests can mock
 * it without instrumenting the sealed JDK class.
 */
@FunctionalInterface
interface FhirHttpSend {
    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
}

public final class FhirTemplateExecutor {

    /** Registered template ids this executor handles. */
    public static final Set<String> ALLOWED_TEMPLATES = Set.of(
        "fhir.patient.read",
        "fhir.patient.search",
        "fhir.server.metadata"
    );

    /**
     * Whitelisted FHIR search parameter names for the Patient resource.
     * Mirrors the FHIR R4 search parameters that are safe to expose.
     * Any key outside this set is refused with {@link UnknownParameterException}.
     */
    public static final Set<String> SEARCH_PARAM_WHITELIST = Set.of(
        "identifier", "family", "given", "birthdate", "gender", "name"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final FhirHttpSend sender;

    public FhirTemplateExecutor() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.sender = req -> client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Test-friendly constructor accepting a mockable sender. */
    FhirTemplateExecutor(FhirHttpSend sender) {
        this.sender = sender;
    }

    /** Raised when the template id is not in {@link #ALLOWED_TEMPLATES}. */
    public static class UnknownTemplateException extends RuntimeException {
        public UnknownTemplateException(String templateId) {
            super("Unknown FHIR template '" + templateId
                + "'. Allowed: " + ALLOWED_TEMPLATES);
        }
    }

    /** Raised when a search parameter key is not in {@link #SEARCH_PARAM_WHITELIST}. */
    public static class UnknownParameterException extends RuntimeException {
        public UnknownParameterException(String param) {
            super("Unknown FHIR search parameter '" + param
                + "'. Allowed: " + SEARCH_PARAM_WHITELIST);
        }
    }

    /**
     * Execute a FHIR template. Returns a partial route-result map:
     * {@code {status, action, latency_ms, row_count, data}} on success or
     * {@code {status="error", action, latency_ms, error, error_details}} on failure.
     *
     * @throws UnknownTemplateException   templateId not in ALLOWED_TEMPLATES
     * @throws UnknownParameterException  a search param key not in SEARCH_PARAM_WHITELIST
     * @throws IllegalArgumentException   connection.base_url absent, blank, or fails validation
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(
        String templateId,
        Map<String, Object> connection,
        Map<String, Object> params
    ) {
        if (!ALLOWED_TEMPLATES.contains(templateId)) {
            throw new UnknownTemplateException(templateId);
        }
        Object rawUrl = connection != null ? connection.get("base_url") : null;
        if (!(rawUrl instanceof String) || ((String) rawUrl).isBlank()) {
            throw new IllegalArgumentException(
                "connection.base_url is required for run_template");
        }
        String baseUrl = validateAndNormalizeBaseUrl((String) rawUrl);
        Map<String, Object> safeParams = params != null ? params : Map.of();
        long start = System.currentTimeMillis();
        try {
            if ("fhir.patient.read".equals(templateId)) {
                return readPatient(start, baseUrl, safeParams);
            } else if ("fhir.server.metadata".equals(templateId)) {
                return readMetadata(start, baseUrl);
            } else {
                return searchPatients(start, baseUrl, safeParams);
            }
        } catch (UnknownParameterException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err(start, templateId, "fhir_transport_error", "FHIR call interrupted");
        } catch (IOException e) {
            return err(start, templateId, "fhir_transport_error",
                "FHIR call failed: IOException: " + e.getMessage());
        }
    }

    /**
     * Validates and normalizes the FHIR server base URL.
     *
     * <p>Rules:
     * <ul>
     *   <li>scheme must be {@code https}; {@code http} is allowed only for localhost / 127.0.0.1</li>
     *   <li>no userinfo (embedded credentials)</li>
     *   <li>no query string or fragment</li>
     * </ul>
     *
     * @throws IllegalArgumentException if any rule is violated
     */
    static String validateAndNormalizeBaseUrl(String raw) {
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "connection.base_url is not a valid URI: " + raw);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(
                "connection.base_url must include a scheme (https://): " + raw);
        }
        if ("http".equalsIgnoreCase(scheme)) {
            String host = uri.getHost();
            if (!("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
                throw new IllegalArgumentException(
                    "connection.base_url must use https (http is only allowed for "
                    + "localhost / 127.0.0.1): " + raw);
            }
        } else if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                "connection.base_url must use https scheme, got: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                "connection.base_url must not contain userinfo (credentials in URL)");
        }
        if (uri.getQuery() != null) {
            throw new IllegalArgumentException(
                "connection.base_url must not contain a query string: " + raw);
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException(
                "connection.base_url must not contain a fragment: " + raw);
        }
        String path = uri.getPath() != null ? uri.getPath().replaceAll("/+$", "") : "";
        return scheme.toLowerCase() + "://" + uri.getHost()
            + (uri.getPort() != -1 ? ":" + uri.getPort() : "")
            + path;
    }

    private Map<String, Object> readPatient(
        long start, String baseUrl, Map<String, Object> params
    ) throws IOException, InterruptedException {
        Object idObj = params.get("patient_id");
        if (!(idObj instanceof String) || ((String) idObj).isBlank()) {
            return err(start, "fhir.patient.read", "missing_parameter",
                "fhir.patient.read requires parameters.patient_id");
        }
        String patientId = URLEncoder.encode(
            (String) idObj, StandardCharsets.UTF_8);
        HttpResponse<String> resp = get(baseUrl + "/Patient/" + patientId);
        return wrapResponse(start, resp, "fhir.patient.read");
    }

    private Map<String, Object> readMetadata(
        long start, String baseUrl
    ) throws IOException, InterruptedException {
        HttpResponse<String> resp = get(baseUrl + "/metadata");
        return wrapResponse(start, resp, "fhir.server.metadata");
    }

    private Map<String, Object> searchPatients(
        long start, String baseUrl, Map<String, Object> params
    ) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!SEARCH_PARAM_WHITELIST.contains(key)) {
                throw new UnknownParameterException(key);
            }
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            query.append('=');
            query.append(URLEncoder.encode(
                String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        String url = baseUrl + "/Patient"
            + (query.length() > 0 ? "?" + query : "");
        HttpResponse<String> resp = get(url);
        return wrapResponse(start, resp, "fhir.patient.search");
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/fhir+json")
            .GET()
            .build();
        return sender.send(req);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> wrapResponse(
        long start, HttpResponse<String> resp, String templateId
    ) throws IOException {
        long latency = System.currentTimeMillis() - start;
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return err(start, templateId, "fhir_http_error",
                "FHIR server returned HTTP " + resp.statusCode()
                + ": " + truncate(resp.body()));
        }
        JsonNode json = MAPPER.readTree(resp.body());
        List<Map<String, Object>> data = new ArrayList<>();
        if (json.has("entry")) {
            for (JsonNode entry : json.get("entry")) {
                JsonNode resource = entry.get("resource");
                if (resource != null) {
                    data.add(MAPPER.convertValue(resource, Map.class));
                }
            }
        } else {
            data.add(MAPPER.convertValue(json, Map.class));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("action", "run_template");
        out.put("template_id", templateId);
        out.put("latency_ms", latency);
        out.put("row_count", data.size());
        out.put("data", data);
        return out;
    }

    private Map<String, Object> err(long start, String templateId, String error, String details) {
        long latency = System.currentTimeMillis() - start;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "error");
        out.put("action", "run_template");
        out.put("template_id", templateId);
        out.put("latency_ms", latency);
        out.put("error", error);
        out.put("error_details", details);
        return out;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
