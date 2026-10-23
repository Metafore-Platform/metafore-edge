package com.metafore.edge.service;

import com.metafore.edge.service.SqlExecutor.ParsedTableName;
import com.metafore.edge.service.SqlExecutor.WhereCond;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@code read} payload (ADR-016 F1.T2b: by_id, by_ids, list, count; Phase 13 keyset pagination; ADR-077 predicate pushdown),
 * parsed and checked up to the point where the database is needed. Moved out of {@link SqlExecutor#executeParametricRead}
 * unchanged so every database checks a payload in the same order and refuses it with the same message; the Postgres and the
 * dialect executors both start here.
 */
final class ReadRequest {

    final String pattern;
    final ParsedTableName table;
    final List<String> columns;
    final String filterColumn;
    final Object filterValue;
    final List<Object> filterValues;
    final boolean excludeTombstoned;
    final boolean keysetMode;
    final String keysetField;
    final Object keysetValue;
    final int effectiveLimit;
    final List<WhereCond> whereConds;

    private ReadRequest(String pattern, ParsedTableName table, List<String> columns, String filterColumn, Object filterValue,
                        List<Object> filterValues, boolean excludeTombstoned, boolean keysetMode, String keysetField,
                        Object keysetValue, int effectiveLimit, List<WhereCond> whereConds) {
        this.pattern = pattern;
        this.table = table;
        this.columns = columns;
        this.filterColumn = filterColumn;
        this.filterValue = filterValue;
        this.filterValues = filterValues;
        this.excludeTombstoned = excludeTombstoned;
        this.keysetMode = keysetMode;
        this.keysetField = keysetField;
        this.keysetValue = keysetValue;
        this.effectiveLimit = effectiveLimit;
        this.whereConds = whereConds;
    }

    /** Parses and checks a payload; an {@link IllegalArgumentException} carries the message to answer with. */
    @SuppressWarnings("unchecked")
    static ReadRequest parse(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Missing read payload");
        }
        String pattern = (String) payload.get("pattern");
        String tableName = (String) payload.get("table_name");
        Object colsObj = payload.get("columns");
        String filterColumn = (String) payload.get("filter_column");
        Object filterValue = payload.get("filter_value");
        Object filterValuesObj = payload.get("filter_values");
        boolean excludeTombstoned = Boolean.TRUE.equals(payload.get("exclude_tombstoned"));

        // Phase 13 / REK.T2a — keyset pagination fields. keyset_field absent → behave exactly as before.
        String keysetField = (String) payload.get("keyset_field");
        Object keysetValue = payload.get("keyset_value");
        Object limitObj = payload.get("limit");
        boolean keysetMode = keysetField != null && !keysetField.isBlank();

        if (pattern == null || !Set.of("by_id", "by_ids", "list", "count").contains(pattern)) {
            throw new IllegalArgumentException(
                "Unsupported read pattern: " + pattern + " (expected by_id/by_ids/list/count)");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Missing or empty table_name");
        }

        // ADR-077 — optional abstract-predicate pushdown. Parsed early so a malformed predicate or unsupported op fails
        // before any DB contact. Supported for list/count only (filter semantics).
        List<WhereCond> whereConds = SqlExecutor.parseWhereConditions(payload.get("where_conditions"));
        if (!whereConds.isEmpty() && !("list".equals(pattern) || "count".equals(pattern))) {
            throw new IllegalArgumentException(
                "where_conditions is only supported for list/count patterns (got pattern=" + pattern + ")");
        }

        ParsedTableName parsedTable = SqlExecutor.parseTableName(tableName);

        // Keyset-mode preconditions. Keyset semantics are list-only: ORDER BY + LIMIT on by_id / by_ids / count would
        // either be a contradiction (by_id returns ≤1) or change scalar semantics (count).
        int effectiveLimit = 0;
        if (keysetMode) {
            if (!"list".equals(pattern)) {
                throw new IllegalArgumentException("keyset_field only supported with pattern=list (got pattern=" + pattern + ")");
            }
            if (limitObj == null) {
                throw new IllegalArgumentException("keyset_field requires limit");
            }
            if (!(limitObj instanceof Number)) {
                throw new IllegalArgumentException("limit must be a number (got " + limitObj.getClass().getName() + ")");
            }
            int requestedLimit = ((Number) limitObj).intValue();
            if (requestedLimit <= 0) {
                throw new IllegalArgumentException("limit must be positive (got " + requestedLimit + ")");
            }
            // Page size is bounded by the adapter's declared ceiling (payload "max_page_size", forwarded by the
            // dispatcher), then by the edge's absolute MAX_PAGE_SIZE safety ceiling — NOT by MAX_ROWS. The has_more sentinel
            // (limit+1) is honored by the fetch cap, which uses effectiveLimit+1 in keyset mode.
            int pageCeiling = SqlExecutor.MAX_PAGE_SIZE;
            Object maxPageSizeObj = payload.get("max_page_size");
            if (maxPageSizeObj instanceof Number) {
                int declared = ((Number) maxPageSizeObj).intValue();
                if (declared > 0) {
                    pageCeiling = Math.min(pageCeiling, declared);
                }
            }
            effectiveLimit = Math.min(requestedLimit, pageCeiling);
        }

        // Pattern-specific required fields.
        List<Object> filterValues = null;
        if ("by_id".equals(pattern)) {
            if (filterColumn == null || filterColumn.isBlank()) {
                throw new IllegalArgumentException("by_id pattern requires filter_column");
            }
            if (filterValue == null) {
                throw new IllegalArgumentException("by_id pattern requires filter_value");
            }
        } else if ("by_ids".equals(pattern)) {
            if (filterColumn == null || filterColumn.isBlank()) {
                throw new IllegalArgumentException("by_ids pattern requires filter_column");
            }
            if (!(filterValuesObj instanceof List)) {
                throw new IllegalArgumentException("by_ids pattern requires filter_values list");
            }
            filterValues = new ArrayList<>((List<Object>) filterValuesObj);
            if (filterValues.isEmpty()) {
                throw new IllegalArgumentException("by_ids pattern requires non-empty filter_values");
            }
            if (filterValues.size() > SqlExecutor.MAX_ROWS) {
                throw new IllegalArgumentException("by_ids filter_values exceeds MAX_ROWS=" + SqlExecutor.MAX_ROWS
                    + " (size=" + filterValues.size() + ")");
            }
        } else if (filterColumn != null && !filterColumn.isBlank() && filterValue == null) {
            // list / count — filter_column is optional; if present, filter_value is required.
            throw new IllegalArgumentException(pattern + " pattern with filter_column requires filter_value");
        }

        // Normalize projection columns. Count ignores projection.
        List<String> columns;
        if ("count".equals(pattern)) {
            columns = Collections.emptyList();
        } else if (colsObj instanceof List) {
            columns = new ArrayList<>();
            for (Object c : (List<Object>) colsObj) {
                if (c != null) columns.add(c.toString());
            }
        } else {
            columns = Collections.emptyList();
        }
        return new ReadRequest(pattern, parsedTable, columns, filterColumn, filterValue, filterValues, excludeTombstoned,
            keysetMode, keysetField, keysetValue, effectiveLimit, whereConds);
    }

    /** The columns the catalog must know: projection (not for count), tombstoned_at, the keyset field, predicate columns. */
    List<String> columnsToValidate() {
        if ("count".equals(pattern) && !excludeTombstoned && !keysetMode && whereConds.isEmpty()) {
            return null;  // skip the per-column check
        }
        List<String> cv = new ArrayList<>();
        if (!"count".equals(pattern)) cv.addAll(columns);
        if (excludeTombstoned) cv.add("tombstoned_at");
        if (keysetMode) cv.add(keysetField);
        // ADR-077 — predicate columns must exist before we quote them into the WHERE clause.
        for (WhereCond c : whereConds) cv.add(c.column);
        return cv.isEmpty() ? null : cv;
    }

    /** The runtime type gate on filter values, keyset_value and predicate values; null when they are all bindable. */
    String valueTypeError() {
        String typeError;
        if ("by_ids".equals(pattern)) {
            typeError = SqlExecutor.validateValueTypes(filterValues, null);
        } else if (filterValue != null) {
            typeError = SqlExecutor.validateValueTypes(Collections.emptyList(), filterValue);
        } else {
            typeError = null;
        }
        if (typeError == null && keysetMode && keysetValue != null) {
            // the where_value slot surfaces a clean diagnostic; the rejection set is the same for any bound value
            typeError = SqlExecutor.validateValueTypes(Collections.emptyList(), keysetValue);
        }
        if (typeError == null && !whereConds.isEmpty()) {
            // ADR-077 — type-gate the flattened predicate binds (the same rejection set as filter/keyset values)
            typeError = SqlExecutor.validateValueTypes(SqlExecutor.collectWhereBinds(whereConds), null);
        }
        return typeError;
    }
}
