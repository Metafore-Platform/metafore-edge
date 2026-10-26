package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Compass 87bcb517 — a run_template that asks for authentication is refused before any call, never sent without it. */
class FhirTemplateAuthRefusalTest {

    private static Map<String, Object> connection(Object authMode, Object credentialRef) {
        Map<String, Object> c = new HashMap<>();
        c.put("base_url", "https://hapi.fhir.org/baseR4");
        if (authMode != null) {
            c.put("auth_mode", authMode);
        }
        if (credentialRef != null) {
            c.put("credential_ref", credentialRef);
        }
        return c;
    }

    @Test
    void everyAuthModeButNoneIsRefusedWithoutAnyCall() throws Exception {
        for (Object mode : List.of("basic", "api_key", "bearer", "oauth2_client_credentials", "mtls",
                "NONE", "None", " none", "", "garbage", 5, true)) {
            FhirHttpSend sender = mock(FhirHttpSend.class);
            FhirTemplateExecutor executor = new FhirTemplateExecutor(sender);
            assertThrows(FhirTemplateExecutor.UnsupportedAuthException.class,
                () -> executor.execute("fhir.server.metadata", connection(mode, null), Map.of()), "auth_mode=" + mode);
            verify(sender, never()).send(any());
        }
    }

    @Test
    void aCredentialRefIsRefusedWhateverTheModeAndWithoutAnyCall() throws Exception {
        for (Object mode : new Object[] {null, "none", "bearer"}) {
            FhirHttpSend sender = mock(FhirHttpSend.class);
            FhirTemplateExecutor executor = new FhirTemplateExecutor(sender);
            assertThrows(FhirTemplateExecutor.UnsupportedAuthException.class,
                () -> executor.execute("fhir.server.metadata", connection(mode, "cred://phluence-fhir"), Map.of()),
                "auth_mode=" + mode);
            verify(sender, never()).send(any());
        }
    }

    @Test
    void theRefusalNeverEchoesTheCredentialNameOrAnOddMode() {
        FhirTemplateExecutor executor = new FhirTemplateExecutor(mock(FhirHttpSend.class));
        var byRef = assertThrows(FhirTemplateExecutor.UnsupportedAuthException.class,
            () -> executor.execute("fhir.server.metadata", connection(null, "cred://secret-name-CANARY"), Map.of()));
        assertFalse(byRef.getMessage().contains("secret-name-CANARY"), byRef.getMessage());
        assertTrue(byRef.getMessage().contains("credential_ref"), byRef.getMessage());
        var byOddMode = assertThrows(FhirTemplateExecutor.UnsupportedAuthException.class,
            () -> executor.execute("fhir.server.metadata", connection("x\nforged line CANARY", null), Map.of()));
        assertFalse(byOddMode.getMessage().contains("CANARY") || byOddMode.getMessage().contains("\n"),
            byOddMode.getMessage());
        var byKnownMode = assertThrows(FhirTemplateExecutor.UnsupportedAuthException.class,
            () -> executor.execute("fhir.server.metadata", connection("bearer", null), Map.of()));
        assertTrue(byKnownMode.getMessage().contains("'bearer'"), byKnownMode.getMessage());
    }

    @Test
    void noAuthAtAllStillRuns() throws Exception {
        for (Object mode : new Object[] {null, "none"}) {
            FhirHttpSend sender = mock(FhirHttpSend.class);
            when(sender.send(any())).thenReturn(fakeOk());
            FhirTemplateExecutor executor = new FhirTemplateExecutor(sender);
            Map<String, Object> result = executor.execute("fhir.server.metadata", connection(mode, null), Map.of());
            assertEquals("success", result.get("status"), "auth_mode=" + mode);
            verify(sender, times(1)).send(any());
        }
    }

    private static java.net.http.HttpResponse<String> fakeOk() {
        return new java.net.http.HttpResponse<>() {
            public int statusCode() { return 200; }
            public String body() { return "{\"resourceType\":\"CapabilityStatement\",\"fhirVersion\":\"4.0.1\"}"; }
            public java.net.http.HttpHeaders headers() {
                return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
            }
            public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.http.HttpRequest request() { return null; }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            public java.net.URI uri() { return java.net.URI.create("https://hapi.fhir.org/baseR4"); }
            public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }
}
