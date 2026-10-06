package vn.danang.polaris.fulfilment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.kafka.KafkaMessageFactory;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;

/**
 * Sends one shipment event as a binary-mode CloudEvent (ADR-0019 §4): topic {@value FulfilmentEvents#DESTINATION},
 * key = order number, value = the JSON payload. Sent directly, not through an outbox, and never retried: a lost
 * report only stalls a demo order (TR-F4). The template has observation on, so the send is a producer span in the
 * current trace and the record carries its {@code traceparent}.
 */
class ShipmentPublisher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentPublisher.class);

    private final KafkaTemplate<String, byte[]> template;
    private final JsonMapper mapper;
    private final String topic;
    private final FulfilmentMetrics metrics;

    ShipmentPublisher(KafkaTemplate<String, byte[]> template, JsonMapper mapper, String topic, FulfilmentMetrics metrics) {
        this.template = template;
        this.mapper = mapper;
        this.topic = topic;
        this.metrics = metrics;
    }

    void publish(String orderNumber, String partnerId, ShipmentStep step, Instant now) {
        var payload = new ShipmentEvent(orderNumber, "SHP-" + orderNumber, partnerId, step, now);
        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withType(step.type())
                .withSource(URI.create(FulfilmentEvents.SOURCE))
                .withTime(now.atOffset(ZoneOffset.UTC))
                .withDataContentType("application/json")
                .withData(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8))
                .build();
        template.send(KafkaMessageFactory.createWriter(topic, orderNumber).writeBinary(event))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        metrics.shipment(partnerId, step, false);
                        log.error("Shipment event not sent orderNumber={} partnerId={} ce_id={} ce_type={}",
                                orderNumber, partnerId, event.getId(), event.getType(), error);
                    }
                    else {
                        metrics.shipment(partnerId, step, true);
                        log.info("Shipment event sent orderNumber={} partnerId={} ce_id={} ce_type={}",
                                orderNumber, partnerId, event.getId(), event.getType());
                    }
                });
    }
}
