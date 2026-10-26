package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compass 3bb6be4e — the edge has no shell path: a command carrying shell_command is refused and logged. */
class ShellRefusalTest {

    private static final EdgeConfig CFG = EdgeConfig.from(Collections.emptyMap());

    private static Map<String, Object> command(String action, Map<String, Object> params) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("action", action);
        cmd.put("route_id", "ps-test");
        cmd.put("parameters", params);
        return cmd;
    }

    private static Map<String, Object> withShell(Object value) {
        Map<String, Object> params = new HashMap<>();
        params.put("shell_command", value);
        return params;
    }

    @Test
    void aShellCommandIsRefusedWithATypedEnvelope() {
        Map<String, Object> envelope = ShellRefusal.check(CFG, "execute", "ps-test", command("execute", withShell("df -h")));
        assertNotNull(envelope, "a command that carries shell_command must be refused");
        assertEquals("rejected", envelope.get("status"));
        assertEquals("execute", envelope.get("action"));
        assertEquals("shell_not_supported", envelope.get("error"));
        assertTrue(String.valueOf(envelope.get("error_details")).contains("shell_command"));
        assertFalse(String.valueOf(envelope).contains("df -h"), "the refusal never echoes the command");
    }

    @Test
    void itIsRefusedWhateverTheValueOrTheAction() {
        for (Object value : new Object[] {"", "   ", "systemctl status x", 5, null}) {
            for (String action : List.of("execute", "deploy", "run_template", "connect_and_ping", "remove")) {
                assertNotNull(ShellRefusal.check(CFG, action, "r", command(action, withShell(value))),
                    "value=" + value + " action=" + action);
            }
        }
    }

    @Test
    void aTopLevelShellCommandIsRefusedToo() {
        Map<String, Object> cmd = command("execute", new HashMap<>());
        cmd.put("shell_command", "cat /etc/passwd");
        assertNotNull(ShellRefusal.check(CFG, "execute", "ps-test", cmd));
    }

    @Test
    void aCommandWithoutShellCommandIsLeftAlone() {
        Map<String, Object> params = new HashMap<>();
        params.put("operation", "read");
        assertNull(ShellRefusal.check(CFG, "execute", "ps-test", command("execute", params)));
        assertNull(ShellRefusal.check(CFG, "execute", "ps-test", command("execute", new HashMap<>())));
        Map<String, Object> noParams = new HashMap<>();
        noParams.put("action", "execute");
        assertNull(ShellRefusal.check(CFG, "execute", "ps-test", noParams));
    }

    @Test
    void anExecLineInTheRouteYamlRunsNothing() {
        // The exec: fallback is gone, and so is route_yaml itself: the command is refused (RouteYamlRefusal).
        Map<String, Object> cmd = command("execute", new HashMap<>());
        cmd.put("route_yaml", "name: t\nexec: ps aux\n");
        assertNull(ShellRefusal.check(CFG, "execute", "ps-test", cmd));
        assertNotNull(RouteYamlRefusal.check(CFG, "execute", "ps-test", cmd));
    }

    @Test
    void theRefusalIsLoggedWithoutTheCommandAndWithoutLineBreaks() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            ShellRefusal.check(CFG, "exec\nute", "route\r\none", command("execute", withShell("rm -rf /secret-path")));
        } finally {
            System.setErr(original);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Refused a route command that carries shell_command"), log);
        assertTrue(log.contains("action=exec_ute") && log.contains("route_id=route__one"), log);
        assertFalse(log.contains("secret-path"), "the command text is not logged: " + log);
    }
}
