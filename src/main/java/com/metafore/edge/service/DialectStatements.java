package com.metafore.edge.service;

import com.metafore.edge.db.Dialect;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;

import java.util.List;
import java.util.StringJoiner;

/**
 * The parametric statements ({@code create}, {@code update}, {@code delete}, {@code select}) in a dialect: identifiers
 * quoted by the dialect, every value a {@code ?}. Postgres output is {@link SqlExecutor#buildSql} exactly (for names without
 * a quote character); the other dialects follow the same shapes.
 */
final class DialectStatements {

    private DialectStatements() { }

    static String table(Dialect d, ParsedTableName parsed) {
        return parsed.schemaWasExplicit ? d.quote(parsed.schema) + "." + d.quote(parsed.table) : d.quote(parsed.table);
    }

    static String build(Dialect d, String operation, ParsedTableName parsed, List<String> columns, String whereColumn) {
        String table = table(d, parsed);
        switch (operation) {
            case "create": {
                StringJoiner cols = new StringJoiner(", ");
                StringJoiner qs = new StringJoiner(", ");
                for (String c : columns) {
                    cols.add(d.quote(c));
                    qs.add("?");
                }
                return "INSERT INTO " + table + " (" + cols + ") VALUES (" + qs + ")";
            }
            case "update":
            case "delete": {
                // delete is a parametric UPDATE too: the tombstone columns arrive through columns and values
                StringJoiner set = new StringJoiner(", ");
                for (String c : columns) {
                    set.add(d.quote(c) + " = ?");
                }
                return "UPDATE " + table + " SET " + set + " WHERE " + d.quote(whereColumn) + " = ?";
            }
            case "select": {
                String proj = columns == null || columns.isEmpty() ? "*" : projection(d, columns);
                return "SELECT " + proj + " FROM " + table + " WHERE " + d.quote(whereColumn) + " = ?";
            }
            default:
                throw new IllegalStateException("build: " + operation);
        }
    }

    private static String projection(Dialect d, List<String> columns) {
        StringJoiner proj = new StringJoiner(", ");
        for (String c : columns) {
            proj.add(d.quote(c));
        }
        return proj.toString();
    }
}
