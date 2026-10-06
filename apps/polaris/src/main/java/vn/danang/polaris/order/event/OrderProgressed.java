package vn.danang.polaris.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import vn.danang.polaris.order.entity.OrderStatus;

/**
 * Domain event (Order Context internal): the assigned partner moved the order along its shipment, so it became
 * {@code PARCELED}, {@code DELIVERING} or {@code DELIVERED}. Registered by the matching
 * {@link vn.danang.polaris.order.entity.Order} method and published when the order is saved. An immutable snapshot.
 */
public record OrderProgressed(
        String orderNumber,
        Instant occurredAt,
        OrderStatus status,
        String partnerId,
        Long customerId,
        String customerName,
        String customerEmail,
        List<OrderPlaced.Line> lines,
        BigDecimal totalAmount) {

    public OrderProgressed {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(partnerId, "partnerId");
        Objects.requireNonNull(totalAmount, "totalAmount");
        lines = List.copyOf(lines);
    }
}
