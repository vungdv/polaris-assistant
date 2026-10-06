package vn.danang.polaris.assistant.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.OrderDraft;

@Schema(description = "State of an order draft after a lifecycle action (e.g. cancel)")
public record OrderDraftStatusResponse(
        @Schema(description = "Session the draft belongs to", example = "123e4567-e89b-12d3-a456-426614174000")
        String sessionId,

        @Schema(description = "Draft identifier", example = "dft-3f2a...")
        String draftId,

        @Schema(description = "Draft status", example = "CANCELLED",
                allowableValues = {"WAITING_CONFIRMATION", "CONFIRMED", "CANCELLED", "EXPIRED", "INVALIDATED"})
        String status,

        @Schema(description = "Order placed from the draft, only when CONFIRMED", example = "ORD-000123")
        @Nullable String confirmedOrderNumber,

        @Schema(description = "When the draft last changed")
        Instant updatedAt
) {

    public static OrderDraftStatusResponse from(OrderDraft draft) {
        return new OrderDraftStatusResponse(draft.getSessionId(), draft.getId(), draft.getStatus().name(),
                draft.getConfirmedOrderNumber(), draft.getUpdatedAt());
    }
}
