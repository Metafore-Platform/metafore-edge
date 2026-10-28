package com.metafore.edge.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.topic.TopicBuilder;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit5.CamelTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compass 268f2e11 — each of the four typed refusals, sent through the real route, publishes one {@code edge_refusal}
 * event on the events topic as well as its route result; it is valid against the schema pinned from metafore-api, names
 * the route and the code, and carries nothing the command sent. A command that is not refused publishes no event.
 */
class RefusalEventRouteTest extends CamelTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EdgeConfig config = EdgeConfig.from(Map.of(
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
            }
        };
    }

    private void advise() throws Exception {
        AdviceWith.adviceWith(context, "route-executor-acme", a -> {
            a.replaceFromWith("direct:routes");
            a.weaveById("route-result-publish").replace().to("mock:result");
            a.weaveById("route-reply-publish").replace().to("mock:reply");
            a.weaveById("refusal-event-publish").replace().to("mock:event");
        });
        context.start();
    }

    private static JsonSchema schema() throws Exception {
        try (InputStream in = RefusalEventRouteTest.class.getResourceAsStream("/schemas/edge_refusal.schema.json")) {
            assertNotNull(in, "missing /schemas/edge_refusal.schema.json (scripts/sync-contracts.sh)");
            ObjectNode node = (ObjectNode) MAPPER.readTree(in);
            node.remove("$id"); // not a URI; strict validation would try to resolve it
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
        }
    }

    private static final String SHELL =
        "{\"action\":\"execute\",\"route_id\":\"r-shell\",\"parameters\":{\"shell_command\":\"cat /etc/SECRET-SHELL\"}}";
    private static final String YAML =
        "{\"action\":\"deploy\",\"route_id\":\"r-yaml\",\"route_yaml\":\"- from: sql:SELECT SECRET-YAML\"}";
    private static final String STDIO =
        "{\"action\":\"execute\",\"route_id\":\"r-mcp\",\"parameters\":{\"mcp_tool\":\"get_roadmap\","
        + "\"mcp_transport\":\"stdio\",\"mcp_command\":\"touch /tmp/SECRET-STDIO\",\"mcp_token\":\"SECRET-TOKEN\"}}";
    private static final String AUTH =
        "{\"action\":\"run_template\",\"route_id\":\"r-fhir\",\"template_id\":\"fhir.server.metadata\",\"parameters\":{},"
        + "\"connection\":{\"base_url\":\"https://unreachable.invalid/fhir\",\"auth_mode\":\"bearer\","
        + "\"credential_ref\":\"cred://SECRET-CRED\"}}";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "shell|r-shell|shell_not_supported", "yaml|r-yaml|unsupported_payload",
        "stdio|r-mcp|unsupported_mcp_transport", "auth|r-fhir|unsupported_connection_auth"})
    void eachRefusalPublishesOneValidKeysOnlyEvent(String which, String routeId, String code) throws Exception {
        advise();
        String command = switch (which) {
            case "shell" -> SHELL; case "yaml" -> YAML; case "stdio" -> STDIO; default -> AUTH;
        };
        MockEndpoint event = getMockEndpoint("mock:event");
        MockEndpoint result = getMockEndpoint("mock:result");
        event.expectedMessageCount(1);
        result.expectedMessageCount(1);
        template.sendBody("direct:routes", command);
        event.assertIsSatisfied(5000);
        result.assertIsSatisfied(5000);

        String body = event.getExchanges().get(0).getIn().getBody(String.class);
        JsonNode node = MAPPER.readTree(body);
        assertTrue(schema().validate(node).isEmpty(), "valid against the pinned schema: " + schema().validate(node));
        assertEquals("edge_refusal", node.get("kind").asText());
        assertEquals(code, node.get("refusal_code").asText());
        assertEquals(routeId, node.get("route_id").asText());
        assertEquals("acme", node.get("tenant_id").asText());
        assertEquals("acme-managed", node.get("controller_id").asText());
        assertEquals(code, MAPPER.readTree(result.getExchanges().get(0).getIn().getBody(String.class)).get("error").asText(),
            "the event is for the same refusal the route result carries");
        assertFalse(body.contains("SECRET"), "no command value is in the event: " + body);
    }

    @Test
    void aCommandThatIsNotRefusedPublishesNoEvent() throws Exception {
        advise();
        MockEndpoint event = getMockEndpoint("mock:event");
        MockEndpoint result = getMockEndpoint("mock:result");
        event.expectedMessageCount(0);
        result.expectedMessageCount(1);
        template.sendBody("direct:routes", "{\"action\":\"remove\",\"route_id\":\"r-1\"}");
        result.assertIsSatisfied(5000);
        event.assertIsSatisfied(300);
    }

    @Test
    void aRefusalOfAnotherKindOfErrorPublishesNoEvent() {
        assertNull(RefusalEvent.from(config, topics, Map.of("error", "invalid_connection", "route_id", "r")));
        assertNull(RefusalEvent.from(config, topics, Map.of("status", "success")));
        assertNull(RefusalEvent.json(config, topics, null));
    }
}
