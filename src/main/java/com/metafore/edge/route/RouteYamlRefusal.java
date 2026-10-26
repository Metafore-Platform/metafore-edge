package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The edge loads no route code (adr-060, adr-096; Compass f615b0a7). A route command that carries {@code route_yaml},
 * at the top level or in its parameters, whatever the action and whatever the value, is refused with a typed
 * {@code unsupported_payload} envelope and a WARN line. Nothing is read from the YAML or executed, and the YAML (which
 * could embed SQL) is not logged. The platform no longer sends it; this keeps an older core or a stray publisher from
 * putting route code or SQL in front of the executor.
 */
final class RouteYamlRefusal {

    static final String ERROR = "unsupported_payload";
    static final String DETAILS =
        "The edge does not load route code; a command carrying 'route_yaml' is not accepted. "
        + "Send a parametric 'operation' (adr-060, adr-096).";
    private static final Logger LOG = LoggerFactory.getLogger(RouteYamlRefusal.class);

    private RouteYamlRefusal() {}

    static boolean carriesRouteYaml(Map<String, Object> cmd) {
        if (cmd.containsKey("route_yaml")) {
            return true;
        }
        Object params = cmd.get("parameters");
        return params instanceof Map && ((Map<?, ?>) params).containsKey("route_yaml");
    }

    /** The refusal envelope, or {@code null} when the command carries no {@code route_yaml}. */
    static Map<String, Object> check(
        EdgeConfig config, String action, String routeId, Map<String, Object> cmd
    ) {
        if (!carriesRouteYaml(cmd)) {
            return null;
        }
        LOG.warn("Refused a route command that carries route_yaml: the edge loads no route code (action={}, route_id={})",
            clean(action), clean(routeId));
        return MessageFactory.routeResult(config, routeId,
            "rejected", action, 0, null, null, null,
            ERROR, DETAILS);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", "_");
    }
}
