package vn.danang.polaris.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Domain event (Order Context internal): an order was placed. Registered by
 * {@link vn.danang.polaris.order.entity.Order#place} and published when the order is saved.
 * It is an immutable snapshot taken at the transition, so translating it never reads lazy state.
 */
public record OrderPlaced(
        String orderNumber,
        Instant occurredAt,
        Long customerId,
        String customerName,
        String customerEmail,
        List<Line> lines,
        BigDecimal totalAmount) {

    public OrderPlaced {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(totalAmount, "totalAmount");
        lines = List.copyOf(lines);
    }

    /** One placed line, priced at placement. */
    public record Line(String sku, String name, int quantity, BigDecimal unitPrice) {
    }
}
