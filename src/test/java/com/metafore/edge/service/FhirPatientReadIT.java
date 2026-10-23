package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task 828b1be6 — Integration test for FhirTemplateExecutor against the public HAPI FHIR R4 sandbox.
 *
 * <p>Uses the well-known {@code Patient/example} fixture that HAPI keeps alive.
 * The test does NOT silence failures if HAPI is unreachable: a ConnectException or
 * timeout propagates as a test error, which is the intended signal that the network
 * or server is unavailable.
 *
 * <p>No PHI is used or asserted — only the HAPI-owned demo resource.
 */
class FhirPatientReadIT {

    private static final String HAPI_BASE = "https://hapi.fhir.org/baseR4";
    private static final Map<String, Object> CONNECTION = Map.of("base_url", HAPI_BASE);

    @Test
    void patientExampleIsReachableAndReturnsPatientResource() throws Exception {
        FhirTemplateExecutor executor = new FhirTemplateExecutor();

        Map<String, Object> result = executor.execute(
            "fhir.patient.read",
            CONNECTION,
            Map.of("patient_id", "example")
        );

        assertEquals("success", result.get("status"),
            "HAPI fhir.patient.read must succeed: " + result.get("error_details"));
        assertEquals("run_template", result.get("action"));
        assertEquals("fhir.patient.read", result.get("template_id"));
        assertTrue(((Number) result.get("row_count")).intValue() >= 1,
            "row_count must be at least 1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get("data");
        assertNotNull(data, "data must be present");
        assertFalse(data.isEmpty(), "data must contain at least one resource");

        Map<String, Object> resource = data.get(0);
        assertEquals("Patient", resource.get("resourceType"),
            "Returned resource must be a FHIR Patient");
        assertNotNull(resource.get("id"),
            "Patient resource must have an id field");
    }
}
