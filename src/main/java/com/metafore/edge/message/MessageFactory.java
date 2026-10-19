package com.metafore.edge.message;

import com.metafore.edge.config.EdgeConfig;

import java.time.Instant;
import java.util.*;

public final class MessageFactory {

    private MessageFactory() {}

    public static Map<String, Object> heartbeat(EdgeConfig config, String status,
            long uptimeSeconds, int routesDeployed, int routesActive,
            boolean dbConnected, Map<String, Object> dbStats) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("status", status);
        msg.put("uptime_seconds", uptimeSeconds);
        msg.put("routes_deployed", routesDeployed);
        msg.put("routes_active", routesActive);
        msg.put("db_connected", dbConnected);
        if (dbStats != null) {
            msg.put("db_stats", dbStats);
        }
        return msg;
    }

    /**
     * Backward-compat 5-arg overload. Calls the 8-arg variant with
     * {@code runtime=null}, {@code runtimeHints=null} — emitting a
     * payload that older Phase-13 schemas validated (no runtime field).
     */
    public static Map<String, Object> registration(EdgeConfig config,
            List<String> capabilities, String dbType, String dbHost, Integer dbPort) {
        return registration(config, capabilities, dbType, dbHost, dbPort,
            null, null);
    }

    /**
     * Registration payload composer.
     *
     * ADR-023: one tenant per edge in v2; {@code tenants} is never emitted.
     */
    public static Map<String, Object> registration(EdgeConfig config,
            List<String> capabilities, String dbType, String dbHost, Integer dbPort,
            String runtime, Map<String, Object> runtimeHints) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("version", config.edgeVersion());
        msg.put("capabilities", capabilities);
        if (dbType != null) {
            msg.put("db_type", dbType);
        }
        if (dbHost != null) {
            msg.put("db_host", dbHost);
        }
        if (dbPort != null) {
            msg.put("db_port", dbPort);
        }
        String dbName = config.dbName();
        if (dbName != null && !dbName.isEmpty()) {
            msg.put("db_name", dbName);
        }
        if (runtime != null && !runtime.isBlank()) {
            msg.put("runtime", runtime);
        }
        if (runtimeHints != null && !runtimeHints.isEmpty()) {
            msg.put("runtime_hints", runtimeHints);
        }
        return msg;
    }

    public static Map<String, Object> event(EdgeConfig config, String severity,
            String component, String logSource, String rawMessage) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("event_id", UUID.randomUUID().toString());
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("severity", severity);
        if (component != null) {
            msg.put("component", component);
        }
        if (logSource != null) {
            msg.put("log_source", logSource);
        }
        if (rawMessage != null) {
            msg.put("raw_message", rawMessage);
        }
        return msg;
    }

    public static Map<String, Object> routeResult(EdgeConfig config, String routeId,
            String status, String action, long latencyMs,
            Integer rowCount, Integer rowsAffected, List<Map<String, Object>> data,
            String error, String errorDetails) {
        // Back-compat overload: non-keyset callers carry no pagination signal.
        return routeResult(config, routeId, status, action, latencyMs,
            rowCount, rowsAffected, data, error, errorDetails, null, null);
    }

    /**
     * Route-result envelope with keyset pagination carried through.
     *
     * ``SqlExecutor.executeParametricRead`` computes ``has_more`` +
     * ``last_keyset_value`` for a keyset list read, but the envelope had no
     * slot for them, so ``executeRead`` dropped them and the dispatcher saw
     * every page as terminal (silent truncation at the page cap). Forward them
     * here — omitted (not emitted as null) when absent so non-keyset replies
     * are byte-for-byte unchanged, and so the dispatcher can distinguish
     * "no more rows" from "field not reported".
     */
    public static Map<String, Object> routeResult(EdgeConfig config, String routeId,
            String status, String action, long latencyMs,
            Integer rowCount, Integer rowsAffected, List<Map<String, Object>> data,
            String error, String errorDetails,
            Boolean hasMore, Object lastKeysetValue) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("route_id", routeId);
        msg.put("timestamp", Instant.now().toString());
        msg.put("status", status);
        if (action != null) {
            msg.put("action", action);
        }
        msg.put("latency_ms", latencyMs);
        if (rowCount != null) {
            msg.put("row_count", rowCount);
        }
        if (rowsAffected != null) {
            msg.put("rows_affected", rowsAffected);
        }
        if (data != null) {
            msg.put("data", data);
        }
        if (error != null) {
            msg.put("error", error);
        }
        if (errorDetails != null) {
            msg.put("error_details", errorDetails);
        }
        if (hasMore != null) {
            msg.put("has_more", hasMore);
        }
        if (lastKeysetValue != null) {
            msg.put("last_keyset_value", lastKeysetValue);
        }
        return msg;
    }

    public static Map<String, Object> writeBackResult(EdgeConfig config,
            String actionId, String status, int httpStatus, String responseBody,
            Map<String, Object> oldValues, Map<String, Object> newValues) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("action_id", actionId);
        msg.put("timestamp", Instant.now().toString());
        msg.put("status", status);
        msg.put("http_status", httpStatus);
        if (responseBody != null) {
            msg.put("response_body", responseBody);
        }
        if (oldValues != null) {
            msg.put("old_values", oldValues);
        }
        if (newValues != null) {
            msg.put("new_values", newValues);
        }
        return msg;
    }

    public static Map<String, Object> discoveryResult(EdgeConfig config,
            String runId, String trigger, Map<String, Object> capabilities) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("controller_id", config.controllerId());
        msg.put("tenant_id", config.tenantId());
        msg.put("timestamp", Instant.now().toString());
        msg.put("run_id", runId);
        msg.put("trigger", trigger);
        if (capabilities != null) {
            msg.put("capabilities", capabilities);
        }
        return msg;
    }
}
