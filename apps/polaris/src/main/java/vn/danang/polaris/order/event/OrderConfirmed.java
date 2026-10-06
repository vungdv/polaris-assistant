package vn.danang.polaris.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Domain event (Order Context internal): a fulfilment partner claimed the order. Registered by
 * {@link vn.danang.polaris.order.entity.Order#confirm} and published when the order is saved. An immutable
 * snapshot, so translating it never reads lazy state.
 */
public record OrderConfirmed(
        String orderNumber,
        Instant occurredAt,
        String partnerId,
        Long customerId,
        String customerName,
        String customerEmail,
        List<OrderPlaced.Line> lines,
        BigDecimal totalAmount) {

    public OrderConfirmed {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(partnerId, "partnerId");
        Objects.requireNonNull(totalAmount, "totalAmount");
        lines = List.copyOf(lines);
    }
}
