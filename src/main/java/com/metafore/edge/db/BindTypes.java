package com.metafore.edge.db;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps a catalog column type of a non-Postgres database onto the vocabulary the binder understands: {@code date},
 * {@code timestamp without time zone}, {@code timestamp with time zone}, {@code numeric}, {@code boolean}, {@link #ORACLE_DATE}
 * or "" (bind the value as it is). The Go agent's {@code bindType} per driver is the reference.
 */
public final class BindTypes {

    /** An Oracle DATE holds a date and a time of day: it takes an ISO date or an ISO timestamp. */
    public static final String ORACLE_DATE = "oracle date";

    private static final Pattern ORACLE_TYPE_ARGS = Pattern.compile("\\(\\d+(,\\s*\\d+)?( (BYTE|CHAR))?\\)");

    private BindTypes() { }

    public static String of(DbType type, String dataType) {
        if (dataType == null) {
            return "";
        }
        String t = dataType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case MYSQL, MARIADB -> switch (t) {
                case "date" -> "date";
                case "datetime", "timestamp" -> "timestamp without time zone";
                case "decimal" -> "numeric";
                default -> "";
            };
            case MSSQL -> switch (t) {
                case "date" -> "date";
                case "datetime", "datetime2", "smalldatetime" -> "timestamp without time zone";
                case "datetimeoffset" -> "timestamp with time zone";
                case "decimal", "numeric", "money", "smallmoney" -> "numeric";
                default -> "";
            };
            case ORACLE -> oracle(dataType);
            case POSTGRESQL -> t;
        };
    }

    private static String oracle(String dataType) {
        String t = ORACLE_TYPE_ARGS.matcher(dataType.toUpperCase(Locale.ROOT)).replaceAll("").trim();
        if (t.equals("DATE")) {
            return ORACLE_DATE;
        }
        if (t.equals("TIMESTAMP")) {
            return "timestamp without time zone";
        }
        if (t.startsWith("TIMESTAMP") && t.endsWith("TIME ZONE")) {
            return "timestamp with time zone";
        }
        if (t.equals("NUMBER") || t.equals("FLOAT")) {
            return "numeric";
        }
        return t.equals("BOOLEAN") ? "boolean" : "";
    }
}
