package vn.danang.polaris.outbox.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code outbox_events} as written when an event is recorded. Delivery state
 * ({@code status}, {@code attempts}, ...) takes its column defaults and belongs to the relay.
 *
 * @param traceparent W3C {@code traceparent} of the raising request, or {@code null}
 * @param tracestate  W3C {@code tracestate}, or {@code null}
 */
public record OutboxRecord(
        UUID eventId,
        String type,
        String source,
        String destination,
        String key,
        String payload,
        String traceparent,
        String tracestate,
        Instant occurredAt) {

    public OutboxRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
