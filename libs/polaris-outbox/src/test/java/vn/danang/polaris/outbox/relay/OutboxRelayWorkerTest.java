package vn.danang.polaris.outbox.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Scheduling behaviour of the relay thread, with the relay itself mocked. */
class OutboxRelayWorkerTest {

    private static final Duration POLL = Duration.ofMillis(20);

    @Test
    void aFailingCycle_isLoggedAndRetriedOnTheNextInterval() throws Exception {
        AtomicInteger cycles = new AtomicInteger();
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.relayOnce()).thenAnswer(invocation -> {
            cycles.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        });

        runFor(relay, Duration.ofMillis(300));

        assertThat(cycles.get()).isGreaterThan(2);
    }

    @Test
    void aFatalError_stopsTheRelayInsteadOfPollingOn() throws Exception {
        AtomicInteger cycles = new AtomicInteger();
        OutboxRelay relay = mock(OutboxRelay.class);
        when(relay.relayOnce()).thenAnswer(invocation -> {
            cycles.incrementAndGet();
            throw new StackOverflowError("simulated");
        });

        runFor(relay, Duration.ofMillis(300));

        assertThat(cycles.get()).isEqualTo(1);
    }

    @Test
    void cyclesThatDeliver_runBackToBack_untilNothingIsDelivered() throws Exception {
        AtomicInteger cycles = new AtomicInteger();
        OutboxRelay relay = mock(OutboxRelay.class);
        // A single-key backlog of five events: one head per cycle.
        when(relay.relayOnce()).thenAnswer(invocation -> cycles.incrementAndGet() <= 5
                ? new OutboxRelay.CycleResult(1, 1, 0)
                : new OutboxRelay.CycleResult(0, 0, 0));
        OutboxRelayWorker worker = new OutboxRelayWorker(() -> relay, mock(OutboxRetention.class),
                Duration.ofHours(1), Duration.ofHours(1));

        worker.start();
        try {
            Thread.sleep(200); // far less than the poll interval: only draining can run six cycles
        } finally {
            worker.stop();
        }

        assertThat(cycles.get()).isEqualTo(6);
    }

    @Test
    void drainAgain_onlyAfterADeliveryWithoutFailure() {
        assertThat(new OutboxRelay.CycleResult(1, 1, 0).drainAgain()).isTrue();
        assertThat(new OutboxRelay.CycleResult(0, 0, 0).drainAgain()).isFalse();
        assertThat(new OutboxRelay.CycleResult(3, 2, 1).drainAgain()).isFalse();
    }

    private static void runFor(OutboxRelay relay, Duration duration) throws InterruptedException {
        OutboxRelayWorker worker = new OutboxRelayWorker(() -> relay, mock(OutboxRetention.class), POLL,
                Duration.ofHours(1));
        worker.start();
        try {
            Thread.sleep(duration.toMillis());
        } finally {
            worker.stop();
        }
    }
}
