package com.metafore.edge.log;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A secret in a command, a URL or an exception message is never written to a log line (Compass 9ece454f). */
class RedactorTest {

    private static final String PASS = "p4ss-w0rd-CANARY";
    private static final String USER = "svc_canary_user";

    private static final String COMMAND = "{\"action\":\"execute\",\"route_id\":\"r1\",\"db_host\":\"db.internal\","
        + "\"db_port\":\"5432\",\"db_name\":\"t_abc\",\"db_user\":\"" + USER + "\",\"db_pass\":\"" + PASS + "\","
        + "\"sql\":\"SELECT 1\"}";

    @Test
    void aCommandKeepsItsShapeAndLosesItsCredentials() {
        String line = Redactor.json(COMMAND);
        assertFalse(line.contains(PASS), line);
        assertFalse(line.contains(USER), line);
        assertTrue(line.contains("\"db_pass\":\"***\""), line);
        assertTrue(line.contains("\"db_host\":\"db.internal\""), line);
        assertTrue(line.contains("\"sql\":\"SELECT 1\""), line);
    }

    @Test
    void everyCredentialShapedNameIsMaskedWhereverItSits() {
        String body = "{\"a\":{\"password\":\"" + PASS + "\"},\"list\":[{\"client_secret\":\"" + PASS + "\"},"
            + "{\"x\":\"y\"}],\"mcp_token\":\"" + PASS + "\",\"apiKey\":\"" + PASS + "\",\"api_key\":\"" + PASS
            + "\",\"credential_ref\":\"" + PASS + "\",\"Authorization\":\"Bearer " + PASS + "\","
            + "\"private_key\":\"" + PASS + "\",\"pwd\":\"" + PASS + "\",\"passwd\":\"" + PASS + "\","
            + "\"keyspace\":\"kept\",\"route_id\":\"kept\"}";
        String line = Redactor.json(body);
        assertFalse(line.contains(PASS), line);
        assertTrue(line.contains("\"keyspace\":\"kept\""), line);
        assertTrue(line.contains("\"route_id\":\"kept\""), line);
        assertTrue(line.contains("\"x\":\"y\""), line);
    }

    @Test
    void aSecretInsideAnOrdinaryFieldsTextIsMaskedToo() {
        String body = "{\"uri\":\"sftp://svc:" + PASS + "@files.example/in\",\"q\":\"https://h/x?api_key=" + PASS
            + "&page=2\"}";
        String line = Redactor.json(body);
        assertFalse(line.contains(PASS), line);
        assertTrue(line.contains("page=2"), line);
        assertTrue(line.contains("files.example"), line);
    }

    @Test
    void textThatIsNotAJsonObjectIsNeverPrinted() {
        assertFalse(Redactor.json("db_pass=" + PASS).contains(PASS));
        assertFalse(Redactor.json("{\"db_pass\":\"" + PASS + "\"").contains(PASS));   // truncated
        assertFalse(Redactor.json("\"" + PASS + "\"").contains(PASS));
        assertEquals("null", Redactor.json(null));
    }

    @Test
    void aMapIsPrintedWithoutItsCredentials() {
        String line = Redactor.map(Map.of("feed_id", "f1", "uri", "ftp://u:" + PASS + "@h/x", "password", PASS));
        assertFalse(line.contains(PASS), line);
        assertTrue(line.contains("f1"), line);
    }

    @Test
    void aUrlAndAnExceptionMessageLoseTheirSecrets() {
        assertEquals("https://***@h/p?q=1", Redactor.url("https://user:" + PASS + "@h/p?q=1"));
        assertFalse(Redactor.url("https://h/p?token=" + PASS + "&a=b").contains(PASS));
        assertTrue(Redactor.url("https://h/p?token=" + PASS + "&a=b").contains("a=b"));
        String message = "FATAL: password authentication failed for user \"x\" (password=" + PASS + ")";
        assertFalse(Redactor.text(message).contains(PASS));
        assertFalse(Redactor.text("jdbc:postgresql://h/db?user=u&password=" + PASS).contains(PASS));
    }

    @Test
    void aMalformedCommandsParseErrorDoesNotQuoteItsSource() {
        String broken = "{\"db_pass\":\"" + PASS + "\" \"db_user\":\"u\"}";
        IOException e = assertThrows(IOException.class, () -> Redactor.parse(broken));
        assertFalse(String.valueOf(e.getMessage()).contains(PASS), e.getMessage());
        assertFalse(e.toString().contains(PASS), e.toString());
    }

    @Test
    void aWellFormedCommandStillParsesIntoAMap() throws IOException {
        Map<String, Object> cmd = Redactor.parse(COMMAND);
        assertEquals("execute", cmd.get("action"));
        assertEquals(PASS, cmd.get("db_pass"));   // the route needs the real value; only the log is masked
    }

    // ── the line a route writes ─────────────────────────────────────

    @Test
    void theArrivalLineCarriesNoTraceOfDbPass() {
        List<String> written = new ArrayList<>();
        org.slf4j.Logger log = (org.slf4j.Logger) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {org.slf4j.Logger.class},
            (proxy, method, args) -> {
                if (args != null) {
                    for (Object arg : args) {
                        written.add(String.valueOf(arg));
                    }
                }
                return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
            });
        CommandLog.received(log, "Route command received on tenant=t1", COMMAND);
        assertFalse(written.isEmpty());
        for (String part : written) {
            assertFalse(part.contains(PASS), part);
            assertFalse(part.contains(USER), part);
        }
        assertTrue(written.stream().anyMatch(p -> p.contains("db.internal")));
    }

    // ── nothing prints a whole command around the redactor ──────────

    private static final Pattern BODY_IN_LOG = Pattern.compile(
        "\\.log\\([^;]*\\$\\{(?:in\\.)?(?:body|bodyAs|exchange|headers?)", Pattern.DOTALL);

    @Test
    void noCamelLogStepPrintsTheBodyOrHeaders() throws IOException {
        Path src = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (BODY_IN_LOG.matcher(Files.readString(file)).find()) {
                    offenders.add(file.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "a .log(...) prints the message body or headers - use CommandLog / Redactor: "
            + offenders);
    }
}
