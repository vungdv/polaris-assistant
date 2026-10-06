package vn.danang.polaris.order.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain event (Order Context internal): an order was cancelled. Registered by
 * {@link vn.danang.polaris.order.entity.Order#cancel} and published when the order is saved.
 *
 * <p>PRD-007 defines no integration event for cancellation (EM-002 has five {@code order.*.v1} milestones),
 * so {@link OrderIntegrationEventTranslator} records nothing for it.
 */
public record OrderCancelled(String orderNumber, Instant occurredAt) {

    public OrderCancelled {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
