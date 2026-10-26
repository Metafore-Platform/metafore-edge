package com.metafore.edge.route;

import java.util.Map;

/**
 * Static utilities for parsing and classifying route-command payloads.
 * Extracted from {@link RouteExecutorRoute} to keep that class within
 * the project's 500-line limit as new actions are added.
 */
final class RouteYamlExtractor {

    private RouteYamlExtractor() {}

    /** Payload shape for the deploy/execute dispatch boundary. */
    enum PayloadKind { PARAMETRIC, LEGACY, AMBIGUOUS, MISSING }

    /**
     * Slice 33.1.0 — payload-shape classifier for the dispatch boundary.
     * Package-private so the four-state branching is directly testable.
     */
    static PayloadKind classifyPayload(
        Map<String, Object> params, String legacySql
    ) {
        boolean hasOperation = false;
        if (params != null) {
            Object op = params.get("operation");
            if (op instanceof String) {
                hasOperation = !((String) op).isBlank();
            }
        }
        boolean hasSql = legacySql != null && !legacySql.isEmpty();
        if (hasOperation && hasSql) return PayloadKind.AMBIGUOUS;
        if (!hasOperation && !hasSql) return PayloadKind.MISSING;
        return hasOperation ? PayloadKind.PARAMETRIC : PayloadKind.LEGACY;
    }

    static String extractQuery(String routeYaml) {
        if (routeYaml == null) return null;
        int idx = routeYaml.indexOf("query:");
        if (idx < 0) idx = routeYaml.indexOf("query :");
        if (idx < 0) return null;
        String after = routeYaml.substring(idx + 6).trim();
        if (after.startsWith(">")) after = after.substring(1).trim();
        StringBuilder sb = new StringBuilder();
        for (String line : after.split("\n")) {
            String trimmed = line.trim();
            if (sb.length() > 0 && !trimmed.isEmpty()
                && (trimmed.startsWith("-") || trimmed.contains(":"))) break;
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(trimmed);
            }
        }
        return sb.toString().trim();
    }
}
