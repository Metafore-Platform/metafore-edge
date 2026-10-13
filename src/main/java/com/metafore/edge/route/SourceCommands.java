package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.db.DbType;
import com.metafore.edge.db.DbType.UnsupportedDbTypeException;
import com.metafore.edge.db.DialectGate;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.ConnectionTester;
import com.metafore.edge.service.DataSourceRegistry;

import java.util.Collections;
import java.util.Map;

/**
 * The database-source half of a route command: which kind of server it names ({@code db_type}), the pool that serves it,
 * and the {@code connect_and_ping} probe. Moved out of {@link RouteExecutorRoute} unchanged apart from {@code db_type}.
 */
final class SourceCommands {

    private SourceCommands() { }

    /**
     * Registers the pool a command's db params name. Null when registered (or when the command has no db params);
     * otherwise the error result to answer with (an unknown {@code db_type}).
     */
    static Map<String, Object> register(EdgeConfig config, DataSourceRegistry dsRegistry, String routeId,
                                        Map<String, Object> params) {
        if (!(params.containsKey("db_host") || params.containsKey("DB_HOST"))) {
            return null;
        }
        String dbHost = str(params, "db_host", "DB_HOST", "localhost");
        DbType dbType;
        try {
            dbType = DbType.of(str(params, "db_type", "DB_TYPE", ""));
        } catch (UnsupportedDbTypeException e) {
            return MessageFactory.routeResult(config, routeId, "error", "execute", 0,
                null, null, null, e.getMessage(), null);
        }
        String dbPort = str(params, "db_port", "DB_PORT", dbType.defaultPort());
        String dbName = str(params, "db_name", "DB_NAME", "");
        String dbUser = str(params, "db_user", "DB_USER", "root");
        String dbPass = str(params, "db_pass", "DB_PASS", "");
        // adr — one pool per DATABASE, not one per request. Core mints a
        // fresh route id per invocation for correlation, so registering
        // under it made every read and write leak a connection pool: 3,121
        // of them on the phluence controller before the JVM ran out of heap
        // and stopped answering route commands (2026-08-30). The pool
        // depends on where it connects, not on who asked.
        dsRegistry.register(dbType,
            DataSourceRegistry.connectionKey(dbType, dbHost, dbPort, dbName, dbUser),
            dbHost, dbPort, dbName, dbUser, dbPass);
        return null;
    }

    /**
     * Which pool serves this command.
     *
     * The connection key when the command carries db params — the same key
     * {@link #register} registered under. Falls back to the route id, then
     * (via the registry) to "default", so a command shaped differently than
     * expected still reaches a database rather than failing with "no database
     * connection available".
     */
    static String dataSourceKey(Map<String, Object> params, String routeId) {
        if (params != null
            && (params.containsKey("db_host") || params.containsKey("DB_HOST"))) {
            DbType type;
            try {
                type = DbType.of(str(params, "db_type", "DB_TYPE", ""));
            } catch (UnsupportedDbTypeException e) {
                return routeId; // register already refused this command
            }
            return DataSourceRegistry.connectionKey(type,
                str(params, "db_host", "DB_HOST", "localhost"),
                str(params, "db_port", "DB_PORT", type.defaultPort()),
                str(params, "db_name", "DB_NAME", ""),
                str(params, "db_user", "DB_USER", "root"));
        }
        return routeId;
    }

    /**
     * Phase 14.12 / MTBC.T1 — ephemeral connection probe. The action on the wire is {@code "connect_and_ping"} so
     * core's response handler can demultiplex from routine reads. The error envelope uses the typed reason (e.g.
     * {@code "host_unresolved"}) as the {@code error} field; the raw driver message goes in {@code error_details}.
     * Does not touch the pool registry. A non-Postgres source is refused until its probe lands ({@link DialectGate}).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> connectAndPing(EdgeConfig config, String routeId, Map<String, Object> cmd) {
        Map<String, Object> params = (Map<String, Object>)
            cmd.getOrDefault("parameters", Collections.emptyMap());

        String refused = DialectGate.refusal(params, "connect_and_ping");
        if (refused != null) {
            return MessageFactory.routeResult(config, routeId, "error", "connect_and_ping", 0,
                null, null, null, refused, DialectGate.REFUSAL);
        }
        String dbHost = str(params, "db_host", "DB_HOST", "");
        String dbPort = str(params, "db_port", "DB_PORT", "5432");
        String dbName = str(params, "db_name", "DB_NAME", "");
        String dbUser = str(params, "db_user", "DB_USER", "");
        String dbPass = str(params, "db_pass", "DB_PASS", "");

        ConnectionTester.ProbeResult probe =
            ConnectionTester.probe(dbHost, dbPort, dbName, dbUser, dbPass);

        if (probe.ok()) {
            return MessageFactory.routeResult(config, routeId,
                "success", "connect_and_ping", probe.latencyMs(),
                null, null, probe.data(), null, null);
        }
        return MessageFactory.routeResult(config, routeId,
            "error", "connect_and_ping", probe.latencyMs(),
            null, null, null, probe.reason(), probe.message());
    }

    private static String str(Map<String, Object> params, String key1, String key2, String def) {
        Object v = params.get(key1);
        if (v == null) v = params.get(key2);
        return v != null ? String.valueOf(v) : def;
    }
}
