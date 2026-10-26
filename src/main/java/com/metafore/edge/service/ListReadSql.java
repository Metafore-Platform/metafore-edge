package com.metafore.edge.service;

import com.metafore.edge.service.ListReadRequest.Column;
import com.metafore.edge.service.ListReadRequest.Count;
import com.metafore.edge.service.ListReadRequest.Filter;
import com.metafore.edge.service.ListReadRequest.Link;
import com.metafore.edge.service.ListReadRequest.Ref;
import com.metafore.edge.service.ListReadRequest.Sort;
import com.metafore.edge.service.ListReadRequest.Source;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import com.metafore.edge.service.SqlExecutor.WhereCond;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a checked {@link ListReadRequest} as one parameterised SELECT (adr-107). Identifiers are quoted and have
 * been checked against INFORMATION_SCHEMA before this runs; values are never interpolated — each placeholder is
 * returned with the table and column whose declared type binds it, in placeholder order.
 */
final class ListReadSql {

    /** A value to bind, with the table and column whose type it binds as. */
    static final class Bind {
        final Object value;
        final ParsedTableName table;
        final String column;

        Bind(Object value, ParsedTableName table, String column) {
            this.value = value;
            this.table = table;
            this.column = column;
        }
    }

    final String sql;
    final List<Bind> binds;

    private ListReadSql(String sql, List<Bind> binds) {
        this.sql = sql;
        this.binds = binds;
    }

    /**
     * @param tombstoned the tables that carry {@code tombstoned_at}; a retired row is left out of each of them when
     *                   the request asks
     */
    static ListReadSql render(ListReadRequest r, Set<String> tombstoned) {
        Map<String, Source> sources = r.sources();
        List<Bind> binds = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> select = new ArrayList<>();
        for (Column c : r.columns) {
            select.add(ref(c.source) + " AS " + q(c.as));
        }
        int n = 0;
        for (Count c : r.counts) {
            String a = "c" + n++;
            StringBuilder s = new StringBuilder("(SELECT COUNT(*) FROM ").append(table(c.table)).append(' ').append(q(a))
                .append(" WHERE ");
            List<String> conds = new ArrayList<>();
            for (Link l : c.match) {
                conds.add(q(a) + "." + q(l.column) + " = " + ref(l.equals));
            }
            for (WhereCond w : c.where) {
                conds.add(cond(q(a) + "." + q(w.column), w, c.table, binds));
            }
            if (r.excludeTombstoned && tombstoned.contains(key(c.table))) {
                conds.add(q(a) + "." + q("tombstoned_at") + " IS NULL");
            }
            s.append(String.join(" AND ", conds)).append(") AS ").append(q(c.as));
            select.add(s.toString());
        }
        sql.append(String.join(", ", select));
        sql.append(" FROM ").append(table(r.base.table)).append(' ').append(q(r.base.alias));
        for (Source j : r.joins) {
            List<String> on = new ArrayList<>();
            for (Link l : j.on) {
                on.add(q(j.alias) + "." + q(l.column) + " = " + ref(l.equals));
            }
            for (WhereCond w : j.where) {
                on.add(cond(q(j.alias) + "." + q(w.column), w, j.table, binds));
            }
            if (r.excludeTombstoned && tombstoned.contains(key(j.table))) {
                on.add(q(j.alias) + "." + q("tombstoned_at") + " IS NULL");
            }
            sql.append(" LEFT JOIN ").append(table(j.table)).append(' ').append(q(j.alias))
                .append(" ON ").append(String.join(" AND ", on));
        }
        List<String> where = new ArrayList<>();
        for (Filter f : r.where) {
            where.add(cond(ref(f.column), f.cond, sources.get(f.column.alias).table, binds));
        }
        if (r.excludeTombstoned && tombstoned.contains(key(r.base.table))) {
            where.add(q(r.base.alias) + "." + q("tombstoned_at") + " IS NULL");
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        List<String> order = new ArrayList<>();
        for (Sort s : r.orderBy) {
            order.add(ref(s.column) + (s.descending ? " DESC NULLS LAST" : " ASC NULLS LAST"));
        }
        if (!order.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", order));
        }
        sql.append(" LIMIT ? OFFSET ?");
        return new ListReadSql(sql.toString(), binds);
    }

    /** A table's identity for the tombstone set: {@code schema.table}. */
    static String key(ParsedTableName t) {
        return t.schema + "." + t.table;
    }

    private static String cond(String col, WhereCond w, ParsedTableName table, List<Bind> binds) {
        switch (w.op) {
            case "is_null":
                return col + " IS NULL";
            case "is_not_null":
                return col + " IS NOT NULL";
            case "between": {
                List<?> lh = (List<?>) w.value;
                binds.add(new Bind(lh.get(0), table, w.column));
                binds.add(new Bind(lh.get(1), table, w.column));
                return col + " BETWEEN ? AND ?";
            }
            case "in":
            case "not_in": {
                List<?> vals = (List<?>) w.value;
                List<String> ph = new ArrayList<>();
                for (Object v : vals) {
                    binds.add(new Bind(v, table, w.column));
                    ph.add("?");
                }
                return col + ("in".equals(w.op) ? " IN (" : " NOT IN (") + String.join(", ", ph) + ")";
            }
            case "contains":
                binds.add(new Bind("%" + w.value + "%", table, w.column));
                return col + "::text ILIKE ?";
            default:
                binds.add(new Bind(w.value, table, w.column));
                return col + " " + op(w.op) + " ?";
        }
    }

    private static String op(String op) {
        switch (op) {
            case "eq": return "=";
            case "ne": return "<>";
            case "gt": return ">";
            case "gte": return ">=";
            case "lt": return "<";
            case "lte": return "<=";
            default: throw new IllegalArgumentException("unsupported where op: " + op);
        }
    }

    private static String ref(Ref r) {
        return q(r.alias) + "." + q(r.column);
    }

    private static String table(ParsedTableName t) {
        return q(t.schema) + "." + q(t.table);
    }

    private static String q(String ident) {
        return "\"" + ident + "\"";
    }
}
