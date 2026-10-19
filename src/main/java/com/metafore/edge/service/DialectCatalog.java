package com.metafore.edge.service;

import com.metafore.edge.db.BindTypes;
import com.metafore.edge.db.DbType;
import com.metafore.edge.db.Dialect;
import com.metafore.edge.db.OracleNames;
import com.metafore.edge.service.SqlExecutor.ParsedTableName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * What the executor learns of one table before it builds a statement on a non-Postgres database: whether it exists, its
 * columns' bind types, the table to render, the dialect its statements are built in and the key a result column is replied
 * under. MySQL, MariaDB and SQL Server answer from INFORMATION_SCHEMA; Oracle has none and is looked up in ALL_TABLES,
 * ALL_VIEWS and ALL_TAB_COLUMNS (names resolved by {@link OracleNames}). The Go agent's {@code catalog} / {@code lookup}.
 */
final class DialectCatalog {

    final boolean exists;
    /** Column name (every name a command may use) to bind type. */
    final Map<String, String> types;
    final ParsedTableName table;
    final Dialect dialect;
    /** The reply key of a result column. */
    final UnaryOperator<String> columnKey;

    private DialectCatalog(boolean exists, Map<String, String> types, ParsedTableName table, Dialect dialect,
                           UnaryOperator<String> columnKey) {
        this.exists = exists;
        this.types = types;
        this.table = table;
        this.dialect = dialect;
        this.columnKey = columnKey;
    }

    static DialectCatalog lookup(Connection conn, DbType type, ParsedTableName parsed) throws SQLException {
        return type == DbType.ORACLE ? oracle(conn, parsed) : informationSchema(conn, type, parsed);
    }

    private static DialectCatalog informationSchema(Connection conn, DbType type, ParsedTableName parsed)
        throws SQLException {
        Dialect dialect = Dialect.of(type);
        String currentSchema = type == DbType.MSSQL ? "SCHEMA_NAME()" : "DATABASE()";
        String where = parsed.schemaWasExplicit
            ? " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
            : " WHERE TABLE_SCHEMA = " + currentSchema + " AND TABLE_NAME = ?";
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES" + where)) {
            bindName(ps, parsed);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    return new DialectCatalog(false, Map.of(), parsed, dialect, UnaryOperator.identity());
                }
            }
        }
        Map<String, String> types = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS" + where)) {
            bindName(ps, parsed);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1), BindTypes.of(type, rs.getString(2)));
                }
            }
        }
        return new DialectCatalog(true, types, parsed, dialect, UnaryOperator.identity());
    }

    private static void bindName(PreparedStatement ps, ParsedTableName parsed) throws SQLException {
        int i = 1;
        if (parsed.schemaWasExplicit) {
            ps.setString(i++, parsed.schema);
        }
        ps.setString(i, parsed.table);
    }

    // ── Oracle ──

    private static DialectCatalog oracle(Connection conn, ParsedTableName parsed) throws SQLException {
        String[] ownerAndName = resolveOracleTable(conn, parsed);
        if (ownerAndName == null) {
            return new DialectCatalog(false, Map.of(), parsed, Dialect.ORACLE, OracleNames::shown);
        }
        ParsedTableName resolved = new ParsedTableName(ownerAndName[0], ownerAndName[1]);
        resolved.schemaWasExplicit = true;
        List<String[]> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ?")) {
            ps.setString(1, ownerAndName[0]);
            ps.setString(2, ownerAndName[1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        // each column is known by its stored name and, when that differs and is not itself stored, by its shown name
        Map<String, String> types = new HashMap<>();
        Map<String, String> stored = new HashMap<>();
        for (String[] c : cols) {
            types.put(c[0], BindTypes.of(DbType.ORACLE, c[1]));
            stored.put(c[0], c[0]);
        }
        for (String[] c : cols) {
            String shown = OracleNames.shown(c[0]);
            if (!shown.equals(c[0]) && !stored.containsKey(shown)) {
                types.put(shown, types.get(c[0]));
                stored.put(shown, c[0]);
            }
        }
        return new DialectCatalog(true, types, resolved, OracleNames.bound(stored), OracleNames::shown);
    }

    /** The stored {owner, name} a table names (unqualified: in the session's current schema), or null. */
    private static String[] resolveOracleTable(Connection conn, ParsedTableName parsed) throws SQLException {
        List<String> names = OracleNames.candidates(parsed.table);
        List<String> owners;
        if (parsed.schemaWasExplicit) {
            owners = OracleNames.candidates(parsed.schema);
        } else {
            try (PreparedStatement ps = conn.prepareStatement("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL");
                 ResultSet rs = ps.executeQuery()) {
                owners = List.of(rs.next() ? rs.getString(1) : "");
            }
        }
        // every candidate pair in one round trip; the best one is picked here
        String inO = inList(owners.size());
        String inN = inList(names.size());
        String sql = "SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE OWNER IN (" + inO + ") AND TABLE_NAME IN (" + inN + ") "
            + "UNION ALL SELECT OWNER, VIEW_NAME FROM ALL_VIEWS WHERE OWNER IN (" + inO + ") AND VIEW_NAME IN (" + inN + ")";
        Set<String> found = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (int pass = 0; pass < 2; pass++) {
                for (String o : owners) ps.setString(i++, o);
                for (String n : names) ps.setString(i++, n);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString(1) + "\u0000" + rs.getString(2));
                }
            }
        }
        for (String o : owners) {
            for (String n : names) {
                if (found.contains(o + "\u0000" + n)) {
                    return new String[] {o, n};
                }
            }
        }
        return null;
    }

    private static String inList(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
