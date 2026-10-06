package vn.danang.polaris.outbox;

import java.util.UUID;

/**
 * Broker-agnostic publish port (TR-E5). Business code depends on this interface only.
 *
 * <p>Publishing records the event atomically with the caller's business change: it is delivered
 * if and only if the surrounding transaction commits (TR-E1). Delivery itself is asynchronous and
 * at-least-once, with the returned id stable across retries (TR-E4).
 *
 * <p><b>Per-key ordering precondition (TR-X8, for TR-E3):</b> events of one key are delivered in the order they were
 * recorded. That equals commit order only if the transactions raising events for the same key are serialised
 * <em>before</em> publishing: lock the aggregate pessimistically ({@code PESSIMISTIC_WRITE}) before the
 * transition, or use optimistic locking ({@code @Version}) so a losing transaction rolls back with its event.
 */
public interface IntegrationEventPublisher {

    /**
     * Records {@code event} in the current transaction.
     *
     * @return the event's CloudEvents {@code id} ({@code ce_id}), assigned now
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active, or it is read-only
     */
    UUID publish(IntegrationEvent event);
}
