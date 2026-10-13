package com.metafore.edge.route;

import com.metafore.edge.service.FhirTemplateExecutor;
import org.apache.camel.builder.RouteBuilder;

import java.util.Map;

/**
 * task 828b1be6 — Camel RouteBuilder wrapper for FHIR template execution.
 *
 * <p>Exposes two {@code direct:} routes that delegate to
 * {@link FhirTemplateExecutor}, making FHIR dispatches visible in
 * the Camel context for tracing and metrics:
 * <ul>
 *   <li>{@code direct:fhir.patient.read}</li>
 *   <li>{@code direct:fhir.patient.search}</li>
 * </ul>
 *
 * <p>Both routes read {@code fhir_connection} and {@code fhir_params}
 * from the Camel exchange headers. {@link com.metafore.edge.route.RouteExecutorRoute}
 * calls {@link FhirTemplateExecutor} directly; these routes exist so that
 * external callers (tests, other routes) can reach FHIR dispatch via
 * the Camel context.
 */
public class FhirTemplateRoutes extends RouteBuilder {

    private final FhirTemplateExecutor executor;

    public FhirTemplateRoutes(FhirTemplateExecutor executor) {
        this.executor = executor;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void configure() {
        from("direct:fhir.patient.read")
            .routeId("fhir-patient-read")
            .process(exchange -> {
                Map<String, Object> connection =
                    exchange.getIn().getHeader("fhir_connection", Map.class);
                Map<String, Object> params =
                    exchange.getIn().getHeader("fhir_params", Map.class);
                exchange.getIn().setBody(
                    executor.execute("fhir.patient.read", connection, params));
            });

        from("direct:fhir.patient.search")
            .routeId("fhir-patient-search")
            .process(exchange -> {
                Map<String, Object> connection =
                    exchange.getIn().getHeader("fhir_connection", Map.class);
                Map<String, Object> params =
                    exchange.getIn().getHeader("fhir_params", Map.class);
                exchange.getIn().setBody(
                    executor.execute("fhir.patient.search", connection, params));
            });
    }
}
