package vn.danang.polaris.web.exception;

import java.math.BigDecimal;
import java.util.List;

/**
 * Thrown when an order line's live unit price no longer matches the price the caller confirmed
 * ({@code expectedUnitPrice}), checked under row lock just before the order commits (FR-14, decision D2:
 * reject rather than silently charge a different total). Translated to 409 {@link #TYPE}.
 */
public class PriceChangedException extends RuntimeException {

    public static final String TYPE = "https://polaris.local/errors/price-changed";

    /** One line whose live price differs from the price the caller expected. */
    public record ChangedLine(String sku, BigDecimal expectedUnitPrice, BigDecimal currentUnitPrice) {}

    private final List<ChangedLine> changedLines;

    public PriceChangedException(List<ChangedLine> changedLines) {
        super(buildMessage(changedLines));
        this.changedLines = List.copyOf(changedLines);
    }

    public List<ChangedLine> getChangedLines() {
        return changedLines;
    }

    private static String buildMessage(List<ChangedLine> lines) {
        StringBuilder sb = new StringBuilder("The price changed for ")
                .append(lines.size()).append(lines.size() == 1 ? " item" : " items").append(":");
        for (ChangedLine line : lines) {
            sb.append(String.format(" '%s' expected %s, now %s;",
                    line.sku(), line.expectedUnitPrice(), line.currentUnitPrice()));
        }
        sb.setLength(sb.length() - 1);
        return sb.append('.').toString();
    }
}
