package vn.danang.polaris.assistant.entity;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of an {@link OrderDraft}'s price snapshot, stored in the draft's {@code items} JSON
 * column. {@code unitPrice} is the live price quoted at staging time; confirmation sends it to
 * Order Management as the expected price (D2: reject if it changed).
 */
public record DraftLine(String sku, String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    public DraftLine {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("Draft line sku must not be blank.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Draft line quantity must be positive.");
        }
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        if (lineTotal == null) {
            lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public static DraftLine of(String sku, String name, int quantity, BigDecimal unitPrice) {
        return new DraftLine(sku, name, quantity, unitPrice, null);
    }
}
