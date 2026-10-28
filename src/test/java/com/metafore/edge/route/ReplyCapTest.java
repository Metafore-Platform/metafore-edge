package com.metafore.edge.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metafore.edge.config.EdgeConfig;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Compass 85d16fea — a result over the limit is never published; the edge answers reply_too_large instead. */
class ReplyCapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EdgeConfig CFG = EdgeConfig.from(Collections.emptyMap());

    private static String result(int padding) throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("route_id", "r-1").put("status", "success").put("action", "run_template").put("template_id", "fhir.server.metadata");
        n.put("latency_ms", 1500);
        n.putArray("data").addObject().put("filler", "x".repeat(padding));
        return MAPPER.writeValueAsString(n);
    }

    @Test
    void aReplyUnderTheLimitIsPublishedAsItIs() throws Exception {
        String json = result(ReplyCap.MAX_BYTES - 1000);
        assertSame(json, ReplyCap.limit(CFG, json));
    }

    @Test
    void aReplyOverTheLimitBecomesATypedErrorWithNoData() throws Exception {
        String json = result(2 * 1024 * 1024);
        JsonNode out = MAPPER.readTree(ReplyCap.limit(CFG, json));
        assertEquals("error", out.get("status").asText());
        assertEquals("reply_too_large", out.get("error").asText());
        assertEquals("r-1", out.get("route_id").asText());
        assertEquals("run_template", out.get("action").asText());
        assertEquals("fhir.server.metadata", out.get("template_id").asText());
        assertTrue(out.get("error_details").asText().contains(String.valueOf(json.length())), out.toString());
        assertFalse(out.has("data"), "the oversized data is not sent");
        assertTrue(out.toString().length() < 2000, "the typed error is small");
        try (InputStream in = ReplyCapTest.class.getResourceAsStream("/schemas/route_result.schema.json")) {
            ObjectNode schema = (ObjectNode) MAPPER.readTree(in);
            schema.remove("$id");
            assertTrue(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema).validate(out).isEmpty(),
                "the typed error is a valid route_result");
        }
    }

    @Test
    void theLimitIsHalfTheBrokersDefaultPacketSize() {
        assertTrue(ReplyCap.MAX_BYTES <= 512 * 1024);
    }
}
