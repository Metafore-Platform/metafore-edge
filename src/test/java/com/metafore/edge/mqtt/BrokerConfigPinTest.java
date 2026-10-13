package com.metafore.edge.mqtt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests/mtls/emqx.conf and acl.conf are the platform chart's own broker configuration, copied by
 * scripts/sync-broker-config.sh and pinned in tests/mtls/PIN. The mTLS integration test is only worth something while the
 * broker it runs is the platform's, so a hand edit (or a stale pin) fails here, offline, long before the broker starts.
 */
class BrokerConfigPinTest {

    private static final Path DIR = Path.of("tests", "mtls");

    private static Map<String, String> pin() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readString(DIR.resolve("PIN")).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    @Test
    void theBrokerConfigIsTheBytesTheMetaforeApiCommitPins() throws Exception {
        Map<String, String> pin = pin();
        assertEquals("Metafore-Platform/metafore-api", pin.get("source_repo"));
        assertTrue(pin.get("source_commit").matches("[0-9a-f]{40}"), "a full sha: " + pin);
        for (String file : new String[] {"emqx.conf", "acl.conf"}) {
            String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(DIR.resolve(file))));
            assertEquals(pin.get(file), actual, file + " differs from the pinned chart file: re-pin with "
                + "scripts/sync-broker-config.sh <metafore-api sha>, never edit it by hand");
        }
    }

    @Test
    void theOverlayKeepsTheChartsCertificateListenerSettings() throws Exception {
        String overlay = Files.readString(DIR.resolve("mtls.overlay.conf"), StandardCharsets.UTF_8);
        for (String must : new String[] {
            "peer_cert_as_username = cn", "zone = mtls", "enable_authn = false", "verify = verify_peer",
            "fail_if_no_peer_cert = true", "bind = \"0.0.0.0:8883\"", "/opt/emqx/etc/api-key/api_keys.txt"}) {
            assertTrue(overlay.contains(must), "mtls.overlay.conf lost: " + must);
        }
    }

    @Test
    void theTopicRulesKeyOnTheUsernameTheCertificateNames() throws Exception {
        String acl = Files.readString(DIR.resolve("acl.conf"));
        assertTrue(acl.contains("control/${username}/#") && acl.contains("telemetry/${username}/#")
            && acl.contains("response/${username}/#") && acl.contains("{deny, all}"), acl);
    }
}
