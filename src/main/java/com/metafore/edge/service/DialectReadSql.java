package com.metafore.edge.service;

import com.metafore.edge.db.Dialect;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import com.metafore.edge.service.SqlExecutor.WhereCond;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The SELECT of a {@code read} pattern in a dialect. Same shapes and the same bind order as {@link SqlExecutor#buildReadSql}
 * (keyset comparator, then the pattern's filter value(s), then the predicate values, then the page size); identifiers are
 * quoted by the dialect, every value is a {@code ?}, and the page cut-off, the contains predicate and the count alias are the
 * dialect's. Postgres output equals {@code buildReadSql} for names without a quote character.
 */
final class DialectReadSql {

    private DialectReadSql() { }

    static String build(Dialect d, String pattern, ParsedTableName parsed, List<String> columns, String filterColumn,
                        int filterValuesCount, boolean excludeTombstoned, String keysetField, boolean keysetValuePresent,
                        List<WhereCond> whereConds) {
        boolean keysetMode = keysetField != null && !keysetField.isBlank();

        String proj;
        if ("count".equals(pattern)) {
            proj = d.countProjection();
        } else if (columns == null || columns.isEmpty()) {
            proj = "*";
        } else {
            StringJoiner pb = new StringJoiner(", ");
            for (String c : columns) {
                pb.add(d.quote(c));
            }
            // the cursor is read off the keyset field of the last row, so it must be projected
            if (keysetMode && !columns.contains(keysetField)) {
                pb.add(d.quote(keysetField));
            }
            proj = pb.toString();
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(proj).append(" FROM ")
            .append(DialectStatements.table(d, parsed));

        List<String> where = new ArrayList<>();
        if (keysetMode && keysetValuePresent) {
            where.add(d.quote(keysetField) + " > ?");
        }
        if ("by_id".equals(pattern)) {
            where.add(d.quote(filterColumn) + " = ?");
        } else if ("by_ids".equals(pattern)) {
            where.add(d.quote(filterColumn) + " IN (" + String.join(", ", java.util.Collections.nCopies(filterValuesCount, "?")) + ")");
        } else if (("list".equals(pattern) || "count".equals(pattern)) && filterColumn != null && !filterColumn.isBlank()) {
            where.add(d.quote(filterColumn) + " = ?");
        }
        if (whereConds != null) {
            for (WhereCond c : whereConds) {
                where.add(condition(d, c));
            }
        }
        if (excludeTombstoned) {
            where.add(d.quote("tombstoned_at") + " IS NULL");
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        if (keysetMode) {
            sql.append(" ORDER BY ").append(d.quote(keysetField)).append(" ASC").append(d.pageLimit("?"));
        }
        return sql.toString();
    }

    /** One abstract-predicate condition (ADR-077) as a parameterized fragment; bind order is {@code collectWhereBinds}. */
    static String condition(Dialect d, WhereCond c) {
        String col = d.quote(c.column);
        switch (c.op) {
            case "eq":       return col + " = ?";
            case "ne":       return col + " <> ?";
            case "gt":       return col + " > ?";
            case "gte":      return col + " >= ?";
            case "lt":       return col + " < ?";
            case "lte":      return col + " <= ?";
            case "between":  return col + " BETWEEN ? AND ?";
            case "contains": return d.contains(col, "?");
            case "is_null":     return col + " IS NULL";
            case "is_not_null": return col + " IS NOT NULL";
            case "in":
            case "not_in": {
                String marks = String.join(", ", java.util.Collections.nCopies(((List<?>) c.value).size(), "?"));
                return col + ("not_in".equals(c.op) ? " NOT IN (" : " IN (") + marks + ")";
            }
            default:
                // unreachable: parseWhereConditions allowlists ops
                throw new IllegalArgumentException("unsupported where op: " + c.op);
        }
    }
}
