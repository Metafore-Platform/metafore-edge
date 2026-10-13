package com.metafore.edge.db;

import com.metafore.edge.service.DataSourceRegistry;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Building a data source opens no socket, so none of this needs a server. */
class DataSourcesTest {

    @Test
    void urls() {
        assertEquals("jdbc:postgresql://h:5432/d", DataSources.url(DbType.POSTGRESQL, "h", "5432", "d"));
        assertEquals("jdbc:mariadb://h:3306/d", DataSources.url(DbType.MYSQL, "h", "", "d"));
        assertEquals("jdbc:sqlserver://h:1433;databaseName=d", DataSources.url(DbType.MSSQL, "h", null, "d"));
        assertEquals("jdbc:oracle:thin:@//h:1521/ORCL", DataSources.url(DbType.ORACLE, "h", "1521", "ORCL"));
    }

    @Test
    void everyNonPostgresTypeBuilds() throws Exception {
        for (DbType t : new DbType[] {DbType.MYSQL, DbType.MARIADB, DbType.MSSQL, DbType.ORACLE}) {
            assertNotNull(DataSources.create(t, "h", t.defaultPort(), "d", "u", "secret"), t.name());
        }
    }

    @Test
    void onlyMysqlAsksForTls() {
        assertTrue(DataSources.mariadbUrl(DbType.MYSQL, "h", 3306, "d").contains("sslMode=trust"));
        assertFalse(DataSources.mariadbUrl(DbType.MARIADB, "h", 3306, "d").contains("sslMode"));
    }

    @Test
    void registryKeysCarryTheTypeExceptForPostgres() {
        String legacy = DataSourceRegistry.connectionKey("h", "5432", "d", "u");
        assertEquals(legacy, DataSourceRegistry.connectionKey(DbType.POSTGRESQL, "h", "5432", "d", "u"));
        assertEquals(legacy, DataSourceRegistry.connectionKey(null, "h", "5432", "d", "u"));
        String mysql = DataSourceRegistry.connectionKey(DbType.MYSQL, "h", "3306", "d", "u");
        assertFalse(mysql.equals(DataSourceRegistry.connectionKey(DbType.MARIADB, "h", "3306", "d", "u")));
        assertTrue(mysql.startsWith("conn:mysql:"));
    }

    @Test
    void registryPoolsPerTypeAndReplacesOnTypeChange() {
        DataSourceRegistry registry = new DataSourceRegistry(new DefaultCamelContext());
        registry.register(DbType.MYSQL, "src", "h", "3306", "d", "u", "p");
        DataSource first = registry.get("src");
        registry.register(DbType.MYSQL, "src", "h", "3306", "d", "u", "p");
        assertSame(first, registry.get("src"), "same type and settings keeps the pool");
        registry.register(DbType.MSSQL, "src", "h", "3306", "d", "u", "p");
        assertNotSame(first, registry.get("src"), "another type replaces it");
    }
}
