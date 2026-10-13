package com.metafore.edge.db;

import java.util.Locale;

/**
 * The database a command's {@code db_*} parameters name ({@code db_type} in route_command.schema.json). Absent or blank is
 * Postgres: the only database this edge spoke before v2, and still what every existing command means. The spellings and the
 * default ports are the Go agent's ({@code config.NormalizeDBType}, {@code DefaultPort}), so both edges accept the same
 * commands.
 */
public enum DbType {
    POSTGRESQL("postgresql", "5432"),
    MYSQL("mysql", "3306"),
    MARIADB("mariadb", "3306"),
    MSSQL("mssql", "1433"),
    ORACLE("oracle", "1521");

    private final String wireName;
    private final String defaultPort;

    DbType(String wireName, String defaultPort) {
        this.wireName = wireName;
        this.defaultPort = defaultPort;
    }

    /** The name registration advertises ({@code registration.schema.json}'s db_type enum). */
    public String wireName() { return wireName; }

    /** The port a database of this type listens on by default. */
    public String defaultPort() { return defaultPort; }

    /** MySQL and MariaDB are one dialect and one driver. */
    public boolean isMySqlFamily() { return this == MYSQL || this == MARIADB; }

    /**
     * The type a {@code db_type} value names: blank, {@code postgresql}, {@code postgres} and {@code pg} are Postgres;
     * {@code mssql}, {@code sqlserver} and {@code sql_server} are SQL Server; {@code mysql}, {@code mariadb} and
     * {@code oracle} are themselves. Case and surrounding spaces do not matter.
     *
     * @throws UnsupportedDbTypeException for anything else, with the Go agent's wording
     */
    public static DbType of(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (n) {
            case "", "postgresql", "postgres", "pg" -> POSTGRESQL;
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "mssql", "sqlserver", "sql_server" -> MSSQL;
            case "oracle" -> ORACLE;
            default -> throw new UnsupportedDbTypeException(n);
        };
    }

    /** A command named a database this edge cannot run. */
    public static final class UnsupportedDbTypeException extends IllegalArgumentException {
        public UnsupportedDbTypeException(String normalized) {
            super("Unsupported db_type: " + normalized + " (expected postgresql, mysql, mariadb, mssql or oracle)");
        }
    }
}
