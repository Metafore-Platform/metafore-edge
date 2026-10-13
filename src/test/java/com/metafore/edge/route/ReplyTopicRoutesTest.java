package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v2: the route result, the discovery result and the write-back result also go to the command's reply topic
 * ({@code response/<tenant>/<controller>/<id>}) when it is this edge's own, and only then. The paho endpoints are replaced
 * with mocks; the commands go in through direct endpoints in place of the MQTT consumers.
 */
class ReplyTopicRoutesTest extends CamelTestSupport {

    private static final String OWN = "response/acme/acme-managed/a-1";
    private final EdgeConfig config = EdgeConfig.from(java.util.Map.of(
        "CONTROLLER_ID", "acme-managed", "TENANT_ID", "acme", "BROKER_URL", "ssl://emqx:8883"));
    private final TopicBuilder topics = new TopicBuilder("acme", "acme-managed");

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    protected RoutesBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                getContext().addRoutes(new RouteExecutorRoute(config, topics, new DataSourceRegistry(getContext())));
                getContext().addRoutes(new RestAdapterRoute(config, topics));
                getContext().addRoutes(new DiscoveryRoute(config, topics));
            }
        };
    }

    private void advise(String routeId, String from, String resultId, String replyId) throws Exception {
        AdviceWith.adviceWith(context, routeId, a -> {
            a.replaceFromWith(from);
            a.weaveById(resultId).replace().to("mock:" + routeId + "-result");
            a.weaveById(replyId).replace().to("mock:" + routeId + "-reply");
        });
    }

    private void adviseAll() throws Exception {
        advise("route-executor-acme", "direct:routes", "route-result-publish", "route-reply-publish");
        advise("rest-adapter-acme", "direct:write-back", "write-back-result-publish", "write-back-reply-publish");
        advise("discovery-ondemand-acme", "direct:discovery", "discovery-result-publish", "discovery-reply-publish");
        // the startup discovery is a timer publishing to the broker: keep it off the network
        AdviceWith.adviceWith(context, "discovery-startup-acme", a -> a.weaveByToUri("paho:*").replace().to("mock:startup"));
        context.start();
    }

    private MockEndpoint mock(String name) {
        return getMockEndpoint("mock:" + name);
    }

    private static String command(String route, String responseTopic) {
        String topic = responseTopic == null ? "" : ",\"response_topic\":" + responseTopic;
        return switch (route) {
            case "routes" -> "{\"action\":\"remove\",\"route_id\":\"r-1\"" + topic + "}";
            case "write-back" -> "{\"action_id\":\"a-1\",\"method\":\"FETCH\",\"url\":\"http://x\"" + topic + "}";
            default -> "{\"trigger\":\"remote\",\"scope\":{\"discover_os\":false,\"discover_ports\":false,\"discover_processes\":false}" + topic + "}";
        };
    }

    private static final String[][] ROUTES = {
        {"routes", "route-executor-acme"}, {"write-back", "rest-adapter-acme"}, {"discovery", "discovery-ondemand-acme"}};

    @Test
    void anOwnPerEdgeTopicGetsTheResultAsWellAsTelemetry() throws Exception {
        adviseAll();
        for (String[] r : ROUTES) {
            mock(r[1] + "-result").expectedMessageCount(1);
            mock(r[1] + "-reply").expectedMessageCount(1);
            template.sendBody("direct:" + r[0], command(r[0], "\"" + OWN + "\""));
            mock(r[1] + "-result").assertIsSatisfied(5000);
            mock(r[1] + "-reply").assertIsSatisfied(5000);
            assertEquals(mock(r[1] + "-result").getExchanges().get(0).getIn().getBody(String.class),
                mock(r[1] + "-reply").getExchanges().get(0).getIn().getBody(String.class), "the same result on both topics");
            // the reply goes out through a fixed endpoint with the topic as a header, never as part of an endpoint URI
            assertEquals(OWN, mock(r[1] + "-reply").getExchanges().get(0).getIn()
                .getHeader(org.apache.camel.component.paho.PahoConstants.CAMEL_PAHO_OVERRIDE_TOPIC));
            assertNull(mock(r[1] + "-result").getExchanges().get(0).getIn()
                .getHeader(org.apache.camel.component.paho.PahoConstants.CAMEL_PAHO_OVERRIDE_TOPIC),
                "the telemetry publish is not redirected");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"response/abc\"",                              // the retired shared topic
        "\"response/acme/other-managed/a-1\"",           // another edge's
        "\"response/other/acme-managed/a-1\"",
        "\"response/acme/acme-managed/#\"",              // a wildcard
        "\"response/acme/acme-managed/+\"",
        "\"response/acme/acme-managed/a-1?brokerUrl=tcp://evil:1883\"",   // would be endpoint options in a built URI
        "\"response/acme/acme-managed/a-1&brokerUrl=tcp://evil:1883\"",
        "\"response/acme/acme-managed/a%3FbrokerUrl=x\"",
        "\"response/acme/acme-managed/a 1\"",
        "\"telemetry/acme/acme-managed/route-results\"", // not a reply topic
        "123",                                           // not a string
        "null",
        "\"\"",
    })
    void anyOtherTopicIsNotAnsweredOnButTelemetryStillGetsTheResult(String responseTopic) throws Exception {
        adviseAll();
        for (String[] r : ROUTES) {
            mock(r[1] + "-result").expectedMessageCount(1);
            mock(r[1] + "-reply").expectedMessageCount(0);
            template.sendBody("direct:" + r[0], command(r[0], responseTopic));
            mock(r[1] + "-result").assertIsSatisfied(5000);
            mock(r[1] + "-reply").assertIsSatisfied(500);
        }
    }

    @Test
    void withNoReplyTopicOnlyTelemetryGetsTheResult() throws Exception {
        adviseAll();
        for (String[] r : ROUTES) {
            mock(r[1] + "-result").expectedMessageCount(1);
            mock(r[1] + "-reply").expectedMessageCount(0);
            template.sendBody("direct:" + r[0], command(r[0], null));
            mock(r[1] + "-result").assertIsSatisfied(5000);
            mock(r[1] + "-reply").assertIsSatisfied(500);
        }
    }

    @Test
    void theWriteBackAndRouteResultsKeepTheirFieldsOnTheReplyTopic() throws Exception {
        adviseAll();
        mock("rest-adapter-acme-reply").expectedMessageCount(1);
        template.sendBody("direct:write-back", command("write-back", "\"" + OWN + "\""));
        mock("rest-adapter-acme-reply").assertIsSatisfied(5000);
        String json = mock("rest-adapter-acme-reply").getExchanges().get(0).getIn().getBody(String.class);
        assertTrue(json.contains("\"action_id\":\"a-1\"") && json.contains("\"status\":\"error\""), json);
        assertTrue(json.contains("\"tenant_id\":\"acme\"") && json.contains("\"controller_id\":\"acme-managed\""), json);
    }

    @Test
    void noRouteBuildsAnEndpointUriFromACommand() throws Exception {
        // a dynamic endpoint (toD / recipientList / enrich) would read options out of whatever a command put in the
        // topic; the reply goes through a fixed endpoint with the topic as a header (PahoConstants.CAMEL_PAHO_OVERRIDE_TOPIC)
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("src", "main", "java", "com", "metafore", "edge", "route"))) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = java.nio.file.Files.readString(file);
                for (String forbidden : new String[] {".toD(", ".recipientList(", ".enrich(", ".pollEnrich("}) {
                    assertFalse(code.contains(forbidden), file.getFileName() + " uses " + forbidden);
                }
            }
        }
    }
}
