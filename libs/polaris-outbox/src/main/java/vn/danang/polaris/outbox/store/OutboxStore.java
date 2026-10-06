package vn.danang.polaris.outbox.store;

/** Persistence seam for the outbox. Implementations must write through the caller's transaction. */
public interface OutboxStore {

    /** Appends one pending event. */
    void append(OutboxRecord record);
}
