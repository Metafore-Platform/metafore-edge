package com.metafore.edge.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTypeTest {

    @Test
    void blankAndPostgresNamesAreTheDefault() {
        assertEquals(DbType.POSTGRESQL, DbType.of(null));
        assertEquals(DbType.POSTGRESQL, DbType.of(""));
        assertEquals(DbType.POSTGRESQL, DbType.of(" Postgres "));
        assertEquals(DbType.POSTGRESQL, DbType.of("PG"));
        assertEquals(DbType.POSTGRESQL, DbType.of("postgresql"));
    }

    @Test
    void otherSourcesAndTheirSpellings() {
        assertEquals(DbType.MYSQL, DbType.of("MySQL"));
        assertEquals(DbType.MARIADB, DbType.of("mariadb"));
        assertEquals(DbType.MSSQL, DbType.of("sqlserver"));
        assertEquals(DbType.MSSQL, DbType.of("SQL_Server"));
        assertEquals(DbType.MSSQL, DbType.of("mssql"));
        assertEquals(DbType.ORACLE, DbType.of("ORACLE"));
    }

    @Test
    void defaultPorts() {
        assertEquals("5432", DbType.POSTGRESQL.defaultPort());
        assertEquals("3306", DbType.MYSQL.defaultPort());
        assertEquals("3306", DbType.MARIADB.defaultPort());
        assertEquals("1433", DbType.MSSQL.defaultPort());
        assertEquals("1521", DbType.ORACLE.defaultPort());
        assertTrue(DbType.MARIADB.isMySqlFamily());
        assertFalse(DbType.ORACLE.isMySqlFamily());
    }

    @Test
    void unknownTypeIsRefusedByName() {
        var e = assertThrows(DbType.UnsupportedDbTypeException.class, () -> DbType.of("sqlite"));
        assertEquals("Unsupported db_type: sqlite (expected postgresql, mysql, mariadb, mssql or oracle)", e.getMessage());
    }
}
