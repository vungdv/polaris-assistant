package vn.danang.polaris.order.shipment;

import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.order.service.ShipmentProgressService;
import vn.danang.polaris.order.service.ShipmentProgressService.Outcome;

/**
 * Reads one record of the shipments topic with the CloudEvents binding (ADR-0019 §4.1) and applies it to the order.
 * Types that are not shipment steps are ignored so new event types never break it. Every no-op is logged with its
 * reason and counted in {@code polaris.order.shipment.reports} (TR-X2); none of them throws, so nothing is retried
 * forever. Only a malformed record or an infrastructure failure throws, and the container's error handler bounds that.
 */
public class ShipmentReportHandler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentReportHandler.class);

    private final ShipmentProgressService service;
    private final JsonMapper mapper;
    private final MeterRegistry meters;

    public ShipmentReportHandler(ShipmentProgressService service, JsonMapper mapper, MeterRegistry meters) {
        this.service = service;
        this.mapper = mapper;
        this.meters = meters;
    }

    public void handle(ConsumerRecord<String, byte[]> record) {
        CloudEvent event = KafkaMessageFactory.createReader(record).toEvent();
        Optional<ShipmentStep> step = ShipmentStep.fromType(event.getType());
        if (step.isEmpty()) {
            log.debug("Ignoring event ce_id={} ce_type={}", event.getId(), event.getType());
            return;
        }
        ShipmentEvent report = mapper.readValue(event.getData().toBytes(), ShipmentEvent.class);
        if (report.step() != step.get()) {
            count(step.get(), "ignored", "type_mismatch");
            log.warn("Shipment report ignored orderNumber={} ce_id={} ce_type={} reason=type_mismatch step={}",
                    report.orderNumber(), event.getId(), event.getType(), report.step());
            return;
        }
        Outcome outcome = service.apply(report);
        if (outcome.applied()) {
            count(report.step(), "applied", outcome.decision().next().name().toLowerCase());
            log.info("Shipment report applied orderNumber={} ce_id={} step={} partnerId={} status={}",
                    report.orderNumber(), event.getId(), report.step(), report.partnerId(), outcome.decision().next());
        } else {
            String reason = outcome.decision().reason().tag();
            count(report.step(), "ignored", reason);
            log.warn("Shipment report ignored orderNumber={} ce_id={} step={} partnerId={} reason={}",
                    report.orderNumber(), event.getId(), report.step(), report.partnerId(), reason);
        }
    }

    private void count(ShipmentStep step, String outcome, String reason) {
        meters.counter("polaris.order.shipment.reports",
                "step", step.name().toLowerCase(), "outcome", outcome, "reason", reason).increment();
    }
}
