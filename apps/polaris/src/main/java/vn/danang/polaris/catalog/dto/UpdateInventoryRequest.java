package vn.danang.polaris.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateInventoryRequest(
    @NotNull(message = "Delta adjustment is required")
    @Schema(description = "Relative stock delta adjustment (+/-) to add or deduct from current stock", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
    Integer delta
) {
}
