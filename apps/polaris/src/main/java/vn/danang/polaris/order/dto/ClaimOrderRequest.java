package vn.danang.polaris.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Claim of a placed order by a fulfilment partner")
public record ClaimOrderRequest(
    @Schema(description = "Identifier of the claiming fulfilment partner", example = "partner-a")
    @NotBlank @Size(max = 64) String partnerId
) {
}
