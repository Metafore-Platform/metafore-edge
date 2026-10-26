package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The edge has no shell path (Compass 3bb6be4e). A route command that carries {@code shell_command}, in its
 * parameters or at the top level, whatever the action and whatever the value, is refused with a typed
 * {@code shell_not_supported} envelope and a WARN line. Nothing is executed, and the command text is not logged.
 */
final class ShellRefusal {

    static final String ERROR = "shell_not_supported";
    private static final Logger LOG = LoggerFactory.getLogger(ShellRefusal.class);

    private ShellRefusal() {}

    static boolean carriesShellCommand(Map<String, Object> cmd) {
        if (cmd.containsKey("shell_command")) {
            return true;
        }
        Object params = cmd.get("parameters");
        return params instanceof Map && ((Map<?, ?>) params).containsKey("shell_command");
    }

    /** The refusal envelope, or {@code null} when the command carries no {@code shell_command}. */
    static Map<String, Object> check(
        EdgeConfig config, String action, String routeId, Map<String, Object> cmd
    ) {
        if (!carriesShellCommand(cmd)) {
            return null;
        }
        LOG.warn("Refused a route command that carries shell_command: the edge has no shell path (action={}, route_id={})",
            clean(action), clean(routeId));
        return MessageFactory.routeResult(config, routeId,
            "rejected", action, 0, null, null, null,
            ERROR, "The edge has no shell path; a command carrying 'shell_command' is not accepted.");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", "_");
    }
}
