package vn.danang.polaris.outbox;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.store.OutboxRecord;
import vn.danang.polaris.outbox.store.OutboxStore;
import vn.danang.polaris.outbox.trace.W3cTraceContext;

/**
 * Records integration events in the transactional outbox (ADR-0018): same transaction as the business
 * change, a {@code ce_id} assigned now, and the raising request's trace context.
 */
public class OutboxIntegrationEventPublisher implements IntegrationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxIntegrationEventPublisher.class);

    private final OutboxStore store;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;
    private final Supplier<W3cTraceContext> traceContext;

    public OutboxIntegrationEventPublisher(OutboxStore store, JsonMapper jsonMapper) {
        this(store, jsonMapper, Clock.systemUTC(), UUID::randomUUID, W3cTraceContext::captureCurrent);
    }

    OutboxIntegrationEventPublisher(OutboxStore store, JsonMapper jsonMapper, Clock clock,
            Supplier<UUID> idGenerator, Supplier<W3cTraceContext> traceContext) {
        this.store = store;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.traceContext = traceContext;
    }

    @Override
    public UUID publish(IntegrationEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Outside a transaction the insert would auto-commit on its own and could outlive a rolled-back change.
            throw new IllegalTransactionStateException(
                    "Integration event " + event.type() + " must be published inside a transaction");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            // A read-only transaction never writes its business change (JPA FlushMode.MANUAL), yet H2 would
            // still commit this insert: an event with no change behind it.
            throw new IllegalTransactionStateException(
                    "Integration event " + event.type() + " must not be published inside a read-only transaction");
        }
        UUID eventId = idGenerator.get();
        W3cTraceContext trace = traceContext.get();
        store.append(new OutboxRecord(
                eventId,
                event.type(),
                event.source(),
                event.destination(),
                event.key(),
                jsonMapper.writeValueAsString(event.data()),
                trace.traceparent(),
                trace.tracestate(),
                clock.instant()));
        log.debug("Outbox event recorded ce_id={} ce_type={} key={}", eventId, event.type(), event.key());
        return eventId;
    }
}
