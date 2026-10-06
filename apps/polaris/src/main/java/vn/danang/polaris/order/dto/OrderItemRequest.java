package vn.danang.polaris.order.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Order line item specification")
public record OrderItemRequest(
    @Schema(description = "Product SKU code", example = "NG-EARBUD-01", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "SKU is required")
    String sku,

    @Schema(description = "Quantity of product units to purchase", example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    Integer quantity,

    @Schema(description = "Optional unit price the buyer confirmed (e.g. from a quote). If the live price differs when the order "
        + "is placed, the whole order is rejected with 409 price-changed and nothing is charged or deducted",
        example = "49.90", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @DecimalMin(value = "0.00", message = "Expected unit price must not be negative")
    BigDecimal expectedUnitPrice
) {
    public OrderItemRequest(String sku, Integer quantity) {
        this(sku, quantity, null);
    }
}
