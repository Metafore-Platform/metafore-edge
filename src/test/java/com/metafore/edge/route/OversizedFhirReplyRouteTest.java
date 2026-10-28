package com.metafore.edge.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.service.DataSourceRegistry;
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
 * Compass 85d16fea — the real route, the real FHIR executor and a local FHIR server whose Patient is far over the limit:
 * what reaches the publish is the small typed {@code reply_too_large}, never the data, and a small Patient still passes.
 */
class OversizedFhirReplyRouteTest extends CamelTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EdgeConfig config = EdgeConfig.from(Map.of(
        "CONTROLLER_ID", "acme-managed", "TENANT_ID", "acme", "BROKER_URL", "ssl://emqx:8883"));
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
                getContext().addRoutes(new RouteExecutorRoute(config, topics, new DataSourceRegistry(getContext())));
            }
        };
    }

    private String serve(int patientBytes) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fhir/Patient/", ex -> {
            byte[] body = ("{\"resourceType\":\"Patient\",\"id\":\"p1\",\"text\":\"" + "x".repeat(patientBytes) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/fhir+json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private JsonNode send(String baseUrl) throws Exception {
        AdviceWith.adviceWith(context, "route-executor-acme", a -> {
            a.replaceFromWith("direct:routes");
            a.weaveById("route-result-publish").replace().to("mock:result");
            a.weaveById("route-reply-publish").replace().to("mock:reply");
            a.weaveById("refusal-event-publish").replace().to("mock:event");
        });
        context.start();
        MockEndpoint result = getMockEndpoint("mock:result");
        result.expectedMessageCount(1);
        template.sendBody("direct:routes", "{\"action\":\"run_template\",\"route_id\":\"r-big\",\"template_id\":\"fhir.patient.read\","
            + "\"parameters\":{\"patient_id\":\"p1\"},\"connection\":{\"base_url\":\"" + baseUrl + "\"}}");
        result.assertIsSatisfied(10000);
        return MAPPER.readTree(result.getExchanges().get(0).getIn().getBody(String.class));
    }

    @Test
    void aPatientOverTheLimitIsAnsweredWithReplyTooLarge() throws Exception {
        JsonNode out = send(serve(2 * 1024 * 1024));
        assertEquals("error", out.get("status").asText());
        assertEquals("reply_too_large", out.get("error").asText());
        assertEquals("r-big", out.get("route_id").asText());
        assertEquals("fhir.patient.read", out.get("template_id").asText());
        assertFalse(out.has("data"));
    }

    @Test
    void aSmallPatientStillGetsItsData() throws Exception {
        JsonNode out = send(serve(1000));
        assertEquals("success", out.get("status").asText());
        assertEquals("p1", out.get("data").get(0).get("id").asText());
    }
}
