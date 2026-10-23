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
                + "created DATETIME(6), born DATE, flag TINYINT(1), grp INT, tombstoned_at DATETIME(6))";
            case MSSQL -> "CREATE TABLE it_items (id INT PRIMARY KEY, name NVARCHAR(100), qty DECIMAL(10,2), "
                + "created DATETIME2, born DATE, flag BIT, grp INT, tombstoned_at DATETIME2)";
            default -> "CREATE TABLE it_items (id NUMBER(10) PRIMARY KEY, name VARCHAR2(100), qty NUMBER(10,2), "
                + "created TIMESTAMP, born DATE, flag NUMBER(1), grp NUMBER(10), tombstoned_at TIMESTAMP)";
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

    private static Map<String, Object> read(Map<String, Object> payload) {
        Map<String, Object> p = new java.util.LinkedHashMap<>(payload);
        p.put("operation", "read");
        p.put("table_name", "it_items");
        return DialectExecutor.executeRead(type, ds, p);
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> result) {
        assertEquals("success", result.get("status"), String.valueOf(result));
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) result.get("data")) {
            out.add(String.valueOf(row.get("name")));
        }
        return out;
    }

    @Test
    void readPatterns() {
        String[] names = {"alpha", "beta", "gamma", "delta", "epsilon"};
        for (int i = 0; i < names.length; i++) {
            // id 13 is tombstoned
            assertEquals("success", run("create", List.of("id", "name", "grp", "tombstoned_at"),
                vals(10 + i, names[i], 7, i == 3 ? "2026-01-01T00:00:00" : null), null, null).get("status"));
        }
        assertEquals(List.of("alpha"), names(read(Map.of("pattern", "by_id", "columns", List.of("name"),
            "filter_column", "id", "filter_value", 10))));
        assertEquals(2, names(read(Map.of("pattern", "by_ids", "columns", List.of("name"), "filter_column", "id",
            "filter_values", List.of(10, 11, 99)))).size());
        assertEquals(5, names(read(Map.of("pattern", "list", "columns", List.of("name"), "filter_column", "grp",
            "filter_value", 7))).size());
        assertEquals(4, names(read(Map.of("pattern", "list", "columns", List.of("name"), "filter_column", "grp",
            "filter_value", 7, "exclude_tombstoned", true))).size());
        assertEquals("5", read(Map.of("pattern", "count", "filter_column", "grp", "filter_value", 7)).get("data").toString()
            .replaceAll("[^0-9]", ""));
        Map<String, Object> grp7 = Map.of("pattern", "list", "columns", List.of("name"), "filter_column", "grp", "filter_value", 7);
        assertEquals(List.of("beta"), names(read(with(grp7, "where_conditions",
            List.of(Map.of("field", "name", "op", "contains", "value", "ET"))))), "contains is case-insensitive");
        assertEquals(3, names(read(with(grp7, "where_conditions",
            List.of(Map.of("field", "id", "op", "between", "value", List.of(11, 13)))))).size());
        assertEquals(2, names(read(with(grp7, "where_conditions",
            List.of(Map.of("field", "name", "op", "in", "value", List.of("alpha", "delta")))))).size());
        assertEquals(1, names(read(with(grp7, "where_conditions",
            List.of(Map.of("field", "tombstoned_at", "op", "is_not_null"))))).size());
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        m.put(key, value);
        return m;
    }

    @SuppressWarnings("unchecked")
    @Test
    void keysetPagesForwardWithoutGapsOrRepeats() {
        for (int i = 0; i < 5; i++) {
            run("create", List.of("id", "name", "grp"), vals(20 + i, "k" + i, 8), null, null);
        }
        Map<String, Object> base = Map.of("pattern", "list", "columns", List.of("name"), "filter_column", "grp",
            "filter_value", 8, "keyset_field", "id", "limit", 2);
        List<String> seen = new ArrayList<>();
        Object cursor = null;
        for (int page = 0; page < 3; page++) {
            Map<String, Object> req = cursor == null ? base : with(base, "keyset_value", cursor);
            Map<String, Object> r = read(req);
            seen.addAll(names(r));
            boolean more = (Boolean) r.get("has_more");
            assertEquals(page < 2, more, "page " + page);
            cursor = r.get("last_keyset_value");
            if (!more) {
                assertNull(cursor);
            }
        }
        assertEquals(List.of("k0", "k1", "k2", "k3", "k4"), seen);
    }

    @Test
    void aReadRefusalIsTheSameMessageAsPostgres() {
        assertEquals("Unsupported read pattern: nope (expected by_id/by_ids/list/count)",
            read(Map.of("pattern", "nope")).get("error"));
        assertEquals("Column zzz does not exist on table it_items",
            read(Map.of("pattern", "list", "columns", List.of("zzz"))).get("error"));
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
