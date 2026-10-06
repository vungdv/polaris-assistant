package vn.danang.polaris.catalog.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for onboarding a new product to the catalog")
public record CreateProductRequest(
    @NotBlank(message = "SKU must not be blank")
    @Size(max = 50, message = "SKU must not exceed 50 characters")
    @Schema(description = "Unique business SKU code", example = "NG-KEYBOARD-01", requiredMode = Schema.RequiredMode.REQUIRED)
    String sku,

    @NotBlank(message = "Product name must not be blank")
    @Size(max = 255, message = "Product name must not exceed 255 characters")
    @Schema(description = "Product display name", example = "Nova Mechanical Keyboard", requiredMode = Schema.RequiredMode.REQUIRED)
    String name,

    @Schema(description = "Detailed product description", example = "Tenkeyless compact mechanical keyboard with RGB backlighting")
    String description,

    @Schema(description = "Category name or label", example = "Computing & Peripherals")
    String category,

    @Schema(description = "Existing category ID to associate", example = "1")
    Long categoryId,

    @NotNull(message = "Price must not be null")
    @Positive(message = "Price must be strictly positive")
    @Schema(description = "Unit price (must be greater than 0.00)", example = "89.99", requiredMode = Schema.RequiredMode.REQUIRED)
    BigDecimal price,

    @Min(value = 0, message = "Stock quantity cannot be negative")
    @Schema(description = "Initial in-stock quantity (default 0)", example = "75", defaultValue = "0")
    Integer stockQuantity,

    @Schema(description = "Product active status (default true)", example = "true", defaultValue = "true")
    Boolean active
) {
    public CreateProductRequest(String sku, String name, BigDecimal price) {
        this(sku, name, null, null, null, price, 0, true);
    }
}
