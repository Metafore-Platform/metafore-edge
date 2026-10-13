package com.metafore.edge.topic;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The reply topic a command names is answered on only when it is this edge's own per-edge topic. */
class ReplyTopicTest {

    private final TopicBuilder topics = new TopicBuilder("acme", "acme-managed");

    @Test
    void theEdgesOwnPerEdgeTopicIsAnswered() {
        assertEquals("response/acme/acme-managed/a-1", ReplyTopic.forEdge("response/acme/acme-managed/a-1", topics));
        assertEquals("response/acme/acme-managed/", topics.responsePrefix());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "response/abc",                                  // the retired shared response/<id>: any edge could answer there
        "response/acme/other-managed/a-1",               // another controller
        "response/other/acme-managed/a-1",               // another tenant
        "response/acme/acme-managed/#",                  // wildcards
        "response/acme/acme-managed/+",
        "response/+/acme-managed/a-1",
        "response/acme/acme-managed/",                   // an empty level
        "response/acme//a-1",
        "response//acme-managed/a-1",
        "response/acme/acme-managed/a/b",                // four levels
        "response/acme/acme-managed",                    // two levels
        "telemetry/acme/acme-managed/route-results",     // not a response topic at all
        "control/acme/acme-managed/routes",
        "response/acme/acme-managed-evil/a-1",           // a prefix of the right shape but another controller
        "RESPONSE/acme/acme-managed/a-1",
        "/response/acme/acme-managed/a-1",
        "   ",
        "",
    })
    void anythingElseIsNotAnsweredOn(String candidate) {
        assertNull(ReplyTopic.forEdge(candidate, topics), candidate);
    }

    @Test
    void aMissingOrNonStringTopicIsNotAnsweredOn() {
        assertNull(ReplyTopic.forEdge(null, topics));
        assertNull(ReplyTopic.forEdge(42, topics));
        assertNull(ReplyTopic.forEdge(java.util.List.of("response/acme/acme-managed/a-1"), topics));
    }
}
