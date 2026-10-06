package vn.danang.polaris.outbox.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.autoconfigure.OutboxAutoConfiguration;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.telemetry.HandOffTracing;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;
import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * E3 acceptance against real PostgreSQL with the production migrations (V1..V15). The recording test transport
 * is the only stub. The application context has <b>no</b> transport, so its relay worker stays idle; each test
 * drives its own relay instances directly, with a controllable clock for backoff.
 */
@SpringBootTest(classes = OutboxRelayIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboxRelayIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private IntegrationEventPublisher publisher;
    @Autowired
    private OutboxRelayStore relayStore;
    @Autowired
    private OutboxRelayWorker worker;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private javax.sql.DataSource dataSource;

    private final MutableClock clock = new MutableClock();
    private MeterRegistry meters;
    private OutboxMetrics metrics;

    @BeforeEach
    void emptyOutbox() {
        jdbc.sql("DELETE FROM outbox_events").update();
        meters = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(meters, relayStore, clock);
    }

    @Test
    void recordedEvents_areHandedOffInCommitOrderPerKey_andMarkedDelivered() {
        List<UUID> recorded = new ArrayList<>();
        try (Scope ignored = requestSpan()) {
            for (String key : List.of("A", "B", "A", "C", "B", "A")) {
                recorded.add(record(key));
            }
        }
        RecordingTransport transport = new RecordingTransport();

        drain(relay(transport, 100));

        assertThat(transport.idsFor("A")).containsExactly(recorded.get(0), recorded.get(2), recorded.get(5));
        assertThat(transport.idsFor("B")).containsExactly(recorded.get(1), recorded.get(4));
        assertThat(transport.idsFor("C")).containsExactly(recorded.get(3));
        assertThat(transport.sent).hasSize(6);
        assertThat(rows()).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("DELIVERED");
            assertThat(row.deliveredAt()).isNotNull();
            assertThat(row.attempts()).isZero();
        });

        OutgoingEvent first = transport.sent.getFirst();
        assertThat(first.id()).isEqualTo(recorded.getFirst());
        assertThat(first.type()).isEqualTo("vn.danang.polaris.test.thing.happened.v1");
        assertThat(first.source()).isEqualTo("/polaris/test");
        assertThat(first.destination()).isEqualTo("polaris.test.thing");
        assertThat(first.key()).isEqualTo("A");
        assertThat(first.payload()).isEqualTo("{\"key\":\"A\"}");
        // No tracing SDK in this context: the recorded request context is handed over unchanged.
        assertThat(first.traceContext().traceparent()).isEqualTo(TRACEPARENT);
        assertThat(first.traceHeaders()).containsEntry("traceparent", TRACEPARENT);
    }

    @Test
    void transportFailing_eventsStayPendingAndRetryWithBackoff_thenAllAreDeliveredInOrderPerKey() {
        UUID a1 = record("A");
        UUID b1 = record("B");
        UUID a2 = record("A");
        RecordingTransport transport = new RecordingTransport();
        transport.failWhen = event -> true;
        OutboxRelay relay = relay(transport, 100);

        OutboxRelay.CycleResult first = relay.relayOnce();

        // A failure ends the batch: only A's head was tried.
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.drainAgain()).isFalse();
        Row a1Row = row(a1);
        assertThat(a1Row.status()).isEqualTo("PENDING");
        assertThat(a1Row.attempts()).isEqualTo(1);
        assertThat(a1Row.lastError()).isEqualTo("java.io.IOException: transport down");
        assertThat(a1Row.nextAttemptAt()).isCloseTo(clock.instant().plusSeconds(1), within(50, ChronoUnit.MILLIS));

        // A's head is not due, so the next cycle reaches B; A's follower is held back.
        relay.relayOnce();
        assertThat(row(b1).attempts()).isEqualTo(1);
        assertThat(row(a2).attempts()).isZero();
        assertThat(relay.relayOnce().locked()).as("nothing due while backing off").isZero();

        // Second failure of A doubles the delay.
        clock.advance(Duration.ofSeconds(1));
        relay.relayOnce();
        assertThat(row(a1).attempts()).isEqualTo(2);
        assertThat(row(a1).nextAttemptAt()).isCloseTo(clock.instant().plusSeconds(2), within(50, ChronoUnit.MILLIS));

        assertThat(transport.sent).isEmpty();
        assertThat(rows()).allSatisfy(row -> assertThat(row.status()).isEqualTo("PENDING"));
        assertThat(meters.get(OutboxMetrics.HANDOFF).tag("outcome", "failure").timer().count()).isEqualTo(3);

        // Transport recovers: everything is delivered, in order per key, with the recorded ids.
        transport.failWhen = event -> false;
        clock.advance(Duration.ofMinutes(10));
        drain(relay);

        assertThat(transport.idsFor("A")).containsExactly(a1, a2);
        assertThat(transport.idsFor("B")).containsExactly(b1);
        assertThat(rows()).allSatisfy(row -> assertThat(row.status()).isEqualTo("DELIVERED"));
    }

    @Test
    void aFailingEvent_holdsBackLaterEventsForTheSameKeyOnly() {
        UUID a1 = record("A");
        UUID b1 = record("B");
        UUID a2 = record("A");
        UUID b2 = record("B");
        RecordingTransport transport = new RecordingTransport();
        transport.failWhen = event -> event.key().equals("A");
        OutboxRelay relay = relay(transport, 100);

        for (int i = 0; i < 5; i++) {
            relay.relayOnce();
        }

        assertThat(transport.idsFor("B")).containsExactly(b1, b2);
        assertThat(transport.idsFor("A")).isEmpty();
        assertThat(row(a1).attempts()).isEqualTo(1);
        assertThat(row(a2).attempts()).as("never tried while its predecessor is pending").isZero();
        assertThat(row(a2).status()).isEqualTo("PENDING");

        transport.failWhen = event -> false;
        clock.advance(Duration.ofMinutes(10));
        drain(relay);
        assertThat(transport.idsFor("A")).containsExactly(a1, a2);
    }

    @Test
    void twoInstances_neitherReorderNorDoubleHandOff() throws Exception {
        Map<String, List<UUID>> recordedPerKey = new LinkedHashMap<>();
        List<String> keys = List.of("K1", "K2", "K3", "K4");
        for (int i = 0; i < 25; i++) {
            for (String key : keys) {
                recordedPerKey.computeIfAbsent(key, k -> new ArrayList<>()).add(record(key));
            }
        }
        List<OutgoingEvent> handedOff = Collections.synchronizedList(new ArrayList<>());
        EventTransport slowTransport = event -> {
            handedOff.add(event);
            Thread.sleep(2); // widen the window in which both instances compete
        };
        OutboxRelay instance1 = relay(slowTransport, 3);
        OutboxRelay instance2 = relay(slowTransport, 3);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = threads.invokeAll(
                    List.<java.util.concurrent.Callable<Integer>>of(() -> runUntilEmpty(instance1),
                            () -> runUntilEmpty(instance2)),
                    60, TimeUnit.SECONDS);
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }
            assertThat(total).isEqualTo(100);
        } finally {
            threads.shutdownNow();
        }

        assertThat(handedOff).hasSize(100);
        assertThat(handedOff.stream().map(OutgoingEvent::id).distinct()).hasSize(100);
        for (String key : keys) {
            assertThat(handedOff.stream().filter(e -> e.key().equals(key)).map(OutgoingEvent::id))
                    .containsExactlyElementsOf(recordedPerKey.get(key));
        }
        assertThat(relayStore.countPending()).isZero();
    }

    @Test
    void crashBetweenHandOffAndMarkingDelivered_isReSentWithTheSameId() {
        UUID recorded = record("A");
        List<OutgoingEvent> beforeCrash = new CopyOnWriteArrayList<>();
        OutboxRelay crashing = relay(event -> {
            beforeCrash.add(event);
            throw new SimulatedCrash();
        }, 100);

        assertThatThrownBy(crashing::relayOnce).isInstanceOf(SimulatedCrash.class);

        Row afterCrash = row(recorded);
        assertThat(afterCrash.status()).isEqualTo("PENDING");
        assertThat(afterCrash.attempts()).as("nothing of the crashed transaction was committed").isZero();

        RecordingTransport restarted = new RecordingTransport();
        drain(relay(restarted, 100));

        assertThat(beforeCrash).extracting(OutgoingEvent::id).containsExactly(recorded);
        assertThat(restarted.sent).extracting(OutgoingEvent::id).containsExactly(recorded);
        assertThat(row(recorded).status()).isEqualTo("DELIVERED");
    }

    @Test
    void relaySessionKilledAfterHandOff_theEventIsReSentWithTheSameId() {
        UUID recorded = record("A");
        List<OutgoingEvent> beforeCrash = new CopyOnWriteArrayList<>();
        OutboxRelay dying = relay(event -> {
            beforeCrash.add(event);
            // The relay process dies after the transport accepted the event: its database session goes away
            // before "delivered" is committed. The relay's own session is the one bound to this thread.
            Integer pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            try (java.sql.Connection other = dataSource.getConnection();
                    java.sql.PreparedStatement kill = other.prepareStatement("SELECT pg_terminate_backend(?)")) {
                kill.setInt(1, pid);
                kill.execute();
            }
        }, 100);

        assertThatThrownBy(dying::relayOnce).as("marking delivered fails on the dead session")
                .isInstanceOf(RuntimeException.class);
        assertThat(row(recorded).status()).isEqualTo("PENDING");
        assertThat(row(recorded).attempts()).isZero();

        RecordingTransport restarted = new RecordingTransport();
        drain(relay(restarted, 100));

        assertThat(beforeCrash).extracting(OutgoingEvent::id).containsExactly(recorded);
        assertThat(restarted.sent).extracting(OutgoingEvent::id).containsExactly(recorded);
        assertThat(row(recorded).status()).isEqualTo("DELIVERED");
    }

    @Test
    void newEvents_areDueAtTheirApplicationClockRecordingTime_notTheDatabaseClock() {
        UUID recorded = record("A");

        OffsetDateTime[] times = jdbc.sql("SELECT occurred_at, next_attempt_at FROM outbox_events WHERE event_id = :id")
                .param("id", recorded)
                .query((rs, n) -> new OffsetDateTime[] {
                        rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class) })
                .single();

        assertThat(times[1].toInstant()).isEqualTo(times[0].toInstant());
    }

    @Test
    void noTransportConfigured_relayStaysIdle_andEventsAccumulateAsPending() throws Exception {
        assertThat(worker.isRunning()).isTrue();
        assertThat(worker.isRelaying()).isFalse();

        record("A");
        record("B");
        Thread.sleep(600); // more than two default poll intervals

        assertThat(rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.attempts()).isZero();
            assertThat(row.lastError()).isNull();
        });
        assertThat(meters.get(OutboxMetrics.BACKLOG).gauge().value()).isEqualTo(2.0);
    }

    @Test
    void retentionPurge_removesOnlyDeliveredEventsOlderThanTheRetentionPeriod() {
        UUID oldDelivered = record("A");
        UUID recentDelivered = record("B");
        UUID oldPending = record("C");
        Instant now = clock.instant();
        markDeliveredAt(oldDelivered, now.minus(Duration.ofDays(8)));
        markDeliveredAt(recentDelivered, now.minus(Duration.ofHours(1)));
        jdbc.sql("UPDATE outbox_events SET occurred_at = :at WHERE event_id = :id")
                .param("at", now.minus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC)).param("id", oldPending).update();

        int purged = new OutboxRetention(relayStore, Duration.ofDays(7), metrics, clock).purgeOnce();

        assertThat(purged).isEqualTo(1);
        assertThat(rows()).extracting(Row::eventId).containsExactlyInAnyOrder(recentDelivered, oldPending);
        assertThat(meters.get(OutboxMetrics.PURGED).counter().count()).isEqualTo(1.0);
    }

    @Test
    void metrics_exposeBacklogOldestPendingAgeAndDeliveryLag() {
        assertThat(meters.get(OutboxMetrics.BACKLOG).gauge().value()).isZero();
        assertThat(meters.get(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isZero();

        UUID old = record("A");
        record("B");
        jdbc.sql("UPDATE outbox_events SET occurred_at = :at WHERE event_id = :id")
                .param("at", clock.instant().minusSeconds(90).atOffset(ZoneOffset.UTC)).param("id", old).update();

        assertThat(meters.get(OutboxMetrics.BACKLOG).gauge().value()).isEqualTo(2.0);
        assertThat(meters.get(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isBetween(90.0, 120.0);

        drain(relay(new RecordingTransport(), 100));

        assertThat(meters.get(OutboxMetrics.BACKLOG).gauge().value()).isZero();
        assertThat(meters.get(OutboxMetrics.OLDEST_PENDING_AGE).gauge().value()).isZero();
        var lag = meters.get(OutboxMetrics.DELIVERY_LAG).tag("destination", "polaris.test.thing").timer();
        assertThat(lag.count()).isEqualTo(2);
        assertThat(lag.max(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(90.0);
        assertThat(meters.get(OutboxMetrics.HANDOFF).tag("outcome", "success").timer().count()).isEqualTo(2);
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private OutboxRelay relay(EventTransport transport, int batchSize) {
        return new OutboxRelay(relayStore, transport, new TransactionTemplate(transactionManager), RetryBackoff.DEFAULT,
                batchSize, metrics, new HandOffTracing(OpenTelemetry.noop()), clock);
    }

    /** Runs cycles until one finds nothing due; returns the number delivered. */
    private static int drain(OutboxRelay relay) {
        int delivered = 0;
        for (int cycles = 0; cycles < 10_000; cycles++) {
            OutboxRelay.CycleResult result = relay.relayOnce();
            delivered += result.delivered();
            if (result.locked() == 0) {
                return delivered;
            }
        }
        throw new AssertionError("relay did not drain");
    }

    /** Keeps an instance polling while anything is pending, even when the other instance holds every head. */
    private int runUntilEmpty(OutboxRelay relay) {
        int delivered = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (relayStore.countPending() > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("relay instances did not drain");
            }
            delivered += relay.relayOnce().delivered();
        }
        return delivered;
    }

    private UUID record(String key) {
        return tx.execute(status -> publisher.publish(new IntegrationEvent(
                "vn.danang.polaris.test.thing.happened.v1", "/polaris/test", "polaris.test.thing", key,
                Map.of("key", key))));
    }

    private void markDeliveredAt(UUID eventId, Instant at) {
        jdbc.sql("UPDATE outbox_events SET status = 'DELIVERED', delivered_at = :at WHERE event_id = :id")
                .param("at", at.atOffset(ZoneOffset.UTC)).param("id", eventId).update();
    }

    private static Scope requestSpan() {
        SpanContext context = SpanContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault());
        return Span.wrap(context).makeCurrent();
    }

    private Row row(UUID eventId) {
        return rows().stream().filter(r -> r.eventId().equals(eventId)).findFirst().orElseThrow();
    }

    private List<Row> rows() {
        return jdbc.sql("""
                SELECT event_id, event_key, status, attempts, next_attempt_at, last_error, delivered_at
                FROM outbox_events ORDER BY id
                """).query((rs, n) -> new Row(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_key"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                rs.getString("last_error"),
                rs.getObject("delivered_at", OffsetDateTime.class))).list();
    }

    private record Row(UUID eventId, String key, String status, int attempts, Instant nextAttemptAt, String lastError,
            OffsetDateTime deliveredAt) {
    }

    /** Test transport, the only stub: records what it accepted and fails on demand. */
    static final class RecordingTransport implements EventTransport {

        final List<OutgoingEvent> sent = new CopyOnWriteArrayList<>();
        volatile Predicate<OutgoingEvent> failWhen = event -> false;

        @Override
        public void send(OutgoingEvent event) throws IOException {
            if (failWhen.test(event)) {
                throw new IOException("transport down");
            }
            sent.add(event);
        }

        List<UUID> idsFor(String key) {
            return sent.stream().filter(e -> e.key().equals(key)).map(OutgoingEvent::id).toList();
        }
    }

    /** The process dying after the transport accepted an event, before the relay committed. */
    static final class SimulatedCrash extends Error {
        SimulatedCrash() {
            super("simulated crash after hand-off");
        }
    }

    /** Wall-clock time plus an adjustable offset, so backoff can be skipped deterministically. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        void advance(Duration by) {
            offset.updateAndGet(current -> current.plus(by));
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            JdbcClientAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            OutboxAutoConfiguration.class })
    static class TestApp {
    }
}
