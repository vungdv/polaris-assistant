package vn.danang.polaris.assistant.dto;

import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A structured card the client renders next to the reply (PRD-003 §3.1, §3.4). The assistant only
 * delivers the payload; rendering belongs to the chat client.
 *
 * @param type    card kind, e.g. {@value #ORDER_DRAFT}
 * @param payload card data; its shape is fixed per {@code type}
 */
@Schema(description = "Structured card rendered by the chat client next to the reply")
public record ChatWidget(
        @Schema(description = "Card kind", example = ORDER_DRAFT)
        String type,

        @Schema(description = "Card data; shape depends on the type")
        Object payload
) {

    /** Staged order awaiting the shopper's "Submit Order" click: {draftId, items, total, expiresAt}. */
    public static final String ORDER_DRAFT = "ORDER_DRAFT";

    /** Order placed from a confirmed draft: {orderNumber, draftId, customer, items, total}. */
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";

    /** Catalog search results, exactly as the search tool returned them: {products: [...]} ({@link ProductListCard}). */
    public static final String PRODUCT_LIST = "PRODUCT_LIST";

    public ChatWidget {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Widget type must not be blank.");
        }
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
