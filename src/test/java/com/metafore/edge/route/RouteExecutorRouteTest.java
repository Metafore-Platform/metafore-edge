package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.service.Capabilities;
import com.metafore.edge.service.ConnectionTester;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteExecutorRouteTest {

    @Test
    void extractQueryFromYaml() {
        String yaml = "name: test-route\nquery: SELECT * FROM users\nparams: none";
        assertEquals("SELECT * FROM users", RouteYamlExtractor.extractQuery(yaml));
    }

    @Test
    void extractQueryMultiLine() {
        String yaml = "query: >\n  SELECT *\n  FROM users\n- next_section";
        assertEquals("SELECT * FROM users", RouteYamlExtractor.extractQuery(yaml));
    }

    @Test
    void extractQueryNull() {
        assertNull(RouteYamlExtractor.extractQuery(null));
        assertNull(RouteYamlExtractor.extractQuery("no query here"));
    }

    @Test
    void extractShellCommand() {
        String yaml = "exec: ps aux\nnext: value";
        assertEquals("ps aux", RouteYamlExtractor.extractShellCommand(yaml));
    }

    @Test
    void extractShellCommandNull() {
        assertNull(RouteYamlExtractor.extractShellCommand(null));
        assertNull(RouteYamlExtractor.extractShellCommand("no exec here"));
    }

    // ── Slice 33.1.0 — payload classification at dispatch boundary ──

    @Test
    void classifyPayloadParametric() {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("operation", "create");
        p.put("table_name", "gtm_accounts");
        assertEquals(
            RouteYamlExtractor.PayloadKind.PARAMETRIC,
            RouteYamlExtractor.classifyPayload(p, null)
        );
    }

    @Test
    void classifyPayloadLegacy() {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        // No 'operation' key — legacy SQL path.
        assertEquals(
            RouteYamlExtractor.PayloadKind.LEGACY,
            RouteYamlExtractor.classifyPayload(p, "SELECT 1")
        );
    }

    @Test
    void classifyPayloadAmbiguousBothPresent() {
        // Defense-in-depth: a payload carrying BOTH new parametric
        // 'operation' AND legacy 'sql' is a routing-layer bug.
        // Edge fails-closed rather than picking one and proceeding.
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("operation", "create");
        assertEquals(
            RouteYamlExtractor.PayloadKind.AMBIGUOUS,
            RouteYamlExtractor.classifyPayload(p, "SELECT 1")
        );
    }

    @Test
    void classifyPayloadMissingNeitherPresent() {
        // Empty params + no SQL anywhere → MISSING. Caller built a
        // malformed payload; edge surfaces a clear error.
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        assertEquals(
            RouteYamlExtractor.PayloadKind.MISSING,
            RouteYamlExtractor.classifyPayload(p, null)
        );
        assertEquals(
            RouteYamlExtractor.PayloadKind.MISSING,
            RouteYamlExtractor.classifyPayload(p, "")
        );
        assertEquals(
            RouteYamlExtractor.PayloadKind.MISSING,
            RouteYamlExtractor.classifyPayload(null, null)
        );
    }

    // ── Phase 14.12 / MTBC.T1 — connect_and_ping verb routing ──

    /**
     * Probe an unresolvable host so we exercise the full
     * SourceCommands.connectAndPing → ConnectionTester.probe → MessageFactory
     * envelope path without needing a live PG. The dsRegistry must not
     * receive any side-effect for either success or failure.
     */
    @Test
    void connectAndPingDoesNotRegisterDataSource() throws Exception {
        DefaultCamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);
        EdgeConfig cfg = EdgeConfig.from(java.util.Collections.emptyMap());
        TopicBuilder topics =
            new TopicBuilder(cfg.tenantId(), cfg.controllerId());
        RouteExecutorRoute route =
            new RouteExecutorRoute(cfg, topics, registry);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("db_host", "this-host-does-not-exist.invalid");
        params.put("db_port", "5432");
        params.put("db_name", "pharma");
        params.put("db_user", "u");
        params.put("db_pass", "p");
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", "connect_and_ping");
        cmd.put("route_id", "test-probe-1");
        cmd.put("parameters", params);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
            SourceCommands.connectAndPing(cfg, "test-probe-1", cmd);

        // Typed reason routed through the error field.
        assertEquals("error", result.get("status"));
        assertEquals("connect_and_ping", result.get("action"));
        assertEquals("host_unresolved", result.get("error"));
        assertNotNull(result.get("error_details"));

        // No registry side-effect — the next get() returns null (no
        // default DS bound either in this test fixture).
        assertNull(registry.get("test-probe-1"));
    }

    @Test
    void connectAndPingBlankHostReturnsUnknown() throws Exception {
        DefaultCamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);
        EdgeConfig cfg = EdgeConfig.from(java.util.Collections.emptyMap());
        TopicBuilder topics =
            new TopicBuilder(cfg.tenantId(), cfg.controllerId());
        RouteExecutorRoute route =
            new RouteExecutorRoute(cfg, topics, registry);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", "connect_and_ping");
        cmd.put("route_id", "test-probe-2");
        cmd.put("parameters", new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
            SourceCommands.connectAndPing(cfg, "test-probe-2", cmd);
        // No db_host → ConnectionTester returns reason=unknown,
        // message="host is required".
        assertEquals("error", result.get("status"));
        assertEquals("unknown", result.get("error"));
    }

    @Test
    void connectAndPingProbeResultExposesTypedReason() {
        // Direct ConnectionTester invocation — guards against future
        // refactors that bypass classify(). RFC-6761 reserved TLD
        // ensures DNS lookup terminates fast with NXDOMAIN.
        ConnectionTester.ProbeResult r = ConnectionTester.probe(
            "no-such-host-anywhere.invalid", "5432", "x", "u", "p");
        assertFalse(r.ok());
        assertEquals("host_unresolved", r.reason());
    }

    // ── CCA.T2 / compass ba53231a — capability_denied enforcement ──

    /**
     * Helper: build a RouteExecutorRoute, reflectively grab
     * {@code enforceCapability}, and invoke it. Returns the envelope or
     * null when the capability check passes (let the action dispatch).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeEnforce(
        String action, Map<String, Object> cmd
    ) throws Exception {
        DefaultCamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);
        EdgeConfig cfg = EdgeConfig.from(java.util.Collections.emptyMap());
        TopicBuilder topics =
            new TopicBuilder(cfg.tenantId(), cfg.controllerId());
        RouteExecutorRoute route =
            new RouteExecutorRoute(cfg, topics, registry);

        Method m = RouteExecutorRoute.class.getDeclaredMethod(
            "enforceCapability", String.class, String.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(
            route, action, "test-route", cmd);
    }

    @Test
    void enforceCapabilityPassesWhenDeclared() throws Exception {
        // All canonical actions this edge serves should pass through.
        assertTrue(Capabilities.DECLARED.contains("test_connection"));
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", "connect_and_ping");
        cmd.put("route_id", "test-route");
        cmd.put("parameters", new HashMap<>());
        assertNull(invokeEnforce("connect_and_ping", cmd),
            "edge advertises test_connection; should not deny");

        Map<String, Object> params = new HashMap<>();
        params.put("operation", "read");
        Map<String, Object> readCmd = new HashMap<>();
        readCmd.put("action", "execute");
        readCmd.put("route_id", "test-route");
        readCmd.put("parameters", params);
        assertNull(invokeEnforce("execute", readCmd),
            "edge advertises read; should not deny");
    }

    @Test
    void enforceCapabilityReturnsNullForUnknownAction() throws Exception {
        // Unknown actions are a parse error at the dispatch layer, NOT a
        // capability denial — enforceCapability must return null so the
        // existing 'Unknown action' branch surfaces the failure.
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", "teleport");
        cmd.put("route_id", "test-route");
        cmd.put("parameters", new HashMap<>());
        assertNull(invokeEnforce("teleport", cmd));
    }

    @Test
    void capabilityDeniedEnvelopeShape() throws Exception {
        // Simulate a restricted-profile edge that advertises only
        // 'read' + 'test_connection'. A write op must be rejected with
        // the typed capability_denied envelope shape core's response
        // handler branches on.
        DefaultCamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);
        EdgeConfig cfg = EdgeConfig.from(java.util.Collections.emptyMap());
        TopicBuilder topics =
            new TopicBuilder(cfg.tenantId(), cfg.controllerId());
        RouteExecutorRoute route =
            new RouteExecutorRoute(cfg, topics, registry);

        java.util.Set<String> readOnly = new java.util.HashSet<>();
        readOnly.add("read");
        readOnly.add("test_connection");

        Map<String, Object> params = new HashMap<>();
        params.put("operation", "update");
        params.put("table_name", "gtm_accounts");
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", "execute");
        cmd.put("route_id", "test-deny-1");
        cmd.put("parameters", params);

        Map<String, Object> envelope = route.enforceCapability(
            readOnly, "execute", "test-deny-1", cmd);

        assertNotNull(envelope, "write op must be denied on read-only edge");
        assertEquals("rejected", envelope.get("status"));
        assertEquals("execute", envelope.get("action"));
        assertEquals("capability_denied", envelope.get("error"));
        Object detailsObj = envelope.get("error_details");
        assertNotNull(detailsObj);
        String details = (String) detailsObj;
        assertTrue(details.contains("'write'"),
            "details must name the offending capability: " + details);
        assertTrue(details.contains("read") && details.contains("test_connection"),
            "details must list declared caps: " + details);
        // Latency is 0 for an immediate dispatch refusal — no DB hit.
        assertEquals(0L, envelope.get("latency_ms"));
        // No data / row counts on a refusal.
        assertNull(envelope.get("data"));
        assertNull(envelope.get("row_count"));
        assertNull(envelope.get("rows_affected"));
    }

    @Test
    void enforceCapabilityWithCustomDeclaredSetPassesWhenIncluded()
        throws Exception {
        // Inverse of the denial test: a restricted edge that advertises
        // only the operation needed must allow it through.
        DefaultCamelContext ctx = new DefaultCamelContext();
        DataSourceRegistry registry = new DataSourceRegistry(ctx);
        EdgeConfig cfg = EdgeConfig.from(java.util.Collections.emptyMap());
        TopicBuilder topics =
            new TopicBuilder(cfg.tenantId(), cfg.controllerId());
        RouteExecutorRoute route =
            new RouteExecutorRoute(cfg, topics, registry);

        java.util.Set<String> writeOnly = new java.util.HashSet<>();
        writeOnly.add("write");

        Map<String, Object> params = new HashMap<>();
        params.put("operation", "create");
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", "execute");
        cmd.put("route_id", "test-allow-1");
        cmd.put("parameters", params);

        assertNull(route.enforceCapability(
            writeOnly, "execute", "test-allow-1", cmd));
    }

    @Test
    void classifyPayloadBlankOperationStringTreatedAsMissing() {
        // Caller passing an empty-string 'operation' shouldn't be
        // accepted as parametric — that's a malformed payload, not
        // an explicit choice. Falls through to LEGACY/MISSING based
        // on whether sql is present.
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("operation", "   ");
        assertEquals(
            RouteYamlExtractor.PayloadKind.MISSING,
            RouteYamlExtractor.classifyPayload(p, null)
        );
        assertEquals(
            RouteYamlExtractor.PayloadKind.LEGACY,
            RouteYamlExtractor.classifyPayload(p, "SELECT 1")
        );
    }

    // ── part 2 interim guard: no Postgres SQL against another kind of server ──

    @Test
    void nonPostgresSourceIsRefusedBeforeSqlExecutor() throws Exception {
        EdgeConfig cfg = EdgeConfig.from(java.util.Collections.emptyMap());
        DataSourceRegistry registry = new DataSourceRegistry(new DefaultCamelContext());
        RouteExecutorRoute route = new RouteExecutorRoute(cfg, new TopicBuilder(cfg.tenantId(), cfg.controllerId()), registry);
        Method execute = RouteExecutorRoute.class.getDeclaredMethod("handleExecute", String.class, Map.class);
        execute.setAccessible(true);

        for (String type : new String[] {"mysql", "mariadb", "mssql", "oracle"}) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("db_type", type);
            params.put("db_host", "h.invalid");
            params.put("db_name", "d");
            params.put("db_user", "u");
            params.put("db_pass", "p");
            params.put("operation", "write_with_events");
            params.put("table_name", "t");
            Map<String, Object> cmd = new HashMap<>();
            cmd.put("parameters", params);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) execute.invoke(route, "r1", cmd);
            assertEquals("error", result.get("status"), type);
            assertEquals("dialect not yet supported for write_with_events (db_type " + type + ")", result.get("error"));
            assertEquals("dialect_not_supported", result.get("error_details"));

            @SuppressWarnings("unchecked")
            Map<String, Object> pinged = SourceCommands.connectAndPing(cfg, "r2", cmd);
            assertEquals("dialect_not_supported", pinged.get("error_details"), type);
        }
    }

    @Test
    void parametricOperationsPassTheGateOnlyForTypesProvenLive() {
        for (String type : new String[] {"mysql", "mariadb"}) {
            for (String op : new String[] {"create", "update", "delete", "select", "read"}) {
                assertNull(com.metafore.edge.db.DialectGate.refusal(Map.of("db_type", type), op), type + " " + op);
            }
            for (String op : new String[] {"write_with_events", "introspect"}) {
                assertNotNull(com.metafore.edge.db.DialectGate.refusal(Map.of("db_type", type), op), type + " " + op);
            }
        }
        for (String type : new String[] {"mssql", "oracle"}) {
            for (String op : new String[] {"create", "update", "delete", "select", "read"}) {
                assertNotNull(com.metafore.edge.db.DialectGate.refusal(Map.of("db_type", type), op), type + " " + op);
            }
        }
    }

    @Test
    void postgresAndUnsetTypePassTheGate() {
        assertNull(com.metafore.edge.db.DialectGate.refusal(Map.of("operation", "read"), "read"));
        assertNull(com.metafore.edge.db.DialectGate.refusal(Map.of("db_type", "postgresql"), "read"));
        assertNull(com.metafore.edge.db.DialectGate.refusal(null, "read"));
    }
}
