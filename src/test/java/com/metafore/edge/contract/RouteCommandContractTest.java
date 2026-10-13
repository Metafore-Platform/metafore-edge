package com.metafore.edge.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's route_command and route_result contracts, pinned byte for byte from metafore-api
 * (packages/contracts/mqtt). The files under src/test/resources/schemas are copies made by
 * scripts/sync-contracts.sh; the PIN beside them records the metafore-api commit and each file's sha256, and the first
 * test fails when a copy is edited by hand or the PIN is stale. The rest say what the schema accepts and refuses, so a
 * change of the platform's contract shows up here as a red test, not as an edge that quietly misreads a command.
 */
class RouteCommandContractTest {

    private static final String COMMAND = "route_command.schema.json";
    private static final String RESULT = "route_result.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = RouteCommandContractTest.class.getResourceAsStream("/schemas/" + name)) {
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

    private static JsonSchema schema(String file) throws IOException {
        ObjectNode node = (ObjectNode) MAPPER.readTree(resource(file));
        node.remove("$id"); // not a URI; strict validation would try to resolve it
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
    }

    private static Set<ValidationMessage> check(String file, String json) throws IOException {
        return schema(file).validate(MAPPER.readTree(json));
    }

    // ── the pin ─────────────────────────────────────────────────────────────

    @Test
    void theSchemasAreTheBytesTheMetaforeApiCommitPins() throws Exception {
        Map<String, String> pin = pin();
        assertEquals("Metafore-Platform/metafore-api", pin.get("source_repo"));
        assertTrue(pin.get("source_commit").matches("[0-9a-f]{40}"), "source_commit must be a full sha: " + pin);
        for (String file : new String[] {COMMAND, RESULT}) {
            assertEquals(pin.get(file), sha256(resource(file)),
                file + " differs from the pinned bytes: re-pin with scripts/sync-contracts.sh <metafore-api sha>, "
                    + "never edit it by hand");
        }
    }

    // ── what the platform may send ──────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"action\":\"deploy\",\"route_id\":\"r-1\",\"route_yaml\":\"name: r\\nquery: SELECT 1\"}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"db_name\":\"d\",\"db_host\":\"h\",\"db_port\":5432,"
            + "\"db_user\":\"u\",\"db_pass\":\"p\",\"sql\":\"SELECT 1\"}}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"db_port\":\"5432\",\"operation\":\"read\","
            + "\"table_name\":\"t\"},\"response_topic\":\"response/acme/acme-managed/a-1\"}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"db_type\":\"mysql\",\"operation\":\"introspect\","
            + "\"introspect\":\"schemas\"}}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"operation\":\"introspect\",\"introspect\":\"columns\","
            + "\"schema_name\":\"s\",\"table_name\":\"t\"}}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"url\":\"https://x\",\"http_method\":\"POST\","
            + "\"http_headers\":{\"A\":\"b\"},\"http_body\":\"{}\"}}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"mcp_server_url\":\"https://m\",\"mcp_tool\":\"t\","
            + "\"mcp_arguments\":{\"k\":1},\"mcp_transport\":\"sse\"}}",
        "{\"action\":\"remove\",\"route_id\":\"r-1\"}",
    })
    void whatThePlatformSendsIsAccepted(String command) throws Exception {
        assertEquals(Set.of(), check(COMMAND, command));
    }

    // ── what the schema refuses ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"route_id\":\"r-1\"}",                                                              // no action
        "{\"action\":\"execute\"}",                                                            // no route_id
        "{\"action\":\"flush\",\"route_id\":\"r-1\"}",                                         // an action it does not know
        "{\"action\":\"execute\",\"route_id\":7}",                                             // route_id is a string
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"tenant_id\":\"t\"}",                   // a field it does not define
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"db_type\":\"sqlite\"}}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"http_method\":\"PATCH\"}}",
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"operation\":\"introspect\"}}",            // no 'introspect'
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"operation\":\"introspect\","
            + "\"introspect\":\"schemas\",\"sql\":\"SELECT 1\"}}",                            // introspect never carries sql
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"operation\":\"introspect\","
            + "\"introspect\":\"columns\",\"schema_name\":\"s\"}}",                           // columns needs table_name
        "{\"action\":\"execute\",\"route_id\":\"r-1\",\"parameters\":{\"operation\":\"introspect\","
            + "\"introspect\":\"tables\"}}",                                                   // tables needs schema_name
    })
    void whatTheSchemaRefusesIsRefused(String command) throws Exception {
        assertFalse(check(COMMAND, command).isEmpty(), "the pinned schema should refuse: " + command);
    }

    @Test
    void theCommandSchemaNamesTheActionsTheEdgeDispatches() throws Exception {
        JsonNode enumNode = MAPPER.readTree(resource(COMMAND)).at("/properties/action/enum");
        Set<String> actions = new java.util.TreeSet<>();
        enumNode.forEach(n -> actions.add(n.asText()));
        assertEquals(Set.of("deploy", "execute", "remove"), actions,
            "the platform's route actions changed: the edge's RouteExecutorRoute must handle each new one");
    }

    // ── what the edge replies (the pinned route_result) ─────────────────────

    @Test
    void aSuccessfulReplyIsAcceptedAndANonsenseStatusIsNot() throws Exception {
        String ok = "{\"controller_id\":\"acme-managed\",\"tenant_id\":\"acme\",\"route_id\":\"r-1\","
            + "\"timestamp\":\"2026-10-13T12:00:00Z\",\"status\":\"success\",\"action\":\"execute\"}";
        Set<ValidationMessage> errors = check(RESULT, ok);
        assertEquals(Set.of(), errors, errors.toString());
        assertFalse(check(RESULT, ok.replace("\"success\"", "\"great\"")).isEmpty());
    }
}
