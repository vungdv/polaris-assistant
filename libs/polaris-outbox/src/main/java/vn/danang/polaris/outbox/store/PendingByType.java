package vn.danang.polaris.outbox.store;

import java.time.Instant;

/**
 * Pending outbox events of one event type.
 *
 * @param type               CloudEvents type, a bounded set (never an order or user identifier)
 * @param count              events still pending
 * @param oldestOccurredAt   when the oldest of them was recorded
 */
public record PendingByType(String type, long count, Instant oldestOccurredAt) {
}
