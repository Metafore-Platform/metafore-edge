package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compass f615b0a7 (adr-060, adr-096) — the edge loads no route code: a command carrying route_yaml is refused. */
class RouteYamlRefusalTest {

    private static final EdgeConfig CFG = EdgeConfig.from(Collections.emptyMap());
    private static final String YAML = "- route:\n    id: r\n    steps:\n      - to: \"sql:SELECT secret FROM t\"\n";

    private static Map<String, Object> command(String action) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", action);
        cmd.put("route_id", "r-1");
        return cmd;
    }

    @Test
    void aTopLevelRouteYamlIsRefusedWithATypedEnvelope() {
        Map<String, Object> cmd = command("deploy");
        cmd.put("route_yaml", YAML);
        Map<String, Object> envelope = RouteYamlRefusal.check(CFG, "deploy", "r-1", cmd);
        assertNotNull(envelope);
        assertEquals("rejected", envelope.get("status"));
        assertEquals("deploy", envelope.get("action"));
        assertEquals("unsupported_payload", envelope.get("error"));
        assertTrue(String.valueOf(envelope.get("error_details")).contains("route_yaml"));
    }

    @Test
    void aRouteYamlInTheParametersIsRefusedToo() {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "read");
        params.put("route_yaml", YAML);
        Map<String, Object> cmd = command("execute");
        cmd.put("parameters", params);
        assertNotNull(RouteYamlRefusal.check(CFG, "execute", "r-1", cmd));
    }

    @Test
    void anyActionAndAnyValueIsRefused() {
        for (String action : new String[] {"deploy", "execute", "remove", "connect_and_ping", "run_template"}) {
            for (Object value : new Object[] {YAML, "", null, 7, new HashMap<>()}) {
                Map<String, Object> cmd = command(action);
                cmd.put("route_yaml", value);
                assertNotNull(RouteYamlRefusal.check(CFG, action, "r-1", cmd), action + " with " + value);
            }
        }
    }

    @Test
    void aQueryInsideTheYamlIsNeverRead() {
        // The edge once pulled a `query:` out of the YAML to classify the payload (and answered LEGACY or MISSING);
        // now the command is refused before any payload is classified.
        Map<String, Object> cmd = command("execute");
        cmd.put("route_yaml", "name: t\nquery: SELECT 1\n");
        Map<String, Object> envelope = RouteYamlRefusal.check(CFG, "execute", "r-1", cmd);
        assertNotNull(envelope);
        assertEquals("unsupported_payload", envelope.get("error"));
    }

    @Test
    void aCommandWithoutRouteYamlIsLeftAlone() {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "read");
        Map<String, Object> cmd = command("execute");
        cmd.put("parameters", params);
        assertNull(RouteYamlRefusal.check(CFG, "execute", "r-1", cmd));
        assertNull(RouteYamlRefusal.check(CFG, "remove", "r-1", command("remove")));
    }

    @Test
    void theRefusalIsLoggedWithoutTheYamlAndWithoutLineBreaks() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Map<String, Object> cmd = command("deploy");
            cmd.put("route_yaml", YAML);
            RouteYamlRefusal.check(CFG, "dep\nloy", "route\r\none", cmd);
        } finally {
            System.setErr(original);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Refused a route command that carries route_yaml"), log);
        assertTrue(log.contains("action=dep_loy") && log.contains("route_id=route__one"), log);
        assertFalse(log.contains("secret"), "the YAML is not logged: " + log);
    }
}
