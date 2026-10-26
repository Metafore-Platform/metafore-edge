package com.metafore.edge.log;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The arrival line names a command by its keys and never prints a value (Compass 03b23c26). */
class CommandLogTest {

    private static final String PASS = "p4ss-w0rd-CANARY";
    private static final String USER = "svc_canary_user";
    private static final String HOST = "db.canary.internal";
    private static final String PERSON = "Jane-Roe-CANARY";
    private static final String SQL = "SELECT ssn-CANARY FROM patients";
    private static final String URL = "https://canary.example/hook?x=1";

    private static final String COMMAND = "{\"action\":\"execute\",\"route_id\":\"r1\",\"parameters\":{"
        + "\"db_host\":\"" + HOST + "\",\"db_user\":\"" + USER + "\",\"db_pass\":\"" + PASS + "\","
        + "\"operation\":\"create\",\"table_name\":\"patients\","
        + "\"values\":{\"patient_name\":\"" + PERSON + "\",\"dob\":\"1980-01-01\"},"
        + "\"sql\":\"" + SQL + "\",\"url\":\"" + URL + "\"}}";

    private static List<String> written(java.util.function.Consumer<org.slf4j.Logger> action) {
        List<String> written = new ArrayList<>();
        org.slf4j.Logger log = (org.slf4j.Logger) Proxy.newProxyInstance(
            CommandLogTest.class.getClassLoader(), new Class<?>[] {org.slf4j.Logger.class},
            (proxy, method, args) -> {
                if (args != null) {
                    for (Object arg : args) {
                        written.add(String.valueOf(arg));
                    }
                }
                return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
            });
        action.accept(log);
        return written;
    }

    @Test
    void aCommandWithACredentialAndCustomerDataLogsNoValueAtAll() {
        List<String> parts = written(log -> CommandLog.received(log, "Route command received on tenant=t1", COMMAND));
        assertFalse(parts.isEmpty());
        for (String part : parts) {
            for (String value : List.of(PASS, USER, HOST, PERSON, SQL, URL, "1980-01-01", "patients")) {
                assertFalse(part.contains(value), "'" + value + "' must not be logged: " + part);
            }
        }
    }

    @Test
    void theLineNamesTheActionTheRouteAndTheKeys() {
        String line = CommandLog.shape(COMMAND);
        assertEquals("action=execute route_id=r1 keys=[action, parameters, route_id] "
            + "parameter_keys=[db_host, db_pass, db_user, operation, sql, table_name, url, values]", line);
    }

    @Test
    void aWriteBackCommandIsLoggedTheSameWay() {
        String body = "{\"action_id\":\"a1\",\"url\":\"" + URL + "\",\"http_method\":\"POST\","
            + "\"http_body\":\"{\\\"name\\\":\\\"" + PERSON + "\\\"}\",\"http_headers\":{\"Authorization\":\"Bearer " + PASS + "\"}}";
        List<String> parts = written(log -> CommandLog.received(log, "Write-back command received", body));
        for (String part : parts) {
            assertFalse(part.contains(PERSON) || part.contains(PASS) || part.contains("canary.example"), part);
        }
        assertTrue(parts.stream().anyMatch(p -> p.contains("http_body") && p.contains("http_headers")), parts.toString());
    }

    @Test
    void aHostileKeyOrActionCannotAddLineBreaksOrLengthToTheLog() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("a\nforged log line", "x");
        params.put("k".repeat(500), "y");
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", "exec\nute");
        cmd.put("route_id", "r".repeat(500));
        cmd.put("parameters", params);
        String line = CommandLog.shape(toJson(cmd));
        assertFalse(line.contains("\n") || line.contains("\r"), line);
        assertTrue(line.contains("action=<not a plain name> route_id=<not a plain name>"), line);
        assertTrue(line.contains("parameter_keys=[<odd key>, <odd key>]"), line);
        assertTrue(line.length() < 300, line);
    }

    @Test
    void aHugeNumberOfKeysIsCapped() {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            params.put(String.format("key_%03d", i), "v");
        }
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("parameters", params);
        String line = CommandLog.shape(toJson(cmd));
        assertTrue(line.contains("+150 more"), line);
        assertTrue(line.length() < 1200, "" + line.length());
    }

    @Test
    void textThatIsNotAJsonObjectIsReducedToItsLength() {
        String truncated = "{\"db_pass\": \"" + PASS.substring(0, 8);
        assertEquals("<not readable JSON, " + truncated.length() + " chars>", CommandLog.shape(truncated));
        String array = "[\"" + PASS.substring(0, 8) + "\"]";
        assertEquals("<not a JSON object, " + array.length() + " chars>", CommandLog.shape(array));
        assertEquals("<not a JSON object, 8 chars>", CommandLog.shape("\"secret\""));
        assertEquals("null", CommandLog.shape(null));
        assertEquals("", CommandLog.shape("  "));
    }

    @Test
    void aMapIsNamedByItsKeysOnly() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("uri", "sftp://" + USER + ":" + PASS + "@" + HOST + "/in");
        cmd.put("delay_ms", 5);
        String line = CommandLog.keys(cmd);
        assertEquals("[delay_ms, uri]", line);
        assertEquals("null", CommandLog.keys(null));
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
