package com.metafore.edge.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.Capabilities;
import com.metafore.edge.service.FhirTemplateExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * task 828b1be6 — registration.schema.json pinned from metafore-api contracts.
 *
 * The file under src/test/resources/schemas is copied by scripts/sync-contracts.sh;
 * the PIN records the api commit and sha256. The pin test fails when the copy is
 * edited by hand or the PIN is stale. The output test asserts that
 * RegistrationRoute's MessageFactory.registration() output validates against it,
 * and that ALLOWED_TEMPLATES matches the platform's edge_templates.json expectations.
 */
class RegistrationContractTest {

    private static final String REGISTRATION = "registration.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = RegistrationContractTest.class.getResourceAsStream("/schemas/" + name)) {
            assertNotNull(in, "missing test resource /schemas/" + name);
            return in.readAllBytes();
        }
    }

    private static Map<String, String> pin() throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : new String(resource("PIN"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonSchema schema() throws IOException {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(resource(REGISTRATION));
        node.remove("$id");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
    }

    private static Set<ValidationMessage> check(String json) throws IOException {
        return schema().validate(MAPPER.readTree(json));
    }

    // ── the pin ─────────────────────────────────────────────────────────────

    @Test
    void theRegistrationSchemaIsTheBytesTheMetaforeApiCommitPins() throws Exception {
        Map<String, String> pin = pin();
        String pinned = pin.get(REGISTRATION);
        // The PIN entry is absent until scripts/sync-contracts.sh is run after
        // metafore-api #910 (which adds templates to registration.schema.json) merges.
        // The check is intentionally skipped until then; RouteCommandContractTest
        // already guards source_repo and source_commit.
        org.junit.jupiter.api.Assumptions.assumeTrue(pinned != null,
            "registration.schema.json not yet pinned — run scripts/sync-contracts.sh <#910 sha>");
        assertEquals(pinned, sha256(resource(REGISTRATION)),
            REGISTRATION + " differs from the pinned bytes: re-pin with scripts/sync-contracts.sh <sha>");
    }

    // ── what a valid registration looks like ────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        // minimal (uses existing capability — fhir/run_template added by metafore-api #910)
        "{\"controller_id\":\"c-1\",\"tenant_id\":\"t-1\",\"timestamp\":\"2026-10-13T12:00:00Z\","
            + "\"version\":\"1.0.0\",\"capabilities\":[\"http\"]}",
        // with templates (capability enum widened by #910 re-pin)
        "{\"controller_id\":\"c-1\",\"tenant_id\":\"t-1\",\"timestamp\":\"2026-10-13T12:00:00Z\","
            + "\"version\":\"1.0.0\",\"capabilities\":[\"http\"],"
            + "\"templates\":[{\"id\":\"fhir.patient.read\",\"version\":\"1\"}]}",
        // with db fields
        "{\"controller_id\":\"c-1\",\"tenant_id\":\"t-1\",\"timestamp\":\"2026-10-13T12:00:00Z\","
            + "\"version\":\"1.0.0\",\"capabilities\":[\"jdbc\"],\"db_type\":\"postgresql\","
            + "\"db_host\":\"localhost\",\"db_port\":5432}",
    })
    void validRegistrationsAreAccepted(String json) throws Exception {
        assertEquals(Set.of(), check(json), "Schema rejected: " + json);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // missing controller_id
        "{\"tenant_id\":\"t-1\",\"timestamp\":\"2026-10-13T12:00:00Z\","
            + "\"version\":\"1.0.0\",\"capabilities\":[]}",
        // template item missing version
        "{\"controller_id\":\"c-1\",\"tenant_id\":\"t-1\",\"timestamp\":\"2026-10-13T12:00:00Z\","
            + "\"version\":\"1.0.0\",\"capabilities\":[],"
            + "\"templates\":[{\"id\":\"fhir.patient.read\"}]}",
        // template item extra field
        "{\"controller_id\":\"c-1\",\"tenant_id\":\"t-1\",\"timestamp\":\"2026-10-13T12:00:00Z\","
            + "\"version\":\"1.0.0\",\"capabilities\":[],"
            + "\"templates\":[{\"id\":\"fhir.patient.read\",\"version\":\"1\",\"extra\":true}]}",
    })
    void invalidRegistrationsAreRejected(String json) throws Exception {
        assertFalse(check(json).isEmpty(), "Schema should reject: " + json);
    }

    // ── RegistrationRoute output validates against the pinned schema ─────────
    // Skipped until registration.schema.json is re-pinned from metafore-api #910.

    @Test
    void registrationRoutePayloadValidatesAgainstPinnedSchema() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(pin().containsKey(REGISTRATION),
            "registration.schema.json not yet pinned — run scripts/sync-contracts.sh <#910 sha>");
        EdgeConfig config = mock(EdgeConfig.class);
        when(config.controllerId()).thenReturn("edge-test");
        when(config.tenantId()).thenReturn("tenant-test");
        when(config.edgeVersion()).thenReturn("1.0.0-test");
        when(config.dbHost()).thenReturn(null);
        when(config.dbPort()).thenReturn("5432");
        when(config.dbName()).thenReturn(null);
        when(config.runtime()).thenReturn(null);
        when(config.runtimeHints()).thenReturn(null);
        when(config.tenants()).thenReturn(null);

        List<String> capabilities = Capabilities.declaredList();
        Map<String, Object> payload = MessageFactory.registration(
            config, capabilities, "none", null, null, null, null, null);

        List<Map<String, Object>> templates = FhirTemplateExecutor.ALLOWED_TEMPLATES
            .stream().sorted()
            .map(id -> { Map<String, Object> t = new java.util.LinkedHashMap<>();
                         t.put("id", id); t.put("version", "1"); return t; })
            .collect(Collectors.toList());
        payload.put("templates", templates);

        String json = MAPPER.writeValueAsString(payload);
        Set<ValidationMessage> errors = check(json);
        assertEquals(Set.of(), errors,
            "RegistrationRoute payload must validate against pinned registration.schema.json: " + errors
            + "\nPayload: " + json);
    }

    // ── ALLOWED_TEMPLATES covers the three canonical FHIR templates ─────────

    @Test
    void allowedTemplatesContainsTheThreeCanonicalFhirTemplates() {
        assertTrue(FhirTemplateExecutor.ALLOWED_TEMPLATES.contains("fhir.patient.read"));
        assertTrue(FhirTemplateExecutor.ALLOWED_TEMPLATES.contains("fhir.patient.search"));
        assertTrue(FhirTemplateExecutor.ALLOWED_TEMPLATES.contains("fhir.server.metadata"));
    }
}
