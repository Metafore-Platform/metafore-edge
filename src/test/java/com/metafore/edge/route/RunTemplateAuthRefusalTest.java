package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.service.DataSourceRegistry;
import com.metafore.edge.service.FhirTemplateExecutor;
import com.metafore.edge.service.McpExecutor;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compass 87bcb517 — the reply to a run_template that asks for authentication is a typed error, with the template id. */
class RunTemplateAuthRefusalTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> handle(Map<String, Object> cmd) throws Exception {
        DefaultCamelContext ctx = new DefaultCamelContext();
        EdgeConfig cfg = EdgeConfig.from(Collections.emptyMap());
        RouteExecutorRoute route = new RouteExecutorRoute(cfg, new TopicBuilder(cfg.tenantId(), cfg.controllerId()),
            new DataSourceRegistry(ctx), new McpExecutor(), new FhirTemplateExecutor());
        Method m = RouteExecutorRoute.class.getDeclaredMethod("handleRunTemplate", String.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(route, "test-route", cmd);
    }

    private static Map<String, Object> command(Map<String, Object> connection) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("template_id", "fhir.server.metadata");
        cmd.put("connection", connection);
        cmd.put("parameters", Map.of());
        return cmd;
    }

    @Test
    void aBearerConnectionIsAnsweredWithATypedErrorAndNoCall() throws Exception {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("base_url", "https://unreachable.invalid/fhir");
        connection.put("auth_mode", "bearer");
        connection.put("credential_ref", "cred://phluence-fhir");

        Map<String, Object> envelope = handle(command(connection));

        assertEquals("error", envelope.get("status"));
        assertEquals("run_template", envelope.get("action"));
        assertEquals("unsupported_connection_auth", envelope.get("error"));
        assertEquals("fhir.server.metadata", envelope.get("template_id"));
        String details = String.valueOf(envelope.get("error_details"));
        assertTrue(details.contains("auth_mode 'bearer'"), details);
        assertFalse(details.contains("phluence-fhir"), "the credential name is not echoed: " + details);
        // The host does not resolve, so a call would have come back as fhir_transport_error, not this error.
    }

    @Test
    void aCredentialRefAloneIsAnsweredTheSameWay() throws Exception {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("base_url", "https://unreachable.invalid/fhir");
        connection.put("credential_ref", "cred://x");

        Map<String, Object> envelope = handle(command(connection));

        assertEquals("unsupported_connection_auth", envelope.get("error"));
        assertEquals("fhir.server.metadata", envelope.get("template_id"));
    }
}
