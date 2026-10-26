package com.metafore.edge.route;

import java.util.Map;

/**
 * Static utilities for classifying route-command payloads. (It once also pulled a query out of a command's
 * {@code route_yaml}; the edge loads no route code now, so a command carrying {@code route_yaml} is refused
 * first, see {@link RouteYamlRefusal}.)
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
}
