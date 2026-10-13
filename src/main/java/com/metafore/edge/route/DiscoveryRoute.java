package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.message.MessageFactory;
import com.metafore.edge.service.DiscoveryService;
import com.metafore.edge.topic.ReplyTopic;
import com.metafore.edge.topic.TopicBuilder;
import org.apache.camel.builder.RouteBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class DiscoveryRoute extends RouteBuilder {

    private final EdgeConfig config;
    private final TopicBuilder topics;

    public DiscoveryRoute(EdgeConfig config, TopicBuilder topics) {
        this.config = config;
        this.topics = topics;
    }

    @Override
    public void configure() {
        // Phase 14.18 — routeIds suffixed with tenant slug so multi-
        // tenant edges instantiate one DiscoveryRoute per tenant
        // without colliding on Camel's unique-routeId requirement.
        String suffix = "-" + topics.tenantId();
        // Startup discovery (one-shot)
        from("timer:discovery" + suffix + "?repeatCount=1&delay=" + config.discoveryDelayMs())
            .routeId("discovery-startup" + suffix)
            .process(exchange -> {
                Map<String, Object> scope = DiscoveryService.defaultScope();
                Map<String, Object> capabilities =
                    DiscoveryService.execute(config.controllerId(), scope);
                exchange.getIn().setBody(MessageFactory.discoveryResult(
                    config, UUID.randomUUID().toString(), "startup", capabilities));
            })
            .marshal().json()
            .to("paho:" + topics.telemetryDiscovery()
                + "?brokerUrl=" + config.brokerUrl())
            .log("Startup discovery published");

        // On-demand discovery (from Core)
        from("paho:" + topics.controlDiscovery()
            + "?brokerUrl=" + config.brokerUrl())
            .routeId("discovery-ondemand" + suffix)
            .log("Discovery command received")
            .unmarshal().json(Map.class)
            .process(exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cmd = exchange.getIn().getBody(Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> scope = (Map<String, Object>)
                    cmd.getOrDefault("scope", DiscoveryService.defaultScope());
                String trigger = (String) cmd.getOrDefault("trigger", "remote");
                // Core's discover_and_wait subscribes to and blocks on the
                // response_topic it sent in the command
                // (response/{correlation_id}). Echo the result there so the
                // synchronous discover API / Atlas "Discover" button resolves
                // instead of always timing out. We still publish to the fixed
                // telemetry/.../discovery topic below so core's async
                // _handle_discovery keeps updating the graph. When no
                // response_topic is present (startup discovery / legacy
                // cores) only the telemetry publish happens — unchanged.
                // v2: only this edge's own per-edge topic, response/<tenant>/<controller>/<id> (topic/ReplyTopic);
                // the retired shared response/<id>, another edge's topic and anything malformed are not answered on.
                String responseTopic = ReplyTopic.forEdge(cmd.get("response_topic"), topics);
                if (responseTopic != null) {
                    exchange.getIn().setHeader("mfResponseTopic", responseTopic);
                }
                Map<String, Object> capabilities =
                    DiscoveryService.execute(config.controllerId(), scope);
                exchange.getIn().setBody(MessageFactory.discoveryResult(
                    config, UUID.randomUUID().toString(), trigger, capabilities));
            })
            .marshal().json()
            // one String the telemetry publish and the reply publish both send in full (a stream is read once)
            .convertBodyTo(String.class)
            .to("paho:" + topics.telemetryDiscovery()
                + "?brokerUrl=" + config.brokerUrl()).id("discovery-result-publish")
            // Discover correlation fix — also deliver to the command's
            // response_topic so core's discover_and_wait
            // (response/{correlation_id}) resolves instead of timing out.
            .choice()
                .when(header("mfResponseTopic").isNotNull())
                    .toD("paho:${header.mfResponseTopic}"
                        + "?brokerUrl=" + config.brokerUrl()).id("discovery-reply-publish")
            .end()
            .log("Discovery results published");
    }
}
