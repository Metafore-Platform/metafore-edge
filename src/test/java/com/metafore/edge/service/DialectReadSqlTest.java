package com.metafore.edge.service;

import com.metafore.edge.db.DbType;
import com.metafore.edge.db.Dialect;
import com.metafore.edge.db.OracleNames;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import com.metafore.edge.service.SqlExecutor.WhereCond;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Golden read statements per dialect: the page cut-off, contains predicate and count alias are the dialect's. */
class DialectReadSqlTest {

    private static final ParsedTableName T = SqlExecutor.parseTableName("shop.items");
    private static final List<String> COLS = List.of("id", "name");
    private static final List<WhereCond> NONE = List.of();

    private static String sql(Dialect d, String pattern, List<String> cols, String fc, int n, boolean tomb, String ks,
                              boolean ksv, List<WhereCond> conds) {
        return DialectReadSql.build(d, pattern, T, cols, fc, n, tomb, ks, ksv, conds);
    }

    @Test
    void postgresIsBuildReadSqlExactly() {
        List<WhereCond> conds = List.of(new WhereCond("name", "contains", "an"), new WhereCond("qty", "between", List.of(1, 5)),
            new WhereCond("id", "not_in", List.of(1, 2)), new WhereCond("born", "is_null", null));
        for (String pattern : new String[] {"by_id", "by_ids", "list", "count"}) {
            for (boolean keyset : new boolean[] {false, true}) {
                if (keyset && !"list".equals(pattern)) continue;
                List<WhereCond> c = "list".equals(pattern) || "count".equals(pattern) ? conds : NONE;
                assertEquals(
                    SqlExecutor.buildReadSql(pattern, T, COLS, "grp", 3, true, keyset ? "id" : null, keyset, c),
                    sql(Dialect.POSTGRESQL, pattern, COLS, "grp", 3, true, keyset ? "id" : null, keyset, c),
                    pattern + " keyset=" + keyset);
            }
        }
    }

    @Test
    void mysqlPatterns() {
        Dialect d = Dialect.MYSQL;
        assertEquals("SELECT `id`, `name` FROM `shop`.`items` WHERE `id` = ?", sql(d, "by_id", COLS, "id", 0, false, null, false, NONE));
        assertEquals("SELECT * FROM `shop`.`items` WHERE `id` IN (?, ?, ?)", sql(d, "by_ids", List.of(), "id", 3, false, null, false, NONE));
        assertEquals("SELECT COUNT(*) AS `count` FROM `shop`.`items` WHERE `grp` = ? AND `tombstoned_at` IS NULL",
            sql(d, "count", COLS, "grp", 0, true, null, false, NONE));
        assertEquals("SELECT `name`, `id` FROM `shop`.`items` WHERE `id` > ? AND `grp` = ? ORDER BY `id` ASC LIMIT ?",
            sql(d, "list", List.of("name"), "grp", 0, false, "id", true, NONE));
        assertEquals("SELECT * FROM `shop`.`items` ORDER BY `id` ASC LIMIT ?", sql(d, "list", List.of(), null, 0, false, "id", false, NONE));
        assertEquals("SELECT * FROM `shop`.`items` WHERE LOWER(`name`) LIKE LOWER(?) AND `qty` BETWEEN ? AND ?",
            sql(d, "list", List.of(), null, 0, false, null, false,
                List.of(new WhereCond("name", "contains", "an"), new WhereCond("qty", "between", List.of(1, 5)))));
    }

    @Test
    void sqlServerPageAndCount() {
        Dialect d = Dialect.MSSQL;
        assertEquals("SELECT [id], [name] FROM [shop].[items] WHERE [id] > ? ORDER BY [id] ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY",
            sql(d, "list", COLS, null, 0, false, "id", true, NONE));
        assertEquals("SELECT COUNT(*) AS [count] FROM [shop].[items]", sql(d, "count", List.of(), null, 0, false, null, false, NONE));
        assertEquals("SELECT * FROM [shop].[items] WHERE LOWER([name]) LIKE LOWER(?)",
            sql(d, "list", List.of(), null, 0, false, null, false, List.of(new WhereCond("name", "contains", "x"))));
    }

    @Test
    void oracleUsesStoredNamesAndKeepsTheTableAsResolved() {
        Dialect d = OracleNames.bound(Map.of("id", "ID", "name", "NAME", "tombstoned_at", "TOMBSTONED_AT", "items", "ITEMS"));
        ParsedTableName t = SqlExecutor.parseTableName("SHOP.items");   // the resolved, stored spelling
        assertEquals("SELECT \"ID\", \"NAME\" FROM \"SHOP\".\"items\" WHERE \"ID\" > ? AND \"TOMBSTONED_AT\" IS NULL "
                + "ORDER BY \"ID\" ASC OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY",
            DialectReadSql.build(d, "list", t, COLS, null, 0, true, "id", true, NONE));
        assertEquals("SELECT COUNT(*) AS \"count\" FROM \"SHOP\".\"items\"",
            DialectReadSql.build(d, "count", t, List.of(), null, 0, false, null, false, NONE));
        assertEquals(DbType.ORACLE, d.type());
    }
}
