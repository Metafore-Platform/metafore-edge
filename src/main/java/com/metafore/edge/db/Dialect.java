package com.metafore.edge.db;

/**
 * What differs between databases when a statement is built: how an identifier is quoted, how a keyset page is cut off, the
 * contains predicate and the count projection. Everything else (the checks, the bind order, the messages) is shared, so every
 * database answers the same payload with the same reply. JDBC binds every parameter as {@code ?}, so unlike the Go agent there
 * is no placeholder to render.
 *
 * <p>Postgres output is exactly what {@code SqlExecutor} has always built; the other dialects follow the same statement
 * shapes as the Go agent's {@code dialect.go}.
 */
public interface Dialect {

    DbType type();

    /** One identifier, quoted, with any embedded closing quote doubled so a name cannot break out of its quotes. */
    String quote(String identifier);

    /** Follows {@code ORDER BY <keyset> ASC} and caps the rows at the bind parameter {@code placeholder} (a {@code ?}). */
    String pageLimit(String placeholder);

    /** The case-insensitive substring predicate ({@code where} op {@code contains}); {@code placeholder} is bound to {@code %value%}. */
    String contains(String column, String placeholder);

    /** The count pattern's select list. */
    String countProjection();

    // ── the dialects ──

    Dialect POSTGRESQL = new Dialect() {
        @Override public DbType type() { return DbType.POSTGRESQL; }
        @Override public String quote(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
        @Override public String pageLimit(String ph) { return " LIMIT " + ph; }
        @Override public String contains(String col, String ph) { return col + " ILIKE " + ph; }
        @Override public String countProjection() { return "COUNT(*) AS count"; }
    };

    /** MySQL and MariaDB: `backticks`; both sides lower-cased so a case-sensitive collation does not change the match. */
    Dialect MYSQL = new Dialect() {
        @Override public DbType type() { return DbType.MYSQL; }
        @Override public String quote(String s) { return "`" + s.replace("`", "``") + "`"; }
        @Override public String pageLimit(String ph) { return " LIMIT " + ph; }
        @Override public String contains(String col, String ph) { return "LOWER(" + col + ") LIKE LOWER(" + ph + ")"; }
        @Override public String countProjection() { return "COUNT(*) AS `count`"; }
    };

    /** SQL Server: [brackets]; a keyset page is cut with OFFSET 0 ROWS FETCH NEXT (2012+), which keeps the cap the last bind. */
    Dialect MSSQL = new Dialect() {
        @Override public DbType type() { return DbType.MSSQL; }
        @Override public String quote(String s) { return "[" + s.replace("]", "]]") + "]"; }
        @Override public String pageLimit(String ph) { return " OFFSET 0 ROWS FETCH NEXT " + ph + " ROWS ONLY"; }
        @Override public String contains(String col, String ph) { return "LOWER(" + col + ") LIKE LOWER(" + ph + ")"; }
        @Override public String countProjection() { return "COUNT(*) AS [count]"; }
    };

    /**
     * Oracle: "double quotes"; OFFSET 0 ROWS FETCH NEXT (12c+) as on SQL Server. Oracle keeps an unquoted name in upper case
     * and every name here is quoted, so a command's name must reach Oracle spelled as the catalog spells it (resolved in the
     * executor), never as sent.
     */
    Dialect ORACLE = new Dialect() {
        @Override public DbType type() { return DbType.ORACLE; }
        @Override public String quote(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
        @Override public String pageLimit(String ph) { return " OFFSET 0 ROWS FETCH NEXT " + ph + " ROWS ONLY"; }
        @Override public String contains(String col, String ph) { return "LOWER(" + col + ") LIKE LOWER(" + ph + ")"; }
        @Override public String countProjection() { return "COUNT(*) AS \"count\""; }
    };

    /** The dialect of a type (MySQL and MariaDB share one). */
    static Dialect of(DbType type) {
        return switch (type) {
            case POSTGRESQL -> POSTGRESQL;
            case MYSQL, MARIADB -> MYSQL;
            case MSSQL -> MSSQL;
            case ORACLE -> ORACLE;
        };
    }
}
