package com.metafore.edge.service;

import com.metafore.edge.db.BindTypes;
import com.metafore.edge.db.DbType;
import com.metafore.edge.db.Dialect;
import com.metafore.edge.db.OracleNames;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Golden statements per dialect: identifiers quoted, every value a bind parameter. */
class DialectStatementsTest {

    private static final List<String> COLS = List.of("name", "qty");

    private static ParsedTableName bare() { return SqlExecutor.parseTableName("items"); }

    private static ParsedTableName qualified() { return SqlExecutor.parseTableName("shop.items"); }

    @Test
    void postgresIsWhatSqlExecutorAlwaysBuilt() {
        for (String op : new String[] {"create", "update", "delete", "select"}) {
            for (ParsedTableName t : new ParsedTableName[] {bare(), qualified()}) {
                assertEquals(SqlExecutor.buildSql(op, t, COLS, "id"),
                    DialectStatements.build(Dialect.POSTGRESQL, op, t, COLS, "id"), op + " " + t.display());
            }
        }
    }

    @Test
    void mysql() {
        Dialect d = Dialect.of(DbType.MARIADB);
        assertEquals("INSERT INTO `items` (`name`, `qty`) VALUES (?, ?)", DialectStatements.build(d, "create", bare(), COLS, null));
        assertEquals("UPDATE `shop`.`items` SET `name` = ?, `qty` = ? WHERE `id` = ?",
            DialectStatements.build(d, "update", qualified(), COLS, "id"));
        assertEquals("UPDATE `items` SET `name` = ?, `qty` = ? WHERE `id` = ?", DialectStatements.build(d, "delete", bare(), COLS, "id"));
        assertEquals("SELECT `name`, `qty` FROM `items` WHERE `id` = ?", DialectStatements.build(d, "select", bare(), COLS, "id"));
        assertEquals("SELECT * FROM `items` WHERE `id` = ?", DialectStatements.build(d, "select", bare(), List.of(), "id"));
    }

    @Test
    void sqlServer() {
        Dialect d = Dialect.MSSQL;
        assertEquals("INSERT INTO [items] ([name], [qty]) VALUES (?, ?)", DialectStatements.build(d, "create", bare(), COLS, null));
        assertEquals("UPDATE [shop].[items] SET [name] = ?, [qty] = ? WHERE [id] = ?",
            DialectStatements.build(d, "update", qualified(), COLS, "id"));
        assertEquals("SELECT [name], [qty] FROM [shop].[items] WHERE [id] = ?",
            DialectStatements.build(d, "select", qualified(), COLS, "id"));
    }

    @Test
    void oracleQuotesTheStoredSpelling() {
        Dialect d = OracleNames.bound(Map.of("name", "NAME", "qty", "QTY", "id", "ID"));
        ParsedTableName resolved = SqlExecutor.parseTableName("SHOP.ITEMS");
        assertEquals("INSERT INTO \"SHOP\".\"ITEMS\" (\"NAME\", \"QTY\") VALUES (?, ?)",
            DialectStatements.build(d, "create", resolved, COLS, null));
        assertEquals("UPDATE \"SHOP\".\"ITEMS\" SET \"NAME\" = ?, \"QTY\" = ? WHERE \"ID\" = ?",
            DialectStatements.build(d, "update", resolved, COLS, "id"));
        // a name that is stored as sent stays as it is
        assertEquals("SELECT \"MixedCase\" FROM \"SHOP\".\"ITEMS\" WHERE \"ID\" = ?",
            DialectStatements.build(d, "select", resolved, List.of("MixedCase"), "id"));
    }

    @Test
    void anEmbeddedQuoteCannotBreakOut() {
        assertEquals("`a``b`", Dialect.MYSQL.quote("a`b"));
        assertEquals("[a]]b]", Dialect.MSSQL.quote("a]b"));
        assertEquals("\"a\"\"b\"", Dialect.ORACLE.quote("a\"b"));
    }

    @Test
    void oracleNameResolution() {
        assertEquals("patients", OracleNames.shown("PATIENTS"));
        assertEquals("MixedCase", OracleNames.shown("MixedCase"));
        assertEquals("WITH SPACE", OracleNames.shown("WITH SPACE"));
        assertEquals(List.of("patients", "PATIENTS"), OracleNames.candidates("patients"));
        assertEquals(List.of("PATIENTS"), OracleNames.candidates("PATIENTS"));
        assertEquals(List.of("with space"), OracleNames.candidates("with space"));
    }

    @Test
    void bindTypes() {
        assertEquals("date", BindTypes.of(DbType.MYSQL, "DATE"));
        assertEquals("timestamp without time zone", BindTypes.of(DbType.MARIADB, "datetime"));
        assertEquals("numeric", BindTypes.of(DbType.MYSQL, "decimal"));
        assertEquals("", BindTypes.of(DbType.MYSQL, "varchar"));
        assertEquals("timestamp with time zone", BindTypes.of(DbType.MSSQL, "datetimeoffset"));
        assertEquals("numeric", BindTypes.of(DbType.MSSQL, "money"));
        assertEquals(BindTypes.ORACLE_DATE, BindTypes.of(DbType.ORACLE, "DATE"));
        assertEquals("timestamp without time zone", BindTypes.of(DbType.ORACLE, "TIMESTAMP(6)"));
        assertEquals("timestamp with time zone", BindTypes.of(DbType.ORACLE, "TIMESTAMP(6) WITH TIME ZONE"));
        assertEquals("numeric", BindTypes.of(DbType.ORACLE, "NUMBER"));
        assertEquals("boolean", BindTypes.of(DbType.ORACLE, "BOOLEAN"));
    }
}
