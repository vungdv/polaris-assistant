package vn.danang.polaris.order.shipment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import vn.danang.polaris.events.fulfilment.FulfilmentEvents;

/**
 * Order's consumer of the Fulfilment shipments topic (F3). Fulfilment owns the topic; Order only reads it. Order owns
 * the dead-letter topic {@code <topic>.DLT} its unprocessable records are moved to (messaging-stability S2).
 */
@ConfigurationProperties("polaris.order.shipment-listener")
public record ShipmentListenerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue(FulfilmentEvents.DESTINATION) String topic,
        @DefaultValue("order.shipments") String groupId,
        /** Retries after the first retryable failure, before the record is dead-lettered. */
        @DefaultValue("3") int retries,
        @DefaultValue("500ms") java.time.Duration backoffInitial,
        @DefaultValue("3") int dltPartitions,
        @DefaultValue("3") short dltReplicas,
        @DefaultValue("2") int dltMinInsyncReplicas) {

    /** The dead-letter topic: Spring Kafka's {@code <topic>.DLT} naming convention. */
    public String dltTopic() {
        return topic + ".DLT";
    }
}
