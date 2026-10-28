package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.topic.TopicBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Compass 268f2e11: a refusal reaches core as an audit row, not only as an edge log line. When a command's result
 * carries one of the four typed refusals, the edge also publishes {@code kind: edge_refusal} on the events topic
 * (the contract is {@code edge_refusal.schema.json}, pinned from metafore-api).
 *
 * <p>Keys only. The event names the route id, the action and the typed code, and a FIXED sentence per code. It
 * never carries a parameter, a command value, the transport the command named, the connection, or any credential:
 * those are exactly what an attacker or a mistake puts in a refused command.
 */
final class RefusalEvent {

    static final String KIND = "edge_refusal";

    private static final Map<String, String> REASONS = Map.of(
        "shell_not_supported", "The edge has no shell path; a command carrying 'shell_command' was not run.",
        "unsupported_payload", "The edge loads no route code; a command carrying 'route_yaml' was not run.",
        "unsupported_mcp_transport", "The edge starts no process for an MCP call; only http and sse are supported.",
        "unsupported_connection_auth", "The connection asked for an authentication the edge cannot make; nothing was sent."
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RefusalEvent() {}

    /** {@link #from} as the JSON the events topic carries, or {@code null} when the result is no refusal. */
    static String json(EdgeConfig config, TopicBuilder topics, Map<String, Object> result) {
        Map<String, Object> event = from(config, topics, result);
        try {
            return event == null ? null : MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a refusal event is plain strings and always serialises", e);
        }
    }

    /** The event for a result that is one of the four refusals, or {@code null} for any other result. */
    static Map<String, Object> from(EdgeConfig config, TopicBuilder topics, Map<String, Object> result) {
        Object code = result == null ? null : result.get("error");
        if (!(code instanceof String) || !REASONS.containsKey(code)) {
            return null;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", KIND);
        event.put("event_id", UUID.randomUUID().toString());
        event.put("controller_id", config.controllerId());
        event.put("tenant_id", topics.tenantId());
        event.put("timestamp", Instant.now().toString());
        event.put("severity", "warn");
        event.put("refusal_code", code);
        putText(event, "route_id", result.get("route_id"), 200);
        event.put("action", text(result.get("action"), 64));
        event.put("reason", REASONS.get(code));
        return event;
    }

    private static void putText(Map<String, Object> event, String key, Object value, int max) {
        String text = text(value, max);
        if (!text.isEmpty()) {
            event.put(key, text);
        }
    }

    private static String text(Object value, int max) {
        String s = value instanceof String ? (String) value : "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
