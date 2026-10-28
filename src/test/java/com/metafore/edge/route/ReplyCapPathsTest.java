package com.metafore.edge.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.topic.TopicBuilder;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compass 85d16fea (a6's F1) — the cap is one step every result publish passes through, not a property of one route. The
 * write-back route and both discovery routes (on demand and at startup) are run for real and each answers over the limit
 * with its own contract's typed form, on the telemetry copy and on the reply-topic copy alike.
 */
class ReplyCapPathsTest extends CamelTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OWN = "response/acme/acme-managed/a-1";
    private final EdgeConfig config = EdgeConfig.from(Map.of(
        "CONTROLLER_ID", "acme-managed", "TENANT_ID", "acme", "BROKER_URL", "ssl://emqx:8883",
        "DISCOVERY_DELAY_MS", "50", "REPLY_MAX_BYTES", "400"));
    private final TopicBuilder topics = new TopicBuilder("acme", "acme-managed");
    private HttpServer server;

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    protected RoutesBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                getContext().addRoutes(new RestAdapterRoute(config, topics));
                getContext().addRoutes(new DiscoveryRoute(config, topics));
            }
        };
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int bytes) throws Exception {
        return serve(bytes, 200);
    }

    private String serve(int bytes, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big", ex -> {
            byte[] body = "x".repeat(bytes).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/big";
    }

    private void advise() throws Exception {
        AdviceWith.adviceWith(context, "rest-adapter-acme", a -> {
            a.replaceFromWith("direct:write-back");
            a.weaveById("write-back-result-publish").replace().to("mock:wb-result");
            a.weaveById("write-back-reply-publish").replace().to("mock:wb-reply");
        });
        AdviceWith.adviceWith(context, "discovery-ondemand-acme", a -> {
            a.replaceFromWith("direct:discovery");
            a.weaveById("discovery-result-publish").replace().to("mock:disc-result");
            a.weaveById("discovery-reply-publish").replace().to("mock:disc-reply");
        });
        AdviceWith.adviceWith(context, "discovery-startup-acme", a -> a.weaveByToUri("paho:*").replace().to("mock:startup"));
        getMockEndpoint("mock:startup").expectedMessageCount(1);
        context.start();
    }

    private JsonNode body(String mock, int index) throws Exception {
        return MAPPER.readTree(getMockEndpoint("mock:" + mock).getExchanges().get(index).getIn().getBody(String.class));
    }

    @Test
    void aWriteBackThatSucceededStaysASuccessWhenItsAnswerIsOverTheLimit() throws Exception {
        advise();
        for (String m : new String[] {"wb-result", "wb-reply"}) getMockEndpoint("mock:" + m).expectedMessageCount(1);
        template.sendBody("direct:write-back", "{\"action_id\":\"a-1\",\"method\":\"POST\",\"url\":\"" + serve(2 * 1024 * 1024, 201)
            + "\",\"old_values\":{\"state\":\"open\"},\"new_values\":{\"state\":\"resolved\"},\"response_topic\":\"" + OWN + "\"}");
        for (String m : new String[] {"wb-result", "wb-reply"}) {
            getMockEndpoint("mock:" + m).assertIsSatisfied(10000);
            JsonNode out = body(m, 0);
            assertEquals("a-1", out.get("action_id").asText(), m);
            assertEquals("success", out.get("status").asText(), "the write happened: " + out);
            assertEquals(201, out.get("http_status").asInt(), m);
            assertEquals("open", out.get("old_values").get("state").asText(), m);
            assertEquals("resolved", out.get("new_values").get("state").asText(), m);
            JsonNode inner = MAPPER.readTree(out.get("response_body").asText());   // valid JSON, as core parses it
            assertEquals("reply_too_large", inner.get("error").asText());
            assertEquals(400, inner.get("limit").asInt());
            assertTrue(inner.get("size").asInt() > 2 * 1024 * 1024, inner.toString());
            assertTrue(out.toString().length() < 1000, "the answer is small");
        }
    }

    @Test
    void aFailedWriteBackStaysAnErrorWithItsOwnStatus() throws Exception {
        advise();
        getMockEndpoint("mock:wb-result").expectedMessageCount(1);
        template.sendBody("direct:write-back", "{\"action_id\":\"a-3\",\"method\":\"POST\",\"url\":\"" + serve(2 * 1024 * 1024, 500) + "\"}");
        getMockEndpoint("mock:wb-result").assertIsSatisfied(10000);
        JsonNode out = body("wb-result", 0);
        assertEquals("error", out.get("status").asText());
        assertEquals(500, out.get("http_status").asInt());
        assertEquals("reply_too_large", MAPPER.readTree(out.get("response_body").asText()).get("error").asText());
    }

    @Test
    void aSmallWriteBackAnswerIsUntouched() throws Exception {
        advise();
        getMockEndpoint("mock:wb-result").expectedMessageCount(1);
        template.sendBody("direct:write-back", "{\"action_id\":\"a-2\",\"method\":\"GET\",\"url\":\"" + serve(20) + "\"}");
        getMockEndpoint("mock:wb-result").assertIsSatisfied(10000);
        assertEquals("xxxxxxxxxxxxxxxxxxxx", body("wb-result", 0).get("response_body").asText());
    }

    @Test
    void anOnDemandDiscoveryOverTheLimitFailsItsLargestCapabilitiesAndKeepsTheRest() throws Exception {
        advise();
        for (String m : new String[] {"disc-result", "disc-reply"}) getMockEndpoint("mock:" + m).expectedMessageCount(1);
        template.sendBody("direct:discovery", "{\"trigger\":\"remote\",\"scope\":{\"discover_os\":true,\"discover_ports\":true,"
            + "\"discover_processes\":true},\"response_topic\":\"" + OWN + "\"}");
        for (String m : new String[] {"disc-result", "disc-reply"}) {
            getMockEndpoint("mock:" + m).assertIsSatisfied(20000);
            JsonNode out = body(m, 0);
            assertTrue(out.has("run_id") && out.has("trigger"), out.toString());
            boolean failed = false;
            for (JsonNode cap : out.get("capabilities")) {
                if (cap.path("status").asText().equals("failed")) {
                    failed = true;
                    assertTrue(cap.get("error").asText().startsWith("reply_too_large:"), cap.toString());
                }
            }
            assertTrue(failed, "a capability over the limit is replaced: " + out);
        }
    }

    @Test
    void theStartupDiscoveryIsCappedToo() throws Exception {
        advise();
        getMockEndpoint("mock:startup").assertIsSatisfied(20000);
        JsonNode out = MAPPER.readTree(getMockEndpoint("mock:startup").getExchanges().get(0).getIn().getBody(String.class));
        assertEquals("startup", out.get("trigger").asText());
        boolean failed = false;
        for (JsonNode cap : out.get("capabilities")) {
            failed |= cap.path("status").asText().equals("failed")
                && cap.path("error").asText().startsWith("reply_too_large:");
        }
        assertTrue(failed, "a startup capability over the limit is replaced: " + out);
    }

    @Test
    void theLimitIsConfigurableAndNotBelowAFloor() {
        assertEquals(EdgeConfig.DEFAULT_REPLY_MAX_BYTES, EdgeConfig.from(Map.of()).replyMaxBytes());
        assertThrows(IllegalArgumentException.class, () -> EdgeConfig.from(Map.of("REPLY_MAX_BYTES", "10")));
    }
}
