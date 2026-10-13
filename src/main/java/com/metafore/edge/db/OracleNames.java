package com.metafore.edge.db;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How the platform's names meet Oracle's. Oracle stores a name created without quotes in upper case and one created with
 * quotes exactly as written; the platform names tables and columns in lower case and every identifier is quoted here. So a
 * stored name that is all upper case and a legal unquoted identifier is <b>shown</b> in lower case ({@code PATIENTS} is
 * {@code patients}), any other stored name as stored; and a command's name is <b>resolved</b> to the stored name spelled
 * exactly like it when there is one, otherwise to its upper-case form when that is a legal unquoted identifier and is stored.
 * Shown and resolved are inverse. The Go agent's {@code oracle_names.go} is the reference.
 */
public final class OracleNames {

    private static final Pattern UNQUOTED = Pattern.compile("^[A-Z][A-Z0-9_$#]*$");

    private OracleNames() { }

    /** The name the platform sees for a stored name. */
    public static String shown(String stored) {
        return UNQUOTED.matcher(stored).matches() ? stored.toLowerCase(java.util.Locale.ROOT) : stored;
    }

    /** The stored spellings a command's name may mean, best first. */
    public static List<String> candidates(String name) {
        String up = name.toUpperCase(java.util.Locale.ROOT);
        return !up.equals(name) && UNQUOTED.matcher(up).matches() ? List.of(name, up) : List.of(name);
    }

    /** The Oracle dialect for one table's statements: a column name is quoted in the stored spelling it resolves to. */
    public static Dialect bound(Map<String, String> stored) {
        return new Dialect() {
            @Override public DbType type() { return DbType.ORACLE; }
            @Override public String quote(String s) { return ORACLE.quote(stored.getOrDefault(s, s)); }
            @Override public String pageLimit(String ph) { return ORACLE.pageLimit(ph); }
            @Override public String contains(String col, String ph) { return ORACLE.contains(col, ph); }
            @Override public String countProjection() { return ORACLE.countProjection(); }
        };
    }
}
