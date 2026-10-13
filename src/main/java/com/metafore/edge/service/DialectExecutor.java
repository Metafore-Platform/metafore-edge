package com.metafore.edge.service;

import com.metafore.edge.db.DbType;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link SqlExecutor#executeParametric} for MySQL, MariaDB, SQL Server and Oracle: the same checks in the same order, the
 * same messages and the same reply, with the catalog read from {@link DialectCatalog} and the statement built in the
 * source's dialect. Postgres keeps its own path.
 */
public final class DialectExecutor {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DialectExecutor.class);

    private DialectExecutor() { }

    /** SQL run on every connection before a command, so a session behaves as the Postgres pools do (UTC, bounded waits). */
    static List<String> sessionSql(DbType type) {
        return switch (type) {
            case MYSQL, MARIADB -> List.of("SET time_zone = '+00:00'");
            case ORACLE -> List.of("ALTER SESSION SET TIME_ZONE = '+00:00' NLS_NUMERIC_CHARACTERS = '.,'");
            default -> List.of();
        };
    }

    public static Map<String, Object> executeParametric(
        DbType type, DataSource ds, String operation, String tableName, List<String> columns, List<Object> values,
        String whereColumn, Object whereValue
    ) {
        long start = System.currentTimeMillis();
        boolean isSelect = "select".equals(operation);
        if (!Set.of("create", "update", "delete", "select").contains(operation)) {
            return error(start, "execute", "Unsupported operation: " + operation + " (expected create/update/delete/select)");
        }
        if (tableName == null || tableName.isBlank()) {
            return error(start, "execute", "Missing or empty table_name");
        }
        ParsedTableName parsed;
        try {
            parsed = SqlExecutor.parseTableName(tableName);
        } catch (IllegalArgumentException e) {
            return error(start, "execute", e.getMessage());
        }
        if (!isSelect && (columns == null || values == null || columns.size() != values.size())) {
            return error(start, "execute", "columns and values must be present and equal length"
                + " (columns=" + (columns == null ? -1 : columns.size())
                + ", values=" + (values == null ? -1 : values.size()) + ")");
        }
        if (("update".equals(operation) || "delete".equals(operation) || isSelect)
            && (whereColumn == null || whereColumn.isBlank())) {
            return error(start, "execute", operation + " requires where_column");
        }

        try (Connection conn = ds.getConnection()) {
            if (type == DbType.MYSQL) {
                // sslMode=trust (DataSources): encrypted, but the server's certificate is not checked
                LOG.warn("MySQL connection to {} is encrypted but the server is not verified (no CA configured)",
                    conn.getMetaData().getURL().replaceFirst("\\?.*$", ""));
            }
            try (Statement st = conn.createStatement()) {
                for (String s : sessionSql(type)) {
                    st.execute(s);
                }
            }
            DialectCatalog cat = DialectCatalog.lookup(conn, type, parsed);
            if (!cat.exists) {
                return error(start, "execute", "Table " + parsed.display() + " does not exist");
            }
            boolean allColumns = isSelect && (columns == null || columns.isEmpty());
            String missing = missingColumn(cat, parsed, allColumns ? null : columns, whereColumn);
            if (missing != null) {
                return error(start, "execute", missing);
            }
            String typeError = SqlExecutor.validateValueTypes(isSelect ? Collections.emptyList() : values, whereValue);
            if (typeError != null) {
                return error(start, "execute", typeError);
            }

            String sql = DialectStatements.build(cat.dialect, operation, cat.table, columns, whereColumn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (!isSelect) {
                    for (int i = 0; i < values.size(); i++) {
                        DialectBinder.bind(ps, idx++, values.get(i), cat.types.get(columns.get(i)));
                    }
                }
                if (!"create".equals(operation)) {
                    DialectBinder.bind(ps, idx, whereValue, cat.types.get(whereColumn));
                }
                Map<String, Object> result = new LinkedHashMap<>();
                if (isSelect) {
                    try (ResultSet rs = ps.executeQuery()) {
                        List<Map<String, Object>> rows = rows(rs, cat);
                        result.put("status", "success");
                        result.put("action", "query");
                        result.put("latency_ms", System.currentTimeMillis() - start);
                        result.put("row_count", rows.size());
                        result.put("data", rows);
                        return result;
                    }
                }
                int affected = ps.executeUpdate();
                result.put("status", "success");
                result.put("action", "execute");
                result.put("latency_ms", System.currentTimeMillis() - start);
                result.put("rows_affected", affected);
                return result;
            }
        } catch (SQLException e) {
            return error(start, isSelect ? "query" : "execute", e.getMessage());
        }
    }

    private static String missingColumn(DialectCatalog cat, ParsedTableName parsed, List<String> columns, String whereColumn) {
        if (columns != null) {
            for (String c : columns) {
                if (c == null || !cat.types.containsKey(c)) {
                    return "Column " + c + " does not exist on table " + parsed.display();
                }
            }
        }
        if (whereColumn != null && !cat.types.containsKey(whereColumn)) {
            return "Column " + whereColumn + " does not exist on table " + parsed.display();
        }
        return null;
    }

    /** At most {@link SqlExecutor#MAX_ROWS} rows, every value as the JDBC object's text (or null). */
    private static List<Map<String, Object>> rows(ResultSet rs, DialectCatalog cat) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < SqlExecutor.MAX_ROWS) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) {
                Object val = rs.getObject(i);
                row.put(cat.columnKey.apply(meta.getColumnLabel(i)), val != null ? val.toString() : null);
            }
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> error(long start, String action, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("action", action);
        r.put("latency_ms", System.currentTimeMillis() - start);
        r.put("error", message);
        return r;
    }
}
