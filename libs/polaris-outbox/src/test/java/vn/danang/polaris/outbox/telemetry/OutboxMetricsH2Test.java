package vn.danang.polaris.outbox.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import vn.danang.polaris.outbox.relay.OutboxRelay;
import vn.danang.polaris.outbox.relay.RetryBackoff;
import vn.danang.polaris.outbox.store.JdbcOutboxRelayStore;
import vn.danang.polaris.outbox.store.JdbcOutboxStore;
import vn.danang.polaris.outbox.store.OutboxRecord;

/**
 * The metric contract for the outbox, against the real V15 schema (H2) and the real relay: totals and per-event-type
 * backlog and age, hand-off outcome per event type, and no identifier-shaped labels.
 */
class OutboxMetricsH2Test {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private JdbcOutboxStore store;
    private JdbcOutboxRelayStore relayStore;
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:h2:mem:outbox-metrics-" + UUID.randomUUID(), "sa", "", true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcOutboxStore(jdbc);
        relayStore = new JdbcOutboxRelayStore(jdbc);
        metrics = new OutboxMetrics(registry, relayStore, clock);
    }

    private void record(String type, String orderKey, Instant occurredAt) {
        tx.executeWithoutResult(s -> store.append(new OutboxRecord(UUID.randomUUID(), type, "/polaris", "polaris.order.lifecycle",
                orderKey, "{}", null, null, occurredAt)));
    }

    private double gauge(String name, String eventType) {
        var search = registry.find(name);
        if (eventType != null) {
            search = search.tag("event_type", eventType);
        }
        return search.gauge().value();
    }

    @Test
    void pendingCountAndOldestAge_areReportedInTotalAndPerEventType() {
        record("order.placed.v1", "ORD-1", T0.minusSeconds(120));
        record("order.placed.v1", "ORD-2", T0.minusSeconds(30));
        record("order.cancelled.v1", "ORD-3", T0.minusSeconds(10));

        assertThat(gauge(OutboxMetrics.BACKLOG, null)).isEqualTo(3);
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE, null)).isEqualTo(120);
        assertThat(gauge(OutboxMetrics.PENDING_BY_TYPE, "order.placed.v1")).isEqualTo(2);
        assertThat(gauge(OutboxMetrics.PENDING_OLDEST_AGE_BY_TYPE, "order.placed.v1")).isEqualTo(120);
        assertThat(gauge(OutboxMetrics.PENDING_BY_TYPE, "order.cancelled.v1")).isEqualTo(1);
        assertThat(gauge(OutboxMetrics.PENDING_OLDEST_AGE_BY_TYPE, "order.cancelled.v1")).isEqualTo(10);

        now.set(T0.plusSeconds(60));
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE, null)).as("age grows with the clock").isEqualTo(180);
        assertThat(gauge(OutboxMetrics.PENDING_OLDEST_AGE_BY_TYPE, "order.cancelled.v1")).isEqualTo(70);
    }

    @Test
    void aDrainedType_reportsZero_ratherThanVanishing() {
        record("order.placed.v1", "ORD-1", T0.minusSeconds(5));
        assertThat(gauge(OutboxMetrics.BACKLOG, null)).isEqualTo(1); // registers the per-type gauges

        OutboxRelay relay = new OutboxRelay(relayStore, event -> { }, tx, RetryBackoff.DEFAULT, 10, metrics,
                new HandOffTracing(OpenTelemetry.noop()), clock);
        assertThat(relay.relayOnce().delivered()).isEqualTo(1);
        now.set(T0.plusSeconds(10)); // past the snapshot TTL

        assertThat(gauge(OutboxMetrics.BACKLOG, null)).isZero();
        assertThat(gauge(OutboxMetrics.OLDEST_PENDING_AGE, null)).isZero();
        assertThat(gauge(OutboxMetrics.PENDING_BY_TYPE, "order.placed.v1")).isZero();
        assertThat(gauge(OutboxMetrics.PENDING_OLDEST_AGE_BY_TYPE, "order.placed.v1")).isZero();
    }

    @Test
    void handOffOutcome_isCountedPerEventType_successAndFailure() {
        record("order.placed.v1", "ORD-1", T0);
        record("order.cancelled.v1", "ORD-2", T0);
        OutboxRelay failing = new OutboxRelay(relayStore, event -> { throw new IllegalStateException("kafka down"); }, tx,
                RetryBackoff.DEFAULT, 10, metrics, new HandOffTracing(OpenTelemetry.noop()), clock);
        assertThat(failing.relayOnce().failed()).isEqualTo(1); // a failure ends the batch: one attempt

        assertThat(registry.get(OutboxMetrics.HANDOFF).tag("outcome", "failure").tag("event_type", "order.placed.v1")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find(OutboxMetrics.HANDOFF).tag("outcome", "success").timer()).isNull();

        now.set(T0.plus(Duration.ofMinutes(10))); // beyond the retry backoff
        OutboxRelay working = new OutboxRelay(relayStore, event -> { }, tx, RetryBackoff.DEFAULT, 10, metrics,
                new HandOffTracing(OpenTelemetry.noop()), clock);
        while (working.relayOnce().drainAgain()) {
            // drain
        }
        assertThat(registry.get(OutboxMetrics.HANDOFF).tag("outcome", "success").tag("event_type", "order.placed.v1")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get(OutboxMetrics.DELIVERY_LAG).tag("event_type", "order.cancelled.v1").timer().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void labels_areTheContractSet_neverIdentifiers() {
        record("order.placed.v1", "ORD-SECRET-1", T0);
        OutboxRelay relay = new OutboxRelay(relayStore, event -> { }, tx, RetryBackoff.DEFAULT, 10, metrics,
                new HandOffTracing(OpenTelemetry.noop()), clock);
        gauge(OutboxMetrics.BACKLOG, null);
        relay.relayOnce();

        var keys = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("polaris.outbox"))
                .flatMap(m -> m.getId().getTags().stream())
                .map(t -> t.getKey())
                .collect(Collectors.toSet());
        assertThat(keys).isSubsetOf("event_type", "destination", "outcome");
        assertThat(registry.getMeters().stream().map(Meter::getId).flatMap(id -> id.getTags().stream())
                .map(t -> t.getValue())).noneMatch(v -> v.contains("ORD-SECRET"));
    }
}
