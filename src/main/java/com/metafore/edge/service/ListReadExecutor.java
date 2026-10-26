package com.metafore.edge.service;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.ListReadRequest.Column;
import com.metafore.edge.service.ListReadRequest.Count;
import com.metafore.edge.service.ListReadRequest.Filter;
import com.metafore.edge.service.ListReadRequest.Link;
import com.metafore.edge.service.ListReadRequest.Sort;
import com.metafore.edge.service.ListReadRequest.Source;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import com.metafore.edge.service.SqlExecutor.WhereCond;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a {@code list_read} (adr-107): checks every table and column the request names against INFORMATION_SCHEMA,
 * renders one SELECT ({@link ListReadSql}), binds each value as its column's declared type, and reads one page plus a
 * sentinel row to say whether there is more. Read-only and bounded, like every read the edge runs.
 */
public final class ListReadExecutor {

    private ListReadExecutor() { }

    public static Map<String, Object> execute(DataSource ds, Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        ListReadRequest req;
        try {
            req = ListReadRequest.parse(payload);
        } catch (IllegalArgumentException e) {
            return error(start, e.getMessage());
        }
        try (Connection conn = ds.getConnection()) {
            Map<String, Map<String, String>> types = new HashMap<>();
            String bad = check(conn, req, types);
            if (bad != null) {
                return error(start, bad);
            }
            Set<String> tombstoned = new HashSet<>();
            for (Map.Entry<String, Map<String, String>> e : types.entrySet()) {
                if (e.getValue().containsKey("tombstoned_at")) {
                    tombstoned.add(e.getKey());
                }
            }
            ListReadSql rendered = ListReadSql.render(req, tombstoned);
            try (PreparedStatement ps = conn.prepareStatement(rendered.sql)) {
                ps.setQueryTimeout(30);
                int idx = 1;
                for (ListReadSql.Bind b : rendered.binds) {
                    SqlExecutor.bindValue(ps, idx++, b.value, types.get(ListReadSql.key(b.table)).get(b.column));
                }
                ps.setInt(idx++, req.limit + 1);
                ps.setInt(idx, req.offset);
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    while (rs.next() && rows.size() <= req.limit) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            Object v = rs.getObject(i);
                            row.put(meta.getColumnLabel(i), v instanceof Number ? v : (v == null ? null : v.toString()));
                        }
                        rows.add(row);
                    }
                }
                boolean more = rows.size() > req.limit;
                if (more) {
                    rows = rows.subList(0, req.limit);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "success");
                out.put("action", "query");
                out.put("latency_ms", System.currentTimeMillis() - start);
                out.put("row_count", rows.size());
                out.put("data", rows);
                out.put("has_more", more);
                return out;
            }
        } catch (SQLException e) {
            return error(start, e.getMessage());
        }
    }

    /** The first table or column the request names that the database does not have, or null. */
    static String check(Connection conn, ListReadRequest r, Map<String, Map<String, String>> types)
        throws SQLException {
        Map<String, Source> sources = r.sources();
        for (Source s : sources.values()) {
            String bad = known(conn, s.table, types);
            if (bad != null) {
                return bad;
            }
        }
        for (Count c : r.counts) {
            String bad = known(conn, c.table, types);
            if (bad != null) {
                return bad;
            }
        }
        List<String[]> named = new ArrayList<>();   // {schema.table, column}
        for (Column c : r.columns) {
            named.add(new String[] {ListReadSql.key(sources.get(c.source.alias).table), c.source.column});
        }
        for (Source j : r.joins) {
            for (Link l : j.on) {
                named.add(new String[] {ListReadSql.key(j.table), l.column});
                named.add(new String[] {ListReadSql.key(sources.get(l.equals.alias).table), l.equals.column});
            }
            for (WhereCond w : j.where) {
                named.add(new String[] {ListReadSql.key(j.table), w.column});
            }
        }
        for (Count c : r.counts) {
            for (Link l : c.match) {
                named.add(new String[] {ListReadSql.key(c.table), l.column});
                named.add(new String[] {ListReadSql.key(sources.get(l.equals.alias).table), l.equals.column});
            }
            for (WhereCond w : c.where) {
                named.add(new String[] {ListReadSql.key(c.table), w.column});
            }
        }
        for (Filter f : r.where) {
            named.add(new String[] {ListReadSql.key(sources.get(f.column.alias).table), f.column.column});
        }
        for (Sort s : r.orderBy) {
            named.add(new String[] {ListReadSql.key(sources.get(s.column.alias).table), s.column.column});
        }
        for (String[] n : named) {
            if (!types.get(n[0]).containsKey(n[1])) {
                return "Column " + n[1] + " does not exist on " + n[0];
            }
        }
        return null;
    }

    private static String known(Connection conn, ParsedTableName t, Map<String, Map<String, String>> types)
        throws SQLException {
        String key = ListReadSql.key(t);
        if (types.containsKey(key)) {
            return null;
        }
        Map<String, String> cols = SqlExecutor.fetchColumnTypes(conn, t);
        if (cols.isEmpty()) {
            return "Table " + t.display() + " does not exist";
        }
        types.put(key, cols);
        return null;
    }

    /** The route-result envelope core reads, with {@code has_more} for paging. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asRouteResult(EdgeConfig config, String routeId, Map<String, Object> r) {
        return MessageFactory.routeResult(config, routeId, (String) r.get("status"), "query",
            ((Number) r.get("latency_ms")).longValue(), (Integer) r.get("row_count"), null,
            (List<Map<String, Object>>) r.get("data"), (String) r.get("error"), null,
            (Boolean) r.get("has_more"), null);
    }

    private static Map<String, Object> error(long start, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "error");
        out.put("action", "query");
        out.put("latency_ms", System.currentTimeMillis() - start);
        out.put("error", message);
        return out;
    }
}
