package vn.danang.polaris.order.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

@Schema(description = "Order placement request payload")
public record CreateOrderRequest(
    @Schema(description = "Customer database ID. Required for staff; shoppers may omit it (their linked customer is used) "
        + "and may not name another customer", example = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    Long customerId,

    @Schema(description = "List of products and quantities to purchase", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "Order must contain at least one item")
    List<@Valid OrderItemRequest> items,

    @Schema(description = "Optional idempotency key to prevent duplicate orders during retries", example = "unique-key-123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    String idempotencyKey
) {}
