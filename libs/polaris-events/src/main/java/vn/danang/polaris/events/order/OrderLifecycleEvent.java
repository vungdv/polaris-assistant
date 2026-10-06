package vn.danang.polaris.events.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Payload of every {@code vn.danang.polaris.order.*.v1} event (EM-002 §4.2).
 *
 * <p>Money is serialized as a JSON string. {@code assignedPartner} is {@code null} until a partner
 * has claimed the order (Δ4). Unknown fields are ignored so consumers tolerate additive changes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderLifecycleEvent(
        String orderNumber,
        OrderMilestone status,
        Instant occurredAt,
        Customer customer,
        List<Item> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        String currency,
        String assignedPartner) {

    public OrderLifecycleEvent {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        items = items == null ? List.of() : List.copyOf(items);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(Long id, String name, String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String sku,
            String name,
            int quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice) {
    }
}
