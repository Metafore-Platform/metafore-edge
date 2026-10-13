package com.metafore.edge.db;

import oracle.jdbc.pool.OracleDataSource;
import org.mariadb.jdbc.MariaDbDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The JDBC {@link DataSource} for a source of a given {@link DbType}. Nothing connects here: a data source is a description of
 * where to connect, and the password is set on it and never put in a URL (a URL ends up in logs).
 *
 * <p>Every source gets bounded waits, as the Postgres ones always have (see {@code DataSourceRegistry.STATEMENT_TIMEOUT_MS}): a
 * login that cannot complete and a write blocked on a lock must fail with a typed {@link SQLException} the executor turns into a
 * published error, never hang the route. Postgres sets them on the server (statement and lock timeouts); the others use the
 * equivalents the Go agent sets: MySQL/MariaDB a UTC session and {@code innodb_lock_wait_timeout}, SQL Server
 * {@code LOCK_TIMEOUT} and a query timeout, Oracle a read timeout (it has no row-lock wait setting for DML: a blocked write ends
 * at the socket read timeout).
 */
public final class DataSources {

    private static final int LOGIN_TIMEOUT_SECONDS = 15;
    private static final int LOCK_TIMEOUT_MS = 15_000;
    private static final int SOCKET_TIMEOUT_MS = 45_000;
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private DataSources() { }

    /** A non-Postgres data source. (Postgres keeps its own construction in {@code DataSourceRegistry}.) */
    public static DataSource create(DbType type, String host, String port, String dbName, String user, String password)
        throws SQLException {
        int portNumber = Integer.parseInt(port == null || port.isBlank() ? type.defaultPort() : port.trim());
        return switch (type) {
            case MYSQL, MARIADB -> mariadb(host, portNumber, dbName, user, password);
            case MSSQL -> mssql(host, portNumber, dbName, user, password);
            case ORACLE -> oracle(host, portNumber, dbName, user, password);
            case POSTGRESQL -> throw new IllegalArgumentException("Postgres data sources are built by DataSourceRegistry");
        };
    }

    /** The JDBC URL a source of this type connects to, without credentials (for logs and tests). */
    public static String url(DbType type, String host, String port, String dbName) {
        String p = port == null || port.isBlank() ? type.defaultPort() : port.trim();
        String db = dbName == null ? "" : dbName;
        return switch (type) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + p + (db.isEmpty() ? "" : "/" + db);
            case MYSQL, MARIADB -> "jdbc:mariadb://" + host + ":" + p + (db.isEmpty() ? "" : "/" + db);
            case MSSQL -> "jdbc:sqlserver://" + host + ":" + p + (db.isEmpty() ? "" : ";databaseName=" + db);
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + p + "/" + db;
        };
    }

    private static DataSource mariadb(String host, int port, String dbName, String user, String password) throws SQLException {
        MariaDbDataSource ds = new MariaDbDataSource();
        // MySQL servers are served by the same driver. A UTC session and a bounded InnoDB lock wait, as the other pools have.
        ds.setUrl(url(DbType.MARIADB, host, Integer.toString(port), dbName)
            + "?connectTimeout=" + LOGIN_TIMEOUT_SECONDS * 1000 + "&socketTimeout=" + SOCKET_TIMEOUT_MS
            + "&sessionVariables=innodb_lock_wait_timeout=" + LOCK_TIMEOUT_MS / 1000);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static DataSource mssql(String host, int port, String dbName, String user, String password) {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setServerName(host);
        ds.setPortNumber(port);
        if (dbName != null && !dbName.isEmpty()) {
            ds.setDatabaseName(dbName);
        }
        ds.setUser(user);
        ds.setPassword(password);
        // the login is encrypted, the server certificate is not verified (encrypt=false in mssql-jdbc terms), as the Go agent
        ds.setEncrypt("false");
        ds.setTrustServerCertificate(true);
        ds.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        ds.setLockTimeout(LOCK_TIMEOUT_MS);
        ds.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return ds;
    }

    private static DataSource oracle(String host, int port, String dbName, String user, String password) throws SQLException {
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalArgumentException("db_name (the Oracle service name) is required");
        }
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(url(DbType.ORACLE, host, Integer.toString(port), dbName));   // db_name is the service name (FREEPDB1), not a SID
        ds.setUser(user);
        ds.setPassword(password);
        ds.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        Properties props = new Properties();
        props.setProperty("oracle.jdbc.ReadTimeout", Integer.toString(SOCKET_TIMEOUT_MS));
        props.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(LOGIN_TIMEOUT_SECONDS * 1000));
        ds.setConnectionProperties(props);
        return ds;
    }
}
