package com.metafore.edge.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The SQL fragments each dialect writes (the Go agent's dialect.go is the reference). */
class DialectTest {

    @Test
    void postgres() {
        Dialect d = Dialect.of(DbType.POSTGRESQL);
        assertEquals("\"name\"", d.quote("name"));
        assertEquals(" LIMIT ?", d.pageLimit("?"));
        assertEquals("col ILIKE ?", d.contains("col", "?"));
        assertEquals("COUNT(*) AS count", d.countProjection());
    }

    @Test
    void mysqlAndMariaDbShareBackticks() {
        for (DbType t : new DbType[] {DbType.MYSQL, DbType.MARIADB}) {
            Dialect d = Dialect.of(t);
            assertEquals("`name`", d.quote("name"));
            assertEquals(" LIMIT ?", d.pageLimit("?"));
            assertEquals("LOWER(col) LIKE LOWER(?)", d.contains("col", "?"));
            assertEquals("COUNT(*) AS `count`", d.countProjection());
        }
    }

    @Test
    void sqlServer() {
        Dialect d = Dialect.of(DbType.MSSQL);
        assertEquals("[name]", d.quote("name"));
        assertEquals(" OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY", d.pageLimit("?"));
        assertEquals("COUNT(*) AS [count]", d.countProjection());
    }

    @Test
    void oracle() {
        Dialect d = Dialect.of(DbType.ORACLE);
        assertEquals("\"name\"", d.quote("name"));
        assertEquals(" OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY", d.pageLimit("?"));
        assertEquals("COUNT(*) AS \"count\"", d.countProjection());
    }
}
