package vn.danang.polaris.order.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Live stock and price verification for a prospective order (FR-14, BPMN {@code O_Verify}).
 * <p>
 * Also returned as MCP {@code structuredContent} by the {@code quote_order} tool, so field names are a
 * published contract consumed by the AI Assistant.
 */
@Schema(description = "Live stock and price verification for a prospective order")
public record QuoteResponse(
    @Schema(description = "True when every line can be ordered as requested right now")
    boolean orderable,

    @Schema(description = "One entry per requested line, in request order")
    List<Line> lines,

    @Schema(description = "Sum of the line totals of all orderable lines at live prices", example = "49.80")
    BigDecimal totalAmount
) {

    @Schema(name = "QuoteLine", description = "Live price and stock for one requested line")
    public record Line(
        @Schema(description = "Product SKU (catalog spelling when found, otherwise as requested)", example = "NG-CHARGER-01")
        String sku,

        @Schema(description = "Product name; null when the SKU is unknown", example = "Nova 65W Fast Charger")
        String name,

        @Schema(description = "Requested quantity", example = "2")
        int requestedQuantity,

        @Schema(description = "Live catalog unit price; null when the SKU is unknown", example = "24.90")
        BigDecimal unitPrice,

        @Schema(description = "Live stock on hand; null when the SKU is unknown", example = "200")
        Integer availableQuantity,

        @Schema(description = "unitPrice x requestedQuantity; null when the line has a problem", example = "49.80")
        BigDecimal lineTotal,

        @Schema(description = "Why this line cannot be ordered as requested; null when it can")
        Problem problem
    ) {}

    @Schema(name = "QuoteLineProblem", description = "Per-line problem code")
    public enum Problem {
        @JsonProperty("not_found") NOT_FOUND,
        @JsonProperty("inactive") INACTIVE,
        @JsonProperty("insufficient_stock") INSUFFICIENT_STOCK
    }
}
