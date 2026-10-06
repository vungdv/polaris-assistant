package vn.danang.polaris.assistant.tools.local;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Assistant-side view of the {@code quote_order} MCP tool's {@code structuredContent} (published by Order
 * Management as the tool's {@code outputSchema}). Money stays {@link BigDecimal} end to end.
 *
 * @param orderable   true when every line can be ordered as requested right now
 * @param lines       one entry per distinct requested SKU
 * @param totalAmount sum of the orderable lines at live prices
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderQuote(boolean orderable, List<Line> lines, BigDecimal totalAmount) {

    public OrderQuote {
        lines = lines != null ? List.copyOf(lines) : List.of();
    }

    /**
     * @param problem {@code not_found}, {@code inactive}, {@code insufficient_stock}, or null if the line is orderable
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(
            String sku,
            String name,
            Integer requestedQuantity,
            BigDecimal unitPrice,
            Integer availableQuantity,
            BigDecimal lineTotal,
            String problem) {

        public boolean isOrderable() {
            return problem == null && unitPrice != null && requestedQuantity != null && requestedQuantity > 0;
        }
    }

    /** True only if Order Management says so <em>and</em> every line is complete enough to snapshot. */
    public boolean isStageable() {
        return orderable && !lines.isEmpty() && lines.stream().allMatch(Line::isOrderable);
    }
}
