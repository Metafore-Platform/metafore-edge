package com.metafore.edge.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Compass 85d16fea: the broker drops the connection of an edge that publishes a packet above its limit
 * ({@code mqtt.max_packet_size}, 1 MB by default), and the reply is lost: the platform sees only silence
 * ({@code edge_unreachable}). No result or reply is published above {@code REPLY_MAX_BYTES} (default 512 KB, half the
 * broker's, which leaves room for the MQTT header and for a broker set lower). {@link #processor} is the ONE step every
 * result publish of the edge passes through (command results, write-back results, discovery results), placed after the
 * body is the JSON string and before the first publish, so the telemetry copy and the reply-topic copy are both capped.
 *
 * <p>Each message keeps its own contract and says it plainly:
 * <ul>
 *   <li>a route result: {@code status=error}, {@code error=reply_too_large}, the size and the limit in
 *       {@code error_details}, and the route id, action and template id;</li>
 *   <li>a write-back result: {@code status=error}, its {@code http_status}, and {@code response_body} that starts
 *       {@code reply_too_large:} (the schema has no error field);</li>
 *   <li>a discovery result: the largest capabilities are replaced by {@code {status: failed, error: reply_too_large...}}
 *       until it fits, so the others still arrive.</li>
 * </ul>
 * Raising the broker's limit is not the fix: a reply this large is a request that should have been narrower.
 */
final class ReplyCap {

    static final String ERROR = "reply_too_large";
    /** The default limit, kept for tests; the edge reads {@link EdgeConfig#replyMaxBytes()}. */
    static final int MAX_BYTES = EdgeConfig.DEFAULT_REPLY_MAX_BYTES;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ReplyCap.class);

    private ReplyCap() {}

    /** The step to put in every route that publishes a result: replaces the JSON String body when it is over the limit. */
    static Processor processor(EdgeConfig config) {
        return exchange -> exchange.getIn().setBody(limit(config, exchange.getIn().getBody(String.class)));
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The JSON as it is, or, when it is over the limit, the typed replacement for its kind. */
    static String limit(EdgeConfig config, String json) {
        int max = config.replyMaxBytes();
        if (json == null || bytes(json) <= max) {
            return json;
        }
        int size = bytes(json);
        try {
            JsonNode in = MAPPER.readTree(json);
            String detail = "The result is " + size + " bytes; the edge publishes at most " + max
                + ". Nothing was dropped silently: ask for less (a narrower search or a smaller page).";
            LOG.warn("A result of {} bytes is over the {}-byte limit: answering {} ({})", size, max, ERROR, kind(in));
            Object out;
            if (in.has("action_id")) {
                out = MessageFactory.writeBackResult(config, in.path("action_id").asText(""), "error",
                    in.path("http_status").asInt(0), ERROR + ": " + detail, null, null);
            } else if (in.has("run_id")) {
                out = fitDiscovery(config, (ObjectNode) in, max, size);
            } else {
                var r = MessageFactory.routeResult(config, in.path("route_id").asText(""), "error",
                    in.path("action").isTextual() ? in.path("action").asText() : null, in.path("latency_ms").asLong(0),
                    null, null, null, ERROR, detail);
                if (in.path("template_id").isTextual()) {
                    r.put("template_id", in.path("template_id").asText());
                }
                out = r;
            }
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a result the edge just serialised reads back and serialises again", e);
        }
    }

    /** The discovery result with its largest capabilities replaced by a failed capability until it fits. */
    private static Map<String, Object> fitDiscovery(EdgeConfig config, ObjectNode in, int max, int size)
        throws JsonProcessingException {
        ObjectNode caps = in.has("capabilities") && in.get("capabilities").isObject()
            ? (ObjectNode) in.get("capabilities") : MAPPER.createObjectNode();
        List<String> bySize = new ArrayList<>();
        for (Iterator<String> it = caps.fieldNames(); it.hasNext(); ) {
            bySize.add(it.next());
        }
        bySize.sort((a, b) -> Integer.compare(caps.get(b).toString().length(), caps.get(a).toString().length()));
        Map<String, Object> out = MessageFactory.discoveryResult(config, in.path("run_id").asText(""),
            in.path("trigger").asText(""), null);
        for (String key : bySize) {
            if (bytes(MAPPER.writeValueAsString(withCaps(in, out, caps))) <= max) {
                break;
            }
            ObjectNode failed = MAPPER.createObjectNode();
            failed.put("status", "failed").put("error", ERROR + ": " + size + " bytes in all, limit " + max);
            if (failed.toString().length() >= caps.get(key).toString().length()) {
                break;   // the rest are smaller than the replacement: replacing them would only make the message bigger
            }
            caps.set(key, failed);
        }
        return withCaps(in, out, caps);
    }

    private static Map<String, Object> withCaps(ObjectNode in, Map<String, Object> out, ObjectNode caps) {
        out.put("capabilities", MAPPER.convertValue(caps, Map.class));
        return out;
    }

    private static String kind(JsonNode in) {
        String id = in.has("action_id") ? "action_id=" + in.path("action_id").asText("")
            : in.has("run_id") ? "run_id=" + in.path("run_id").asText("") : "route_id=" + in.path("route_id").asText("");
        return id.replaceAll("[\\r\\n\\t]", "_");
    }
}
