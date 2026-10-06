package vn.danang.polaris.events.fulfilment;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of every {@code vn.danang.polaris.fulfilment.shipment.*.v1} event (EM-002 §4.2).
 *
 * <p>{@code partnerId} identifies the reporting fulfilment partner and is required (Δ4).
 * Unknown fields are ignored so consumers tolerate additive changes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipmentEvent(
        String orderNumber,
        String shipmentId,
        String partnerId,
        ShipmentStep step,
        Instant occurredAt) {

    public ShipmentEvent {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(partnerId, "partnerId");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
