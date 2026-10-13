package com.metafore.edge.service;

import com.metafore.edge.db.BindTypes;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Binds one value on a non-Postgres connection. Dates and timestamps arrive as ISO strings; they are parsed here and bound
 * as {@code java.time} objects, so the server never parses a string with its own date format and a wall-clock value keeps
 * its hour across the JVM's zone. Everything else binds as {@link SqlExecutor#bindValue} does with no column type.
 */
final class DialectBinder {

    private DialectBinder() { }

    static void bind(PreparedStatement ps, int idx, Object v, String bindType) throws SQLException {
        if (v instanceof CharSequence && bindType != null && !bindType.isEmpty()) {
            String s = v.toString();
            try {
                switch (bindType) {
                    case "date":
                        ps.setObject(idx, LocalDate.parse(s));
                        return;
                    case "timestamp with time zone":
                        ps.setObject(idx, OffsetDateTime.parse(s));
                        return;
                    case "timestamp without time zone":
                        ps.setObject(idx, naive(s));
                        return;
                    case BindTypes.ORACLE_DATE:
                        ps.setObject(idx, oracleDate(s));
                        return;
                    default:
                        break;
                }
            } catch (DateTimeParseException e) {
                throw new SQLException("Invalid ISO " + bindType + " string: " + s + " (" + e.getMessage() + ")", e);
            }
        }
        SqlExecutor.bindValue(ps, idx, v, null);
    }

    /** A wall-clock ISO timestamp; one carrying an offset is normalised to UTC (a conversion, not a parse failure). */
    private static LocalDateTime naive(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException naive) {
            try {
                return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            } catch (DateTimeParseException zoned) {
                throw naive;
            }
        }
    }

    /** An Oracle DATE takes an ISO date (midnight) or an ISO timestamp. */
    private static LocalDateTime oracleDate(String s) {
        try {
            return LocalDate.parse(s).atStartOfDay();
        } catch (DateTimeParseException notADate) {
            return naive(s);
        }
    }
}
