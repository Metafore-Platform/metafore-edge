package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.db.DialectGate;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.Capabilities;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.service.FhirTemplateExecutor;
import com.metafore.edge.service.McpExecutor;
import com.metafore.edge.service.ShellExecutor;
import com.metafore.edge.service.SqlExecutor;
import com.metafore.edge.topic.ReplyTopic;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.paho.PahoConstants;

import javax.sql.DataSource;
import java.util.*;

public class RouteExecutorRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;
    private final DataSourceRegistry dsRegistry;
    private final McpExecutor mcpExecutor;
    private final FhirTemplateExecutor fhirExecutor;

    public RouteExecutorRoute(EdgeConfig config, TopicBuilder topics,
                              DataSourceRegistry dsRegistry) {
        this(config, topics, dsRegistry, new McpExecutor(), new FhirTemplateExecutor());
    }

    /**
     * Test-friendly constructor accepting pre-built executors (wrapping mock
     * HttpClients) so MCP and FHIR dispatch branches are exercised without
     * live servers. adr-158 (MCP), task 828b1be6 (FHIR).
     */
    RouteExecutorRoute(EdgeConfig config, TopicBuilder topics,
                       DataSourceRegistry dsRegistry, McpExecutor mcpExecutor,
                       FhirTemplateExecutor fhirExecutor) {
        this.config = config;
        this.topics = topics;
        this.dsRegistry = dsRegistry;
        this.mcpExecutor = mcpExecutor;
        this.fhirExecutor = fhirExecutor;
    }

    @Override
    public void configure() {
        // Phase 14.18 — routeId is suffixed with the tenant slug so a
        // multi-tenant edge runs one independently-named route per
        // tenant (route-executor-<slug>) and each subscription resolves
        // to a tenant-scoped control topic. The tenant_id is implicit in
        // the topic path; no header decode required.
        String camelRouteId = "route-executor-" + topics.tenantId();
        from("paho:" + topics.controlRoutes() + "?brokerUrl=" + config.brokerUrl())
            .routeId(camelRouteId)
            .log("Route command received on tenant=" + topics.tenantId() + ": ${body}")
            .unmarshal().json(Map.class)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = exchange.getIn().getBody(Map.class);
                String action = (String) cmd.getOrDefault("action", "");
                String routeId = (String) cmd.getOrDefault("route_id", "");
                // v2: the command's own reply topic, only when it is this edge's per-edge topic (topic/ReplyTopic)
                exchange.setProperty("mfReplyTopic", ReplyTopic.forEdge(cmd.get("response_topic"), topics));

                Map<String, Object> result = enforceCapability(action, routeId, cmd);
                if (result != null) {
                    // task 828b1be6: run_template must fail-fast with status=error
                    // (not rejected) and echo template_id so platform can correlate.
                    if ("run_template".equals(action)) {
                        result.put("status", "error");
                        result.put("template_id", cmd.getOrDefault("template_id", ""));
                    }
                    exchange.getIn().setBody(result);
                    return;
                }

                if ("deploy".equals(action) || "execute".equals(action)) {
                    result = handleExecute(routeId, cmd);
                } else if ("connect_and_ping".equals(action)) {
                    // Phase 14.12 / MTBC.T1 — ephemeral connection
                    // probe. Does NOT touch dsRegistry; does NOT route
                    // through SqlExecutor whitelist. See
                    // ConnectionTester for the typed-reason taxonomy.
                    result = SourceCommands.connectAndPing(config, routeId, cmd);
                } else if ("remove".equals(action)) {
                    dsRegistry.remove(routeId);
                    result = MessageFactory.routeResult(config, routeId,
                        "success", null, 0, null, null, null, null, null);
                } else if ("run_template".equals(action)) {
                    // task 828b1be6 — FHIR template registry
                    result = handleRunTemplate(routeId, cmd);
                } else {
                    result = MessageFactory.routeResult(config, routeId,
                        "error", null, 0, null, null, null,
                        "Unknown action: " + action, null);
                }

                exchange.getIn().setBody(result);
            })
            .marshal().json()
            // one String the telemetry publish and the reply publish both send in full (a stream is read once)
            .convertBodyTo(String.class)
            .to("paho:" + topics.telemetryRouteResults()
                + "?brokerUrl=" + config.brokerUrl()).id("route-result-publish")
            .choice()
                .when(exchangeProperty("mfReplyTopic").isNotNull())
                    // the topic goes in as a header on a FIXED endpoint, never into an endpoint URI built from the command
                    .setHeader(PahoConstants.CAMEL_PAHO_OVERRIDE_TOPIC, exchangeProperty("mfReplyTopic"))
                    .to("paho:" + topics.responsePrefix() + "reply?brokerUrl=" + config.brokerUrl()).id("route-reply-publish")
            .end()
            .log("Route result published");
    }

    /**
     * CCA.T2 / compass ba53231a — capability enforcement at the
     * dispatch boundary.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code null} when the action is unknown (let the existing
     *       "Unknown action" branch surface it — that's a parse error,
     *       not a capability denial) or when the required capability is
     *       in the edge's declared set (let dispatch proceed).</li>
     *   <li>A {@code capability_denied} envelope when the action's
     *       required capability is NOT in {@link Capabilities#DECLARED}.
     *       Envelope carries {@code status=rejected},
     *       {@code error=capability_denied}, and {@code error_details}
     *       naming the offending capability + the declared set, so the
     *       core-side response handler can route the audit row + UI
     *       message without parsing free-text.</li>
     * </ul>
     */
    private Map<String, Object> enforceCapability(
        String action, String routeId, Map<String, Object> cmd
    ) {
        return enforceCapability(
            Capabilities.DECLARED, action, routeId, cmd);
    }

    /**
     * Package-private overload — accepts a custom declared set so the
     * denial path can be exercised in unit tests without rebuilding
     * the image. Production callers go through the no-arg overload.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> enforceCapability(
        java.util.Set<String> declared,
        String action, String routeId, Map<String, Object> cmd
    ) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());
        String required = Capabilities.deriveRequiredCapability(action, params);
        if (required == null) {
            return null;
        }
        if (declared.contains(required)) {
            return null;
        }
        String details = "Edge does not advertise capability '" + required
            + "' for action '" + action + "'. Declared: " + declared + ".";
        return MessageFactory.routeResult(config, routeId,
            "rejected", action, 0, null, null, null,
            "capability_denied", details);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleExecute(String routeId, Map<String, Object> cmd) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());
        String routeYaml = (String) cmd.getOrDefault("route_yaml", "");

        // adr-158 — MCP transport. Keyed on mcp_tool presence; executes a
        // JSON-RPC tool call against an MCP-native system with credentials
        // (mcp_token) local to the controller. Checked before the DB /
        // shell / SQL branches because an MCP command carries none of
        // db_host / shell_command / operation.
        if (McpExecutor.isMcpCommand(params)) {
            return executeMcp(routeId, params);
        }

        // Register DataSource if DB params present
        Map<String, Object> refusedSource = SourceCommands.register(config, dsRegistry, routeId, params);
        if (refusedSource != null) {
            return refusedSource;
        }

        // Check for shell command
        String shellCmd = params != null ? (String) params.get("shell_command") : null;
        if (shellCmd == null && routeYaml != null && routeYaml.contains("exec:")) {
            shellCmd = RouteYamlExtractor.extractShellCommand(routeYaml);
        }

        if (shellCmd != null && !shellCmd.isEmpty()) {
            return executeShell(routeId, shellCmd);
        }

        // Slice 33.1.0 dispatch — parametric vs legacy SQL.
        // ``operation`` keys the only supported path
        // (PreparedStatement, INFORMATION_SCHEMA validation,
        // type-supported gate).
        //
        // security(adr-096): the legacy ``sql`` string-substitution path
        // is RETIRED. A payload carrying a raw ``sql`` key (the former
        // injection surface — unescaped ${param} interpolation executed
        // via a plain Statement) is now rejected with a structured
        // ``unsupported_payload`` envelope instead of being executed. The
        // edge accepts ONLY the parametric ``operation`` payload. We still
        // classify the shape so the rejection message is specific, but no
        // raw-SQL command can reach the database.
        String legacySql = params != null ? (String) params.get("sql") : null;
        if (legacySql == null && routeYaml != null) {
            legacySql = RouteYamlExtractor.extractQuery(routeYaml);
        }
        RouteYamlExtractor.PayloadKind kind =
            RouteYamlExtractor.classifyPayload(params, legacySql);
        switch (kind) {
            case AMBIGUOUS:
                return MessageFactory.routeResult(config, routeId,
                    "error", null, 0, null, null, null,
                    "Ambiguous payload: both 'operation' (parametric) "
                    + "and 'sql' (legacy) present. Send only 'operation'.",
                    null);
            case MISSING:
                return MessageFactory.routeResult(config, routeId,
                    "error", null, 0, null, null, null,
                    "Missing 'operation' (parametric path) in route "
                    + "parameters", null);
            case PARAMETRIC:
                return executeParametric(routeId, params);
            case LEGACY:
                // security(adr-096): retired raw-SQL path. Fail closed —
                // never route an unescaped ${param}-substituted string to
                // the database. Structured 'unsupported_payload' so core's
                // response handler can audit + surface without parsing
                // free text.
                return MessageFactory.routeResult(config, routeId,
                    "rejected", "query", 0, null, null, null,
                    "unsupported_payload",
                    "Legacy raw 'sql' payloads are no longer accepted. "
                    + "Send a parametric 'operation' "
                    + "(create/update/delete/select/read) payload. "
                    + "The unescaped string-substitution SQL path was "
                    + "retired (adr-096).");
            default:
                throw new IllegalStateException("PayloadKind: " + kind);
        }
    }

    /**
     * Slice 33.1.0 — parametric CRUD dispatch via JDBC PreparedStatement.
     * Reads structured payload, delegates to
     * ``SqlExecutor.executeParametric`` which validates table +
     * columns + value types before binding.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeParametric(
        String routeId, Map<String, Object> params
    ) {
        String operation = (String) params.get("operation");
        String refused = DialectGate.refusal(params, operation);
        if (refused != null) {
            return MessageFactory.routeResult(config, routeId, "error", "execute", 0,
                null, null, null, refused, DialectGate.REFUSAL);
        }
        DataSource ds = dsRegistry.get(SourceCommands.dataSourceKey(params, routeId));
        if (ds == null) {
            return MessageFactory.routeResult(config, routeId,
                "error", "execute", 0, null, null, null,
                "No database connection available", null);
        }

        // ADR-016 F1.T2b — "read" op has a richer payload shape
        // (pattern + filter_column + filter_value / filter_values +
        // exclude_tombstoned) than the write ops or the legacy "select"
        // op. Delegate to executeRead so the dispatch boundary stays
        // operation-aware without forcing the write payload shape onto
        // reads.
        if ("read".equals(operation)) {
            return executeRead(routeId, ds, params);
        }

        // adr-196 — transactional record-write + change-event emit. The
        // record write and its derived change-events commit in ONE JDBC
        // transaction (SqlExecutor.executeWriteWithEvents); the payload
        // carries the record write plus a generic field->event_type map.
        if ("write_with_events".equals(operation)) {
            return executeWriteWithEvents(routeId, ds, params);
        }

        String tableName = (String) params.get("table_name");
        Object colsObj = params.get("columns");
        Object valsObj = params.get("values");
        String whereColumn = (String) params.get("where_column");
        Object whereValue = params.get("where_value");

        if (!(colsObj instanceof List) || !(valsObj instanceof List)) {
            return MessageFactory.routeResult(config, routeId,
                "error", "execute", 0, null, null, null,
                "'columns' and 'values' must be present and be lists",
                null);
        }
        List<String> columns = new ArrayList<>();
        for (Object c : (List<Object>) colsObj) {
            columns.add(c == null ? null : c.toString());
        }
        List<Object> values = new ArrayList<>((List<Object>) valsObj);

        Map<String, Object> execResult = SourceCommands.executeParametric(
            params, ds, operation, tableName, columns, values, whereColumn, whereValue);
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault("action", "execute");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        Integer rowsAffected = (Integer) execResult.get("rows_affected");
        // Slice 33.1.0b — ``select`` returns row_count + data; writes
        // return rows_affected. Surface both — MessageFactory drops the
        // null fields from the wire envelope.
        Integer rowCount = (Integer) execResult.get("row_count");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data =
            (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, rowsAffected, data, error, null);
    }

    /**
     * ADR-016 F1.T2b — dispatch on-demand record reads to
     * {@link SqlExecutor#executeParametricRead}.
     *
     * Payload carries pattern + filter_column + filter_value /
     * filter_values + exclude_tombstoned alongside the standard
     * parametric envelope. Result shape mirrors the existing
     * ``select`` op (data array + row_count).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeRead(
        String routeId, DataSource ds, Map<String, Object> params
    ) {
        Map<String, Object> execResult =
            SqlExecutor.executeParametricRead(ds, params);
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault("action", "query");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        Integer rowCount = (Integer) execResult.get("row_count");
        List<Map<String, Object>> data =
            (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        // Keyset pagination: executeParametricRead sets has_more +
        // last_keyset_value on a keyset list read; forward them so the
        // dispatcher can page instead of treating the capped page as
        // complete. Absent (non-keyset reads) -> null -> omitted from the
        // envelope, preserving the pre-keyset reply shape exactly.
        Boolean hasMore = (Boolean) execResult.get("has_more");
        Object lastKeysetValue = execResult.get("last_keyset_value");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, null, data, error, null,
            hasMore, lastKeysetValue);
    }

    /**
     * adr-196 — dispatch a transactional record-write-with-events to
     * {@link SqlExecutor#executeWriteWithEvents}. The record write and
     * its derived change-events commit atomically in one JDBC
     * transaction against the tenant PG.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeWriteWithEvents(
        String routeId, DataSource ds, Map<String, Object> params
    ) {
        Map<String, Object> execResult =
            SqlExecutor.executeWriteWithEvents(ds, params);
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault(
            "action", "write_with_events");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        Integer rowsAffected = (Integer) execResult.get("rows_affected");
        String error = (String) execResult.get("error");
        Map<String, Object> result = MessageFactory.routeResult(config,
            routeId, status, action, latency, null, rowsAffected, null,
            error, null);
        // Surface the emitted-event count so core can assert the audit
        // trail landed without a follow-up read (Principle #46).
        Object emitted = execResult.get("events_emitted");
        if (emitted != null) {
            result.put("events_emitted", emitted);
        }
        return result;
    }

    /**
     * adr-158 — dispatch an MCP tool call to {@link McpExecutor} and wrap
     * its partial result into the standard route-result envelope
     * ({@code action="mcp"}). Credentials (mcp_token) arrive in-band on
     * the route command and never leave the controller.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeMcp(String routeId, Map<String, Object> params) {
        Map<String, Object> execResult = mcpExecutor.execute(params);
        String status = (String) execResult.get("status");
        String action = (String) execResult.getOrDefault("action", "mcp");
        long latency = ((Number) execResult.getOrDefault("latency_ms", 0L)).longValue();
        Integer rowCount = (Integer) execResult.get("row_count");
        List<Map<String, Object>> data =
            (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        String errorDetails = (String) execResult.get("error_details");
        return MessageFactory.routeResult(config, routeId,
            status, action, latency, rowCount, null, data, error, errorDetails);
    }

    private Map<String, Object> executeShell(String routeId, String command) {
        if (!ShellExecutor.isAllowed(command)) {
            return MessageFactory.routeResult(config, routeId,
                "rejected", "shell", 0, null, null, null,
                "Command not in whitelist: " + command.split("\\s+")[0], null);
        }
        Map<String, Object> execResult = ShellExecutor.execute(command);
        String status = (String) execResult.get("status");
        long latency = ((Number) execResult.get("latency_ms")).longValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) execResult.get("data");
        String error = (String) execResult.get("error");
        return MessageFactory.routeResult(config, routeId,
            status, "shell", latency, null, null, data, error, null);
    }

    // security(adr-096): the legacy executeSql(...) helper — which called
    // SqlExecutor.substituteParams (unescaped ${param} interpolation) and
    // then SqlExecutor.execute(ds, rawSql) via a plain Statement — has been
    // removed. The dispatch boundary (handleExecute) now rejects any raw
    // 'sql' payload with an 'unsupported_payload' envelope; only the
    // parametric PreparedStatement path (executeParametric / executeRead)
    // can reach the database.

    /**
     * task 828b1be6 — dispatch a {@code run_template} command to
     * {@link FhirTemplateExecutor} and wrap the result in the standard
     * route-result envelope.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleRunTemplate(
        String routeId, Map<String, Object> cmd
    ) {
        String templateId = (String) cmd.getOrDefault("template_id", "");
        Map<String, Object> connection = (Map<String, Object>)
            cmd.getOrDefault("connection", Map.of());
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Map.of());
        try {
            Map<String, Object> execResult =
                fhirExecutor.execute(templateId, connection, params);
            String status = (String) execResult.get("status");
            String action = (String) execResult.getOrDefault("action", "run_template");
            long latency =
                ((Number) execResult.getOrDefault("latency_ms", 0L)).longValue();
            Integer rowCount = (Integer) execResult.get("row_count");
            List<Map<String, Object>> data =
                (List<Map<String, Object>>) execResult.get("data");
            String error = (String) execResult.get("error");
            String errorDetails = (String) execResult.get("error_details");
            Map<String, Object> result = MessageFactory.routeResult(config, routeId,
                status, action, latency, rowCount, null, data, error, errorDetails);
            // Echo template_id so platform can correlate result to request.
            if (execResult.containsKey("template_id")) {
                result.put("template_id", execResult.get("template_id"));
            }
            return result;
        } catch (FhirTemplateExecutor.UnknownTemplateException e) {
            // task 828b1be6: fail fast, never silent — error not rejected
            Map<String, Object> r = MessageFactory.routeResult(config, routeId,
                "error", "run_template", 0, null, null, null,
                "unknown_template", e.getMessage());
            r.put("template_id", templateId);
            return r;
        } catch (FhirTemplateExecutor.UnknownParameterException e) {
            Map<String, Object> r = MessageFactory.routeResult(config, routeId,
                "error", "run_template", 0, null, null, null,
                "unknown_parameter", e.getMessage());
            r.put("template_id", templateId);
            return r;
        } catch (IllegalArgumentException e) {
            // missing / invalid connection.base_url
            Map<String, Object> r = MessageFactory.routeResult(config, routeId,
                "error", "run_template", 0, null, null, null,
                "invalid_connection", e.getMessage());
            r.put("template_id", templateId);
            return r;
        }
    }

    private static String str(Map<String, Object> params,
                               String key1, String key2, String def) {
        Object v = params.get(key1);
        if (v == null) v = params.get(key2);
        return v != null ? String.valueOf(v) : def;
    }

}
