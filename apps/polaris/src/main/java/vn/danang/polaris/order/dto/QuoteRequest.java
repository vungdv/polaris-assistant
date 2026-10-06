package vn.danang.polaris.order.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Read-only stock and price check for a prospective order. No customer is needed and nothing is reserved or written.")
public record QuoteRequest(
    @Schema(description = "Products and quantities to check; lines repeating a SKU are merged",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "Quote must contain at least one item")
    @Size(max = QuoteRequest.MAX_ITEMS, message = "Quote must contain at most " + QuoteRequest.MAX_ITEMS + " items")
    List<@Valid Item> items
) {

    public static final int MAX_ITEMS = 50;

    @Schema(name = "QuoteItemRequest", description = "Quote line item specification")
    public record Item(
        @Schema(description = "Product SKU code", example = "NG-CHARGER-01", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "SKU is required")
        String sku,

        @Schema(description = "Quantity of product units requested", example = "2", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Integer quantity
    ) {}
}
