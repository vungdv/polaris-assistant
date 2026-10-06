package vn.danang.polaris.outbox.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.store.PendingByType;
import vn.danang.polaris.outbox.store.PendingEvent;

/**
 * The outbox gauges are read on the registry's single publish thread, so a database that hangs must never hold
 * that thread (it would stop every metric of the app, JVM included). The store here hangs on demand.
 */
class OutboxMetricsNonBlockingTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Duration QUERY_TIMEOUT = Duration.ofMillis(300);

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HangingStore store = new HangingStore();
    private final OutboxMetrics metrics = new OutboxMetrics(registry, store, clock, Duration.ofSeconds(5), QUERY_TIMEOUT,
            Duration.ofSeconds(30));

    @AfterEach
    void tearDown() {
        store.release.countDown();
        metrics.close();
    }

    private double backlog() {
        return registry.get(OutboxMetrics.BACKLOG).gauge().value();
    }

    private long timeMillis(Runnable read) {
        long start = System.nanoTime();
        read.run();
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    @Test
    void aHungDatabase_costsTheReaderAtMostTheQueryTimeout_andThenNothing() {
        store.hang.set(true);

        long first = timeMillis(() -> assertThat(backlog()).as("no snapshot yet: not reported").isNaN());
        assertThat(first).as("first read waits for the query timeout, not for the database").isBetween(250L, 1500L);

        // The remaining gauges of the same publish, and the next publishes, must not wait again.
        long rest = timeMillis(() -> {
            assertThat(registry.get(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isNaN();
            assertThat(backlog()).isNaN();
            assertThat(backlog()).isNaN();
        });
        assertThat(rest).as("a query already in flight is not waited for again").isLessThan(250L);
    }

    @Test
    void aSlowDatabase_doesNotStopOtherMeters_ofTheSameRegistry() throws Exception {
        store.hang.set(true);
        AtomicBoolean otherMeterRead = new AtomicBoolean();
        registry.gauge("jvm.like.gauge", new java.util.concurrent.atomic.AtomicInteger(7));

        Thread publisher = new Thread(() -> {
            // What a push registry does on its publish thread: read every gauge in turn.
            registry.getMeters().forEach(meter -> {
                if (meter instanceof io.micrometer.core.instrument.Gauge g) {
                    g.value();
                    if (g.getId().getName().equals("jvm.like.gauge")) {
                        otherMeterRead.set(true);
                    }
                }
            });
        });
        long took = timeMillis(() -> {
            publisher.start();
            try {
                publisher.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(publisher.isAlive()).as("the publish pass finished while the database was hung").isFalse();
        assertThat(otherMeterRead).isTrue();
        assertThat(took).isLessThan(2_000L);
    }

    @Test
    void valuesComeBack_whenTheDatabaseDoes() throws Exception {
        store.hang.set(true);
        assertThat(backlog()).isNaN();

        store.hang.set(false);
        store.release.countDown();
        store.backlog = 4;
        now.set(T0.plusSeconds(10)); // past the snapshot TTL
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Double.isNaN(backlog()) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(backlog()).isEqualTo(4);
    }

    @Test
    void aLastGoodSnapshot_isServedWhileFresh_thenDropped_neverPresentedAsCurrentForever() {
        store.backlog = 3;
        assertThat(backlog()).isEqualTo(3);

        store.hang.set(true);
        now.set(T0.plusSeconds(10)); // TTL passed, query hangs, snapshot still within staleAfter (30 s)
        assertThat(backlog()).as("bounded wait, then the last good value").isEqualTo(3);

        now.set(T0.plusSeconds(45)); // older than staleAfter
        assertThat(backlog()).isNaN();
    }

    @Test
    void aFailingDatabase_reportsNaN_andRecovers() {
        store.fail.set(true);
        assertThat(backlog()).isNaN();
        store.fail.set(false);
        store.backlog = 2;
        now.set(T0.plusSeconds(10));
        assertThat(backlog()).isEqualTo(2);
    }

    @Test
    void withinTheTtl_oneSnapshotServesEveryGauge() {
        store.backlog = 5;
        assertThat(backlog()).isEqualTo(5);
        store.backlog = 9; // would be seen by a fresh query
        now.set(T0.plusSeconds(2));
        assertThat(backlog()).as("cached").isEqualTo(5);
        assertThat(store.queries.get()).isEqualTo(1);
    }

    private static final class HangingStore implements OutboxRelayStore {
        final AtomicBoolean hang = new AtomicBoolean();
        final AtomicBoolean fail = new AtomicBoolean();
        final CountDownLatch release = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        volatile long backlog;

        @Override
        public long countPending() {
            queries.incrementAndGet();
            if (fail.get()) {
                throw new IllegalStateException("db down");
            }
            if (hang.get()) {
                try {
                    release.await(); // a JDBC read with no timeout on a paused database
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return backlog;
        }

        @Override public Optional<Instant> oldestPendingOccurredAt() { return Optional.empty(); }
        @Override public List<PendingByType> pendingByType() { return List.of(); }
        @Override public List<PendingEvent> lockDueHeads(Instant now, int limit) { return List.of(); }
        @Override public void markDelivered(long id, Instant deliveredAt) { }
        @Override public void markFailed(long id, Instant nextAttemptAt, String error) { }
        @Override public int purgeDeliveredBefore(Instant cutoff) { return 0; }
    }
}
