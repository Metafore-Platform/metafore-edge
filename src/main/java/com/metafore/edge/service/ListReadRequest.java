package com.metafore.edge.service;

import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import com.metafore.edge.service.SqlExecutor.WhereCond;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@code list_read} payload (adr-107): one list in one request. A base table, LEFT JOINs along declared references,
 * correlated counts of related rows, filters, sort and a page. Structured, never SQL text: every name is an identifier
 * checked here for shape and, before any statement is built, against INFORMATION_SCHEMA by {@link ListReadExecutor};
 * every value is bound.
 *
 * <pre>
 * { operation: "list_read",
 *   base:    {table, alias},
 *   joins:   [{table, alias, on: [{column, equals: "alias.column"}], where: [{column, op, value}]}],
 *   columns: [{source: "alias.column", as}],
 *   counts:  [{table, as, match: [{column, equals: "alias.column"}], where: [{column, op, value}]}],
 *   where:   [{column: "alias.column", op, value}],
 *   order_by:[{column: "alias.column", dir: "asc"|"desc"}],
 *   exclude_tombstoned: true, limit, offset }
 * </pre>
 */
final class ListReadRequest {

    static final int MAX_JOINS = 8;
    static final int MAX_COUNTS = 8;
    static final int MAX_COLUMNS = 100;
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_OFFSET = 100_000;
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** One table in the read: the base or a join. */
    static final class Source {
        final String alias;
        final ParsedTableName table;
        final List<Link> on;
        final List<WhereCond> where;

        Source(String alias, ParsedTableName table, List<Link> on, List<WhereCond> where) {
            this.alias = alias;
            this.table = table;
            this.on = on;
            this.where = where;
        }
    }

    /** {@code column} of the joined (or counted) table equals {@code alias.column} of one declared earlier. */
    static final class Link {
        final String column;
        final Ref equals;

        Link(String column, Ref equals) {
            this.column = column;
            this.equals = equals;
        }
    }

    /** {@code alias.column}. */
    static final class Ref {
        final String alias;
        final String column;

        Ref(String alias, String column) {
            this.alias = alias;
            this.column = column;
        }
    }

    static final class Column {
        final Ref source;
        final String as;

        Column(Ref source, String as) {
            this.source = source;
            this.as = as;
        }
    }

    static final class Count {
        final ParsedTableName table;
        final String as;
        final List<Link> match;
        final List<WhereCond> where;

        Count(ParsedTableName table, String as, List<Link> match, List<WhereCond> where) {
            this.table = table;
            this.as = as;
            this.match = match;
            this.where = where;
        }
    }

    static final class Sort {
        final Ref column;
        final boolean descending;

        Sort(Ref column, boolean descending) {
            this.column = column;
            this.descending = descending;
        }
    }

    /** A top-level filter on a column of any source. */
    static final class Filter {
        final Ref column;
        final WhereCond cond;

        Filter(Ref column, WhereCond cond) {
            this.column = column;
            this.cond = cond;
        }
    }

    final Source base;
    final List<Source> joins;
    final List<Column> columns;
    final List<Count> counts;
    final List<Filter> where;
    final List<Sort> orderBy;
    final boolean excludeTombstoned;
    final int limit;
    final int offset;

    private ListReadRequest(Source base, List<Source> joins, List<Column> columns, List<Count> counts,
                            List<Filter> where, List<Sort> orderBy, boolean excludeTombstoned, int limit, int offset) {
        this.base = base;
        this.joins = joins;
        this.columns = columns;
        this.counts = counts;
        this.where = where;
        this.orderBy = orderBy;
        this.excludeTombstoned = excludeTombstoned;
        this.limit = limit;
        this.offset = offset;
    }

    /** Every source by alias, base first. */
    Map<String, Source> sources() {
        Map<String, Source> out = new LinkedHashMap<>();
        out.put(base.alias, base);
        for (Source j : joins) {
            out.put(j.alias, j);
        }
        return out;
    }

    /** Parses and checks a payload's shape; an {@link IllegalArgumentException} carries the message to answer with. */
    @SuppressWarnings("unchecked")
    static ListReadRequest parse(Map<String, Object> p) {
        if (p == null) {
            throw new IllegalArgumentException("Missing list_read payload");
        }
        Map<String, Object> b = asMap(p.get("base"), "base");
        Source base = new Source(ident(b.get("alias"), "base.alias"), table(b.get("table"), "base.table"),
            List.of(), List.of());
        Map<String, Source> known = new LinkedHashMap<>();
        known.put(base.alias, base);

        List<Source> joins = new ArrayList<>();
        for (Object o : asList(p.get("joins"), "joins", MAX_JOINS)) {
            Map<String, Object> m = asMap(o, "join");
            String alias = ident(m.get("alias"), "join.alias");
            if (known.containsKey(alias)) {
                throw new IllegalArgumentException("alias used twice: " + alias);
            }
            List<Link> on = links(m.get("on"), known, "join.on");
            if (on.isEmpty()) {
                throw new IllegalArgumentException("join " + alias + " needs at least one 'on' link");
            }
            Source s = new Source(alias, table(m.get("table"), "join.table"), on,
                SqlExecutor.parseWhereConditions(m.get("where")));
            checkPlainColumns(s.where, "join " + alias);
            joins.add(s);
            known.put(alias, s);
        }

        List<Column> columns = new ArrayList<>();
        for (Object o : asList(p.get("columns"), "columns", MAX_COLUMNS)) {
            Map<String, Object> m = asMap(o, "column");
            columns.add(new Column(ref(m.get("source"), known, "column.source"), ident(m.get("as"), "column.as")));
        }

        List<Count> counts = new ArrayList<>();
        for (Object o : asList(p.get("counts"), "counts", MAX_COUNTS)) {
            Map<String, Object> m = asMap(o, "count");
            List<Link> match = links(m.get("match"), known, "count.match");
            if (match.isEmpty()) {
                throw new IllegalArgumentException("a count needs at least one 'match' link");
            }
            Count c = new Count(table(m.get("table"), "count.table"), ident(m.get("as"), "count.as"), match,
                SqlExecutor.parseWhereConditions(m.get("where")));
            checkPlainColumns(c.where, "count " + c.as);
            counts.add(c);
        }
        if (columns.isEmpty() && counts.isEmpty()) {
            throw new IllegalArgumentException("list_read needs at least one column or count");
        }

        List<Filter> where = new ArrayList<>();
        for (WhereCond c : SqlExecutor.parseWhereConditions(p.get("where"))) {
            Ref r = ref(c.column, known, "where.column");
            where.add(new Filter(r, new WhereCond(r.column, c.op, c.value)));
        }

        List<Sort> orderBy = new ArrayList<>();
        for (Object o : asList(p.get("order_by"), "order_by", MAX_COLUMNS)) {
            Map<String, Object> m = asMap(o, "order_by");
            Object dir = m.get("dir");
            if (dir != null && !"asc".equals(dir) && !"desc".equals(dir)) {
                throw new IllegalArgumentException("order_by.dir must be asc or desc");
            }
            orderBy.add(new Sort(ref(m.get("column"), known, "order_by.column"), "desc".equals(dir)));
        }

        int limit = number(p.get("limit"), DEFAULT_LIMIT, "limit");
        if (limit <= 0 || limit > SqlExecutor.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be 1.." + SqlExecutor.MAX_PAGE_SIZE + " (got " + limit + ")");
        }
        int offset = number(p.get("offset"), 0, "offset");
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("offset must be 0.." + MAX_OFFSET + " (got " + offset + ")");
        }
        return new ListReadRequest(base, joins, columns, counts, where, orderBy,
            Boolean.TRUE.equals(p.get("exclude_tombstoned")), limit, offset);
    }

    private static void checkPlainColumns(List<WhereCond> conds, String where) {
        for (WhereCond c : conds) {
            ident(c.column, where + " where.column");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Link> links(Object raw, Map<String, Source> known, String what) {
        List<Link> out = new ArrayList<>();
        for (Object o : asList(raw, what, 8)) {
            Map<String, Object> m = asMap(o, what);
            out.add(new Link(ident(m.get("column"), what + ".column"), ref(m.get("equals"), known, what + ".equals")));
        }
        return out;
    }

    private static Ref ref(Object raw, Map<String, Source> known, String what) {
        String s = raw == null ? "" : raw.toString().trim();
        int dot = s.indexOf('.');
        if (dot <= 0 || dot != s.lastIndexOf('.')) {
            throw new IllegalArgumentException(what + " must be alias.column (got '" + s + "')");
        }
        String alias = ident(s.substring(0, dot), what);
        if (!known.containsKey(alias)) {
            throw new IllegalArgumentException(what + " names an alias not declared before it: " + alias);
        }
        return new Ref(alias, ident(s.substring(dot + 1), what));
    }

    static String ident(Object raw, String what) {
        String s = raw == null ? "" : raw.toString().trim();
        if (!IDENT.matcher(s).matches()) {
            throw new IllegalArgumentException(what + " is not a plain identifier: '" + s + "'");
        }
        return s;
    }

    private static ParsedTableName table(Object raw, String what) {
        String s = raw == null ? "" : raw.toString().trim();
        ParsedTableName t = SqlExecutor.parseTableName(s);
        ident(t.schema, what);
        ident(t.table, what);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw, String what) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object raw, String what, int max) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException(what + " must be a list");
        }
        List<Object> l = (List<Object>) raw;
        if (l.size() > max) {
            throw new IllegalArgumentException(what + " has " + l.size() + " entries; at most " + max);
        }
        return l;
    }

    private static int number(Object raw, int dflt, String what) {
        if (raw == null) {
            return dflt;
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(what + " must be a number");
        }
        return ((Number) raw).intValue();
    }
}
