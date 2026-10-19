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
            session(conn, type);
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

    /**
     * {@link SqlExecutor#executeParametricRead} for the same databases: the payload is parsed and checked by
     * {@link ReadRequest} (shared with Postgres), the table and columns by the catalog, the statement built in the dialect.
     */
    public static Map<String, Object> executeRead(DbType type, DataSource ds, Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        ReadRequest req;
        try {
            req = ReadRequest.parse(payload);
        } catch (IllegalArgumentException e) {
            return error(start, "query", e.getMessage());
        }
        try (Connection conn = ds.getConnection()) {
            session(conn, type);
            DialectCatalog cat = DialectCatalog.lookup(conn, type, req.table);
            if (!cat.exists) {
                return error(start, "query", "Table " + req.table.display() + " does not exist");
            }
            String missing = missingColumn(cat, req.table, req.columnsToValidate(), req.filterColumn);
            if (missing != null) {
                return error(start, "query", missing);
            }
            String typeError = req.valueTypeError();
            if (typeError != null) {
                return error(start, "query", typeError);
            }
            String sql = DialectReadSql.build(cat.dialect, req.pattern, cat.table, req.columns, req.filterColumn,
                req.filterValues != null ? req.filterValues.size() : 0, req.excludeTombstoned,
                req.keysetMode ? req.keysetField : null, req.keysetMode && req.keysetValue != null, req.whereConds);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bindRead(ps, req, cat);
                try (ResultSet rs = ps.executeQuery()) {
                    return readResult(rs, req, cat, start);
                }
            }
        } catch (SQLException e) {
            return error(start, "query", e.getMessage());
        }
    }

    /** Bind order = emission order: keyset comparator, filter value(s), predicate values, page size. */
    private static void bindRead(PreparedStatement ps, ReadRequest r, DialectCatalog cat) throws SQLException {
        int idx = 1;
        if (r.keysetMode && r.keysetValue != null) {
            DialectBinder.bind(ps, idx++, r.keysetValue, cat.types.get(r.keysetField));
        }
        if ("by_ids".equals(r.pattern)) {
            for (Object v : r.filterValues) {
                DialectBinder.bind(ps, idx++, v, cat.types.get(r.filterColumn));
            }
        } else if (r.filterValue != null && r.filterColumn != null && !r.filterColumn.isBlank()) {
            DialectBinder.bind(ps, idx++, r.filterValue, cat.types.get(r.filterColumn));
        }
        for (SqlExecutor.WhereCond c : r.whereConds) {
            String ct = cat.types.get(c.column);
            if ("is_null".equals(c.op) || "is_not_null".equals(c.op)) {
                continue;
            } else if ("between".equals(c.op)) {
                List<?> lh = (List<?>) c.value;
                DialectBinder.bind(ps, idx++, lh.get(0), ct);
                DialectBinder.bind(ps, idx++, lh.get(1), ct);
            } else if ("in".equals(c.op) || "not_in".equals(c.op)) {
                for (Object el : (List<?>) c.value) {
                    DialectBinder.bind(ps, idx++, el, ct);
                }
            } else if ("contains".equals(c.op)) {
                DialectBinder.bind(ps, idx++, "%" + c.value + "%", ct);
            } else {
                DialectBinder.bind(ps, idx++, c.value, ct);
            }
        }
        if (r.keysetMode) {
            ps.setInt(idx, r.effectiveLimit + 1);   // one sentinel row derives has_more
        }
    }

    private static Map<String, Object> readResult(ResultSet rs, ReadRequest r, DialectCatalog cat, long start)
        throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        int keysetCol = -1;
        if (r.keysetMode) {
            for (int i = 1; i <= n; i++) {
                if (r.keysetField.equals(cat.columnKey.apply(meta.getColumnLabel(i)))) {
                    keysetCol = i;
                    break;
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int fetchCap = r.keysetMode ? r.effectiveLimit + 1 : SqlExecutor.MAX_ROWS;
        while (rs.next() && rows.size() < fetchCap) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) {
                Object val = rs.getObject(i);
                row.put(cat.columnKey.apply(meta.getColumnLabel(i)), val != null ? val.toString() : null);
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("action", "query");
        result.put("latency_ms", System.currentTimeMillis() - start);
        if (r.keysetMode) {
            boolean hasMore = rows.size() > r.effectiveLimit;
            if (hasMore) {
                rows = rows.subList(0, r.effectiveLimit);   // trim the +1 sentinel
            }
            Object last = hasMore && !rows.isEmpty() && keysetCol > 0 ? rows.get(rows.size() - 1).get(r.keysetField) : null;
            result.put("row_count", rows.size());
            result.put("data", rows);
            result.put("has_more", hasMore);
            result.put("last_keyset_value", last);
        } else {
            result.put("row_count", rows.size());
            result.put("data", rows);
        }
        return result;
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

    private static void session(Connection conn, DbType type) throws SQLException {
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
