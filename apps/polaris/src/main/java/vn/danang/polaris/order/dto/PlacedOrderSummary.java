package vn.danang.polaris.order.dto;

import java.math.BigDecimal;

/**
 * Read-only projection of a {@code PLACED} order: who ordered it, how much it is worth and how many product units it
 * holds (sum of item quantities).
 */
public record PlacedOrderSummary(
    String customerName,
    BigDecimal totalAmount,
    Long totalProductItems
) {}
