package com.metafore.edge.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The one line a route writes when a command arrives (Compass 03b23c26): which command it was, never what it carried.
 *
 * <p>The line names the {@code action} and {@code route_id} (only when they are plain names) and the KEY names of the
 * command and of its {@code parameters}. It never prints a value: a command holds connection credentials, record values
 * (customer data), SQL text and URLs, and a name-based mask cannot tell which of those a log may keep. Key names are
 * checked too, so a hostile key cannot add a line break or a long string to the log. Text that is not a JSON object is
 * reduced to its length.
 */
public final class CommandLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final int MAX_KEYS = 50;

    private CommandLog() {
    }

    public static void received(Logger log, String what, String body) {
        log.info("{}: {}", what, shape(body));
    }

    /** What arrived, without any value: action, route_id, and the key names of the command and its parameters. */
    public static String shape(String body) {
        if (body == null) {
            return "null";
        }
        if (body.isBlank()) {
            return "";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            return "<not readable JSON, " + body.length() + " chars>";
        }
        if (node == null || !node.isObject()) {
            return "<not a JSON object, " + body.length() + " chars>";
        }
        JsonNode params = node.get("parameters");
        return "action=" + plain(node.get("action")) + " route_id=" + plain(node.get("route_id"))
            + " keys=" + names(fieldNames(node))
            + " parameter_keys=" + (params != null && params.isObject() ? names(fieldNames(params)) : "[]");
    }

    /** The key names of a command already read into a map, for a line that must say which command without its values. */
    public static String keys(Map<?, ?> command) {
        if (command == null) {
            return "null";
        }
        Collection<String> keys = new TreeSet<>();
        for (Object key : command.keySet()) {
            keys.add(String.valueOf(key));
        }
        return names(keys);
    }

    private static Collection<String> fieldNames(JsonNode node) {
        Collection<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        return keys;
    }

    private static String names(Collection<String> keys) {
        StringBuilder out = new StringBuilder("[");
        int shown = 0;
        for (String key : keys) {
            if (shown == MAX_KEYS) {
                out.append(", +").append(keys.size() - MAX_KEYS).append(" more");
                break;
            }
            out.append(shown++ == 0 ? "" : ", ").append(PLAIN_NAME.matcher(key).matches() ? key : "<odd key>");
        }
        return out.append(']').toString();
    }

    private static String plain(JsonNode value) {
        if (value == null || value.isNull()) {
            return "-";
        }
        return value.isTextual() && PLAIN_NAME.matcher(value.asText()).matches() ? value.asText() : "<not a plain name>";
    }
}
