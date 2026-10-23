package com.metafore.edge.topic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * The reply topic a command names ({@code response_topic}), checked before this edge publishes on it.
 *
 * <p>v2 replies are per edge: {@code response/<tenant>/<controller>/<id>}. The topic must be THIS edge's own
 * ({@code response/<its tenant>/<its controller>/}, compared exactly) followed by an id of {@code [A-Za-z0-9._-]{1,128}}
 * (an allowlist: no wildcard, no further level, no {@code ?} or {@code &} that Camel would read as endpoint options);
 * the retired shared {@code response/<id>}, another edge's topic and anything malformed are not answered on: the result
 * still goes to its telemetry topic and the refusal is logged. (The broker would refuse the publish anyway: its topic
 * rules let an edge publish only under {@code response/<its username>/}.) Same rule as the Go agent's
 * {@code ValidResponseTopic} plus its own-edge check.
 */
public final class ReplyTopic {

    private static final Logger LOG = LoggerFactory.getLogger(ReplyTopic.class);

    private ReplyTopic() { }

    /** The last level of a reply topic: a short id of plain characters, never anything a URI or a topic filter could read. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** {@code candidate} when it is this edge's own per-edge reply topic, else {@code null} (also for a missing one). */
    public static String forEdge(Object candidate, TopicBuilder topics) {
        if (!(candidate instanceof String topic) || topic.isBlank()) {
            return null;
        }
        String prefix = topics.responsePrefix();
        // this edge's own tenant and controller, compared exactly, then an id from an allowlist: no '/', '+', '#', '?', '&',
        // '%', space, control character or anything else (a '?' would otherwise become endpoint options in a Camel URI)
        if (!topic.startsWith(prefix) || !ID.matcher(topic.substring(prefix.length())).matches()) {
            LOG.warn("A command named a reply topic that is not this edge's ({}); not answered there. Answers go to "
                + "{}<id> only (id: letters, digits, '.', '_', '-', up to 128).", printable(topic), prefix);
            return null;
        }
        return topic;
    }

    /** The topic for a log line: control characters shown, length bounded (the string came from a command). */
    private static String printable(String s) {
        String cut = s.length() > 200 ? s.substring(0, 200) + "..." : s;
        return cut.replaceAll("\\p{Cntrl}", "?");
    }
}
