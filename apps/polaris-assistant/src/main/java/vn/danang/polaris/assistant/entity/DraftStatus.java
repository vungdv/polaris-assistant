package vn.danang.polaris.assistant.entity;

/**
 * Lifecycle states of an {@link OrderDraft}. A draft is staged as {@link #WAITING_CONFIRMATION}
 * and leaves it exactly once, to one of the terminal states:
 * <pre>
 * WAITING_CONFIRMATION → CONFIRMED | CANCELLED | EXPIRED | INVALIDATED
 * </pre>
 */
public enum DraftStatus {
    WAITING_CONFIRMATION,
    /** Shopper clicked "Submit Order" and Order Management placed the order. */
    CONFIRMED,
    /** Shopper (or the model, via discard) declined the draft. */
    CANCELLED,
    /** The confirmation TTL elapsed before the shopper confirmed. */
    EXPIRED,
    /** Order Management rejected the snapshot at confirm time (price changed / insufficient stock). */
    INVALIDATED;

    public boolean isOpen() {
        return this == WAITING_CONFIRMATION;
    }
}
