package vn.danang.polaris.outbox.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A pending {@code outbox_events} row locked by the relay for hand-off.
 *
 * @param id       insertion order (primary key); orders events per key
 * @param attempts failed hand-off attempts so far
 */
public record PendingEvent(
        long id,
        UUID eventId,
        String type,
        String source,
        String destination,
        String key,
        String payload,
        String traceparent,
        String tracestate,
        Instant occurredAt,
        int attempts) {
}
