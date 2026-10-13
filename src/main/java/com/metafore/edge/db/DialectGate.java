package com.metafore.edge.db;

import java.util.Map;
import java.util.Set;

/**
 * Interim guard (part 2, between PR 1 and PR 2): operations still write Postgres SQL, so a source of any other
 * {@link DbType} is refused before it reaches {@code SqlExecutor}. Each later PR lets one operation through for the
 * types whose dialect it supports, until this class is empty and removed.
 */
public final class DialectGate {

    /** The {@code error_details} tag of a refusal. */
    public static final String REFUSAL = "dialect_not_supported";

    /** The operations whose statements are built in the source's dialect (PR 2); every other one is Postgres-only for now. */
    private static final Set<String> DIALECT_OPERATIONS = Set.of("create", "update", "delete", "select");

    private DialectGate() { }

    /** The refusal message when {@code params} name a non-Postgres source for {@code operation}, otherwise null. */
    public static String refusal(Map<String, Object> params, String operation) {
        if (params == null) {
            return null;
        }
        Object raw = params.get("db_type") != null ? params.get("db_type") : params.get("DB_TYPE");
        DbType type;
        try {
            type = DbType.of(raw == null ? "" : raw.toString());
        } catch (DbType.UnsupportedDbTypeException e) {
            return e.getMessage();
        }
        return type == DbType.POSTGRESQL || DIALECT_OPERATIONS.contains(operation) ? null
            : "dialect not yet supported for " + (operation == null ? "this operation" : operation)
                + " (db_type " + type.wireName() + ")";
    }
}
