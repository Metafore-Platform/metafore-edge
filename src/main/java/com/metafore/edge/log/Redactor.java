package com.metafore.edge.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place the edge decides what may not reach a log line (Compass 9ece454f).
 *
 * <p>A route command carries the connection it runs on: {@code db_user} and {@code db_pass}, and a REST or MCP
 * command can carry a token or a key. Every log line that prints a command, a URL or an exception message goes
 * through here first, so a secret is never written at any level. The rest of the line is kept, so the log stays
 * useful: only the VALUE of a credential-shaped field is replaced, never the field name.
 *
 * <p>A field is credential-shaped when its name (lower-cased) contains one of {@link #FRAGMENTS}, or has one of
 * {@link #WORDS} as a whole word (split on anything that is not a letter or digit, so {@code api_key} and
 * {@code apiKey}-style names are caught but {@code keyspace} is not). Nested objects and arrays are walked.
 * Text that is not JSON is never printed as it is: it is reduced to its length.
 */
public final class Redactor {

    /** What replaces a secret's value. */
    public static final String MASK = "***";

    /** A name containing any of these is a credential, wherever it sits in the name. */
    static final List<String> FRAGMENTS = List.of(
        "pass", "pwd", "secret", "token", "credential", "apikey", "auth", "bearer", "cookie", "jwt", "private",
        "signature");

    /** A name having any of these as a whole word is a credential (or who it belongs to). */
    static final Set<String> WORDS = Set.of("key", "user", "username", "login", "uid");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    /** {@code name=value} or {@code name: value} in free text (an exception message, a query string). */
    private static final Pattern PAIR = Pattern.compile(
        "(?i)\\b([a-z0-9_.-]*(?:pass|pwd|secret|token|credential|apikey|api_key|auth|bearer|key)[a-z0-9_.-]*)"
        + "(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s&;,)\\]}]+)");
    /** {@code scheme://user:password@host}: the whole user-info goes. */
    private static final Pattern USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");

    private Redactor() {
    }

    /** Is a field of this name a credential, or who one belongs to? */
    public static boolean sensitive(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String fragment : FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        for (String word : NON_ALNUM.split(lower)) {
            if (WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A JSON command as a log line: every credential-shaped value replaced, the rest as it came. Not JSON (or empty):
     * only its length, because there is no telling what it holds.
     */
    public static String json(String body) {
        if (body == null) {
            return "null";
        }
        if (body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || !(node.isObject() || node.isArray())) {
                return "<not a JSON object, " + body.length() + " chars>";
            }
            return MAPPER.writeValueAsString(mask(node));
        } catch (Exception e) {
            return "<not readable JSON, " + body.length() + " chars>";
        }
    }

    /** A command already read into a map, as a log line. */
    public static String map(Map<?, ?> command) {
        if (command == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(mask(MAPPER.valueToTree(command)));
        } catch (Exception e) {
            return "<unprintable map, " + command.size() + " entries>";
        }
    }

    /** A URL as a log line: the user-info gone, and the value of every credential-shaped query parameter. */
    public static String url(String url) {
        if (url == null) {
            return "null";
        }
        return text(url);
    }

    /**
     * Free text (an exception message, a URL) with credential-shaped {@code name=value} pairs and URL user-info
     * replaced. For text a library wrote, where the shape is not ours.
     */
    public static String text(String text) {
        if (text == null) {
            return "null";
        }
        String out = USERINFO.matcher(text).replaceAll("$1" + MASK + "@");
        Matcher m = PAIR.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + MASK));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static JsonNode mask(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (sensitive(field.getKey()) && !value.isNull()) {
                    out.put(field.getKey(), MASK);
                } else {
                    out.set(field.getKey(), mask(value));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                out.add(mask(item));
            }
            return out;
        }
        if (node.isTextual()) {
            return MAPPER.getNodeFactory().textNode(text(node.asText()));
        }
        return node;
    }

    /**
     * Read a JSON command into a map. A failure names the position but never quotes the source: Jackson's own message
     * repeats the first characters of the input, which here is the credentials.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String body) throws java.io.IOException {
        return (Map<String, Object>) PARSER.readValue(body, Map.class);
    }

    private static final ObjectMapper PARSER = new ObjectMapper(
        com.fasterxml.jackson.core.JsonFactory.builder()
            .disable(com.fasterxml.jackson.core.StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build());
}
