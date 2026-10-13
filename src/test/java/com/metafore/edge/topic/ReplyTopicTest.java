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
        // the id is an allowlist ([A-Za-z0-9._-]{1,128}); Camel would read '?' and '&' as endpoint options
        "response/acme/acme-managed/a-1?brokerUrl=tcp://evil:1883",
        "response/acme/acme-managed/a-1&brokerUrl=tcp://evil:1883",
        "response/acme/acme-managed/a-1?x=1",
        "response/acme/acme-managed/a%3F1",
        "response/acme/acme-managed/a%3fbrokerUrl=x",
        "response/acme/acme-managed/a 1",
        "response/acme/acme-managed/a\t1",
        "response/acme/acme-managed/a\n1",
        "response/acme/acme-managed/a-1\n",
        "response/acme/acme-managed/\u00e9",
        "response/acme/acme-managed/a;b",
        "response/acme/acme-managed/a=b",
        "response/acme/acme-managed/a\\b",
        "response/acme/acme-managed/.." + "/x",
    })
    void anythingElseIsNotAnsweredOn(String candidate) {
        assertNull(ReplyTopic.forEdge(candidate, topics), candidate);
    }

    @Test
    void theIdIsOneToOneHundredTwentyEightPlainCharacters() {
        String longest = "a".repeat(128);
        assertEquals("response/acme/acme-managed/" + longest, ReplyTopic.forEdge("response/acme/acme-managed/" + longest, topics));
        assertNull(ReplyTopic.forEdge("response/acme/acme-managed/" + "a".repeat(129), topics));
        for (String id : new String[] {"a", "A-1", "x.y_z-9", "0", "...", "-", "9f1c2d3e-aaaa-bbbb-cccc-0123456789ab"}) {
            assertNotNull(ReplyTopic.forEdge("response/acme/acme-managed/" + id, topics), id);
        }
    }

    @Test
    void aTenantOrControllerNameIsComparedExactlyEvenWithSpacesAndCapitals() {
        TopicBuilder odd = new TopicBuilder("Acme Corp", "Acme-Managed");
        assertNotNull(ReplyTopic.forEdge("response/Acme Corp/Acme-Managed/a-1", odd));
        assertNull(ReplyTopic.forEdge("response/acme corp/Acme-Managed/a-1", odd));
        assertNull(ReplyTopic.forEdge("response/Acme Corp/Acme-Managed/a-1?x=1", odd));
    }

    @Test
    void aMissingOrNonStringTopicIsNotAnsweredOn() {
        assertNull(ReplyTopic.forEdge(null, topics));
        assertNull(ReplyTopic.forEdge(42, topics));
        assertNull(ReplyTopic.forEdge(java.util.List.of("response/acme/acme-managed/a-1"), topics));
    }
}
