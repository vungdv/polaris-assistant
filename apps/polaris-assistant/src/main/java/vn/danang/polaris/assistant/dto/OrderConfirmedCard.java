package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;

/**
 * Payload of the {@value ChatWidget#ORDER_CONFIRMED} card (BPMN {@code A_ConfirmCard}): the order Order
 * Management placed from a confirmed draft. Lines and total are the draft's snapshot, which Order Management
 * charged unchanged: it rejects the order if any live price differs from the snapshot (D2).
 */
@Schema(description = "ORDER_CONFIRMED card: order placed after the shopper clicked Submit Order")
public record OrderConfirmedCard(
        @Schema(description = "Order number assigned by Order Management", example = "ORD-000123")
        String orderNumber,

        @Schema(description = "The draft this order was placed from", example = "dft-3f2a...")
        String draftId,

        @Schema(description = "Customer the order was placed for", example = "7")
        Long customerId,

        @Schema(description = "Customer display name, or null when it could not be obtained cheaply", example = "Alice Tran")
        @Nullable String customerName,

        @Schema(description = "Ordered lines at the confirmed prices")
        List<DraftLine> items,

        @Schema(description = "Order total", example = "49.80")
        BigDecimal total
) {

    /**
     * @param orderNumber  the order placed for {@code draft}
     * @param customerName display name of {@code draft.getCustomerId()}, or null if unknown
     */
    public static OrderConfirmedCard from(OrderDraft draft, String orderNumber, @Nullable String customerName) {
        return new OrderConfirmedCard(orderNumber, draft.getId(), draft.getCustomerId(), customerName,
                List.copyOf(draft.getItems()), draft.getTotalAmount());
    }

    public ChatWidget toWidget() {
        return new ChatWidget(ChatWidget.ORDER_CONFIRMED, this);
    }
}
