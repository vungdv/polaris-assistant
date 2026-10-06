package vn.danang.polaris.outbox.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.store.PendingEvent;
import vn.danang.polaris.outbox.telemetry.HandOffTracing;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;
import vn.danang.polaris.outbox.transport.EventTransport;

/**
 * One relay cycle (E3 design §5): in a single transaction, lock the due head of up to {@code batchSize} keys,
 * hand each to the transport in id order and mark it delivered, or count the failure, schedule a retry with
 * backoff and end the batch. A crash before the commit leaves the events pending, to be re-sent with the same id.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayStore store;
    private final EventTransport transport;
    private final TransactionTemplate transaction;
    private final RetryBackoff backoff;
    private final int batchSize;
    private final OutboxMetrics metrics;
    private final HandOffTracing tracing;
    private final Clock clock;

    public OutboxRelay(OutboxRelayStore store, EventTransport transport, TransactionTemplate transaction,
            RetryBackoff backoff, int batchSize, OutboxMetrics metrics, HandOffTracing tracing, Clock clock) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.batchSize = batchSize;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.tracing = Objects.requireNonNull(tracing, "tracing");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs one cycle. */
    public CycleResult relayOnce() {
        List<Delivery> delivered = new ArrayList<>();
        int[] failed = { 0 };
        int[] locked = { 0 };
        transaction.executeWithoutResult(status -> {
            List<PendingEvent> heads = store.lockDueHeads(clock.instant(), batchSize);
            locked[0] = heads.size();
            for (PendingEvent event : heads) {
                if (!handOff(event, delivered)) {
                    failed[0]++;
                    break; // the transport is likely down: don't pay its timeout once per key (design §6)
                }
            }
        });
        // Only now are the deliveries committed.
        delivered.forEach(d -> metrics.recordDelivered(d.destination(), d.type(), d.lag()));
        return new CycleResult(locked[0], delivered.size(), failed[0]);
    }

    private boolean handOff(PendingEvent event, List<Delivery> delivered) {
        // The hand-off span is current throughout, so the bookkeeping and the logs below carry its trace ids.
        try (HandOffTracing.HandOff handOff = tracing.begin(event)) {
            Instant start = clock.instant();
            try {
                transport.send(handOff.outgoingEvent());
            } catch (Exception failure) {
                handOff.failed(failure);
                Instant now = clock.instant();
                metrics.recordHandOff(event.destination(), event.type(), false, Duration.between(start, now));
                Duration retryIn = backoff.delayAfter(event.attempts());
                store.markFailed(event.id(), now.plus(retryIn), describe(failure));
                log.warn("Outbox hand-off failed ce_id={} ce_type={} key={} destination={} attempt={} retry_in={} error={}",
                        event.eventId(), event.type(), event.key(), event.destination(), event.attempts() + 1, retryIn,
                        describe(failure));
                return false;
            } catch (Error crash) {
                handOff.failed(crash);
                throw crash; // rolls the cycle back: the event stays pending and is re-sent with the same id
            }
            Instant now = clock.instant();
            metrics.recordHandOff(event.destination(), event.type(), true, Duration.between(start, now));
            store.markDelivered(event.id(), now);
            delivered.add(new Delivery(event.destination(), event.type(), Duration.between(event.occurredAt(), now)));
            log.debug("Outbox event delivered ce_id={} ce_type={} key={} destination={} attempt={}",
                    event.eventId(), event.type(), event.key(), event.destination(), event.attempts() + 1);
            return true;
        }
    }

    private static String describe(Exception failure) {
        return failure.getMessage() == null
                ? failure.getClass().getName()
                : failure.getClass().getName() + ": " + failure.getMessage();
    }

    private record Delivery(String destination, String type, Duration lag) {
    }

    /**
     * Outcome of one cycle.
     *
     * @param locked    heads locked for hand-off
     * @param delivered events handed off and marked delivered
     * @param failed    failed hand-offs (at most one: a failure ends the batch)
     */
    public record CycleResult(int locked, int delivered, int failed) {

        /**
         * Something was delivered and nothing failed: run the next cycle straight away. A delivery can make the
         * key's next event its head, so a backlog (even on a single key) drains back-to-back, not one per poll.
         */
        public boolean drainAgain() {
            return delivered > 0 && failed == 0;
        }
    }
}
