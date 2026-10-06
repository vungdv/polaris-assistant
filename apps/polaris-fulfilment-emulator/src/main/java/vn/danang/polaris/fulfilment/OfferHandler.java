package vn.danang.polaris.fulfilment;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.KafkaMessageFactory;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;

/**
 * Reads one record of the order lifecycle topic with the CloudEvents binding (ADR-0019 §4.1) and offers
 * {@code order.placed.v1} orders to a partner. Every other type is ignored (DEBUG) so new event types never break it.
 */
class OfferHandler {

    private static final Logger log = LoggerFactory.getLogger(OfferHandler.class);

    private final JsonMapper mapper;
    private final FulfilmentMetrics metrics;

    OfferHandler(JsonMapper mapper, FulfilmentMetrics metrics) {
        this.mapper = mapper;
        this.metrics = metrics;
    }

    void handle(PartnerAgent partner, ConsumerRecord<String, byte[]> record) {
        CloudEvent event = KafkaMessageFactory.createReader(record).toEvent();
        if (!OrderEvents.PLACED_V1.equals(event.getType())) {
            metrics.offer(partner.partnerId(), "ignored");
            log.debug("Ignoring event partnerId={} ce_id={} ce_type={}", partner.partnerId(), event.getId(), event.getType());
            return;
        }
        OrderLifecycleEvent placed = mapper.readValue(event.getData().toBytes(), OrderLifecycleEvent.class);
        metrics.offer(partner.partnerId(), "received");
        log.info("Offer ce_id={} ce_type={} orderNumber={} partnerId={}", event.getId(), event.getType(),
                placed.orderNumber(), partner.partnerId());
        partner.onOffer(placed.orderNumber());
    }
}
