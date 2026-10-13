package com.metafore.edge.topic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The reply topic a command names ({@code response_topic}), checked before this edge publishes on it.
 *
 * <p>v2 replies are per edge: {@code response/<tenant>/<controller>/<id>}. The topic must be concrete (three levels, no
 * empty level, no {@code +} or {@code #}) and must be THIS edge's own ({@code response/<its tenant>/<its controller>/});
 * the retired shared {@code response/<id>}, another edge's topic and anything malformed are not answered on: the result
 * still goes to its telemetry topic and the refusal is logged. (The broker would refuse the publish anyway: its topic
 * rules let an edge publish only under {@code response/<its username>/}.) Same rule as the Go agent's
 * {@code ValidResponseTopic} plus its own-edge check.
 */
public final class ReplyTopic {

    private static final Logger LOG = LoggerFactory.getLogger(ReplyTopic.class);

    private ReplyTopic() { }

    /** {@code candidate} when it is this edge's own per-edge reply topic, else {@code null} (also for a missing one). */
    public static String forEdge(Object candidate, TopicBuilder topics) {
        if (!(candidate instanceof String topic) || topic.isBlank()) {
            return null;
        }
        if (concrete(topic) == null || !topic.startsWith(topics.responsePrefix())) {
            LOG.warn("A command named a reply topic that is not this edge's ({}); not answered there. Answers go to "
                + "{}<id> only.", topic, topics.responsePrefix());
            return null;
        }
        return topic;
    }

    /** {@code s} when it is {@code response/<a>/<b>/<c>} with no wildcard and no blank level; else {@code null}. */
    static String concrete(String s) {
        if (!s.startsWith("response/") || s.contains("+") || s.contains("#")) {
            return null;
        }
        String[] levels = s.substring("response/".length()).split("/", -1);
        if (levels.length != 3) {
            return null;
        }
        for (String level : levels) {
            if (level.isBlank()) {
                return null;
            }
        }
        return s;
    }
}
