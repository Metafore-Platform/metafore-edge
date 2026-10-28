package com.metafore.edge.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Compass 85d16fea: the broker drops the connection of an edge that publishes a packet above its limit
 * ({@code mqtt.max_packet_size}, 1 MB by default), and the reply is lost: the platform sees only silence
 * ({@code edge_unreachable}). A result is therefore never published above {@link #MAX_BYTES}; the edge answers with a
 * typed {@code reply_too_large} error that names the size and the limit, and keeps the route id, the action and the
 * template id so the platform can correlate it. The limit is half the broker's, which leaves room for the MQTT header
 * and for a broker configured lower. The raising of the broker's limit is not the fix: a reply this large is a request
 * that should have been narrower.
 */
final class ReplyCap {

    static final String ERROR = "reply_too_large";
    static final int MAX_BYTES = 512 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(ReplyCap.class);

    private ReplyCap() {}

    /** The JSON as it is, or, when it is over {@link #MAX_BYTES}, the typed {@code reply_too_large} envelope instead. */
    static String limit(EdgeConfig config, String json) {
        int size = json == null ? 0 : json.getBytes(StandardCharsets.UTF_8).length;
        if (size <= MAX_BYTES) {
            return json;
        }
        try {
            JsonNode in = MAPPER.readTree(json);
            var out = MessageFactory.routeResult(config, in.path("route_id").asText(""), "error",
                in.path("action").isTextual() ? in.path("action").asText() : null, in.path("latency_ms").asLong(0),
                null, null, null, ERROR, "The result is " + size + " bytes; the edge publishes at most " + MAX_BYTES
                    + ". Nothing was dropped silently: ask for less (a narrower search or a smaller page).");
            if (in.path("template_id").isTextual()) {
                out.put("template_id", in.path("template_id").asText());
            }
            LOG.warn("A reply of {} bytes is over the {}-byte limit: answering {} (route_id={}, action={})",
                size, MAX_BYTES, ERROR, clean(in.path("route_id").asText("")), clean(in.path("action").asText("")));
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a reply the edge just serialised reads back and serialises again", e);
        }
    }

    private static String clean(String value) {
        return value.replaceAll("[\\r\\n\\t]", "_");
    }
}
