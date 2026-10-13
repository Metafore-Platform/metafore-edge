package com.metafore.edge.service;

import com.metafore.edge.db.DataSources;
import com.metafore.edge.db.DbType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The parametric operations against a real MySQL, MariaDB, SQL Server or Oracle. Runs only when {@code IT_DB_TYPE} names one
 * (and {@code IT_DB_HOST}, {@code IT_DB_PORT}, {@code IT_DB_NAME}, {@code IT_DB_USER}, {@code IT_DB_PASS}); CI starts the
 * server (development.yml, job {@code dialects}). The table is made unquoted, so on Oracle it is stored in upper case and
 * the lower-case names below go through the name resolution.
 */
class DialectParametricIT {

    private static DbType type;
    private static DataSource ds;

    @BeforeAll
    static void connect() throws Exception {
        String t = System.getenv("IT_DB_TYPE");
        assumeTrue(t != null && !t.isBlank(), "IT_DB_TYPE is not set");
        type = DbType.of(t);
        ds = DataSources.create(type, System.getenv("IT_DB_HOST"), System.getenv("IT_DB_PORT"), System.getenv("IT_DB_NAME"),
            System.getenv("IT_DB_USER"), System.getenv("IT_DB_PASS"));
        Exception last = null;
        for (int i = 0; i < 30; i++) {   // the server may still be starting
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                try { st.execute("DROP TABLE it_items"); } catch (Exception absent) { /* first run */ }
                st.execute(ddl());
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        throw last;
    }

    @AfterAll
    static void drop() throws Exception {
        if (ds != null) {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE it_items");
            }
        }
    }

    private static String ddl() {
        return switch (type) {
            case MYSQL, MARIADB -> "CREATE TABLE it_items (id INT PRIMARY KEY, name VARCHAR(100), qty DECIMAL(10,2), "
                + "created DATETIME(6), born DATE, flag TINYINT(1))";
            case MSSQL -> "CREATE TABLE it_items (id INT PRIMARY KEY, name NVARCHAR(100), qty DECIMAL(10,2), "
                + "created DATETIME2, born DATE, flag BIT)";
            default -> "CREATE TABLE it_items (id NUMBER(10) PRIMARY KEY, name VARCHAR2(100), qty NUMBER(10,2), "
                + "created TIMESTAMP, born DATE, flag NUMBER(1))";
        };
    }

    private static Map<String, Object> run(String op, List<String> cols, List<Object> vals, String whereCol, Object whereVal) {
        return DialectExecutor.executeParametric(type, ds, op, "it_items", cols, vals, whereCol, whereVal);
    }

    private static List<Object> vals(Object... v) { return new ArrayList<>(Arrays.asList(v)); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> only(Map<String, Object> result) {
        assertEquals("success", result.get("status"), String.valueOf(result));
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertEquals(1, data.size());
        return data.get(0);
    }

    @Test
    void createSelectUpdateDelete() {
        List<String> cols = List.of("id", "name", "qty", "created", "born");
        Map<String, Object> created = run("create", cols, vals(1, "Ann", "12.50", "2026-01-02T03:04:05", "2026-01-02"), null, null);
        assertEquals("success", created.get("status"), String.valueOf(created));
        assertEquals(1, created.get("rows_affected"));

        Map<String, Object> row = only(run("select", List.of("id", "name", "created", "born"), null, "id", 1));
        assertEquals("Ann", row.get("name"));
        assertTrue(row.get("created").toString().startsWith("2026-01-02 03:04:05"), String.valueOf(row));
        assertTrue(row.get("born").toString().startsWith("2026-01-02"), String.valueOf(row));
        assertTrue(only(run("select", List.of(), null, "id", 1)).containsKey("name"), "SELECT * keeps the platform's names");

        assertEquals(1, run("update", List.of("name"), vals("Bea"), "id", 1).get("rows_affected"));
        // an update that changes nothing still matched the row: never "record not found"
        assertEquals(1, run("update", List.of("name"), vals("Bea"), "id", 1).get("rows_affected"));
        assertEquals("Bea", only(run("select", List.of("name"), null, "id", 1)).get("name"));

        assertEquals(1, run("delete", List.of("name"), vals((Object) null), "id", 1).get("rows_affected"));
        assertNull(only(run("select", List.of("name"), null, "id", 1)).get("name"));
        assertEquals(0, run("select", List.of("name"), null, "id", 99).get("row_count"));
    }

    @Test
    void aValueIsBoundNeverInterpolated() {
        String evil = "x'); DROP TABLE it_items; --";
        assertEquals("success", run("create", List.of("id", "name"), vals(2, evil), null, null).get("status"));
        assertEquals(evil, only(run("select", List.of("name"), null, "id", 2)).get("name"));
    }

    @Test
    void refusalsAreTheSameMessagesAsPostgres() {
        assertEquals("Table it_items2 does not exist", DialectExecutor.executeParametric(type, ds, "select", "it_items2",
            List.of(), null, "id", 1).get("error"));
        assertEquals("Column zzz does not exist on table it_items",
            run("select", List.of("zzz"), null, "id", 1).get("error"));
        assertEquals("Column zzz does not exist on table it_items",
            run("select", List.of("name"), null, "zzz", 1).get("error"));
        String unsupported = String.valueOf(run("create", List.of("id", "name"), vals(3, Map.of("a", 1)), null, null).get("error"));
        assertTrue(unsupported.startsWith("Unsupported column type for values[1]"), unsupported);
        assertEquals("update requires where_column", run("update", List.of("name"), vals("x"), null, null).get("error"));
    }
}
