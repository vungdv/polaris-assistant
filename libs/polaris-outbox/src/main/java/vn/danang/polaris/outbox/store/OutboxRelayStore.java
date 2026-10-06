package vn.danang.polaris.outbox.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Relay-side persistence seam for the outbox (E3). The recording side ({@link OutboxStore}) stays append-only.
 */
public interface OutboxRelayStore {

    /**
     * Locks, in the caller's transaction, the due head (oldest pending event) of up to {@code limit} keys,
     * oldest first. Heads locked by another relay are skipped; a key whose head is not due yields nothing, so
     * a failing event holds back only its own key.
     */
    List<PendingEvent> lockDueHeads(Instant now, int limit);

    void markDelivered(long id, Instant deliveredAt);

    /** Counts one more failed attempt and schedules the next one. */
    void markFailed(long id, Instant nextAttemptAt, String error);

    /** Deletes delivered events delivered before {@code cutoff}; pending events are never touched. */
    int purgeDeliveredBefore(Instant cutoff);

    long countPending();

    Optional<Instant> oldestPendingOccurredAt();

    /** Pending events grouped by event type (one row per type that has any); empty when nothing is pending. */
    List<PendingByType> pendingByType();
}
