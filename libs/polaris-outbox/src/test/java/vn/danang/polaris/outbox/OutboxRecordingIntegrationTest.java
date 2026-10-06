package vn.danang.polaris.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.context.Scope;
import vn.danang.polaris.outbox.autoconfigure.OutboxAutoConfiguration;
import vn.danang.polaris.outbox.store.OutboxRecord;
import vn.danang.polaris.outbox.store.OutboxStore;

/**
 * E2 acceptance against real PostgreSQL with the production migrations (V1..V15), using a test-only
 * domain event, translator and integration event: no Order code.
 */
@SpringBootTest(classes = OutboxRecordingIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboxRecordingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEventPublisher domainEvents;
    @Autowired
    private IntegrationEventPublisher publisher;
    @Autowired
    private OutboxStore store;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyOutbox() {
        jdbc.sql("DELETE FROM outbox_events").update();
    }

    @Test
    void commit_recordsExactlyOneEventWithUniqueIdAndTraceContext() {
        UUID ceId;
        try (Scope ignored = currentSpan()) {
            ceId = tx.execute(status -> {
                domainEvents.publishEvent(new SampleThingHappened("T-1", "hello"));
                return SampleTranslator.lastId;
            });
        }

        List<Row> rows = rows();
        assertThat(rows).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.eventId()).isEqualTo(ceId);
        assertThat(row.eventType()).isEqualTo(SampleTranslator.TYPE);
        assertThat(row.eventSource()).isEqualTo(SampleTranslator.SOURCE);
        assertThat(row.destination()).isEqualTo(SampleTranslator.DESTINATION);
        assertThat(row.eventKey()).isEqualTo("T-1");
        assertThat(row.payload()).isEqualTo("{\"thingId\":\"T-1\",\"note\":\"hello\"}");
        assertThat(row.traceparent()).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.attempts()).isZero();
        assertThat(row.occurredAt()).isNotNull();
        assertThat(row.deliveredAt()).isNull();
    }

    @Test
    void commit_withoutActiveSpan_recordsNoTraceContext() {
        tx.executeWithoutResult(status -> domainEvents.publishEvent(new SampleThingHappened("T-2", "untraced")));

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.traceparent()).isNull();
            assertThat(row.tracestate()).isNull();
        });
    }

    @Test
    void eachRecordedEvent_getsItsOwnId() {
        tx.executeWithoutResult(status -> {
            domainEvents.publishEvent(new SampleThingHappened("T-3", "first"));
            domainEvents.publishEvent(new SampleThingHappened("T-3", "second"));
        });

        List<Row> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).eventId()).isNotEqualTo(rows.get(1).eventId());
    }

    @Test
    void storage_rejectsADuplicateEventId() {
        OutboxRecord record = new OutboxRecord(UUID.randomUUID(), "t", "/s", "d", "k", "{}", null, null, Instant.now());
        tx.executeWithoutResult(status -> store.append(record));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.append(record)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(rows()).hasSize(1);
    }

    @Test
    void rollback_recordsNothing() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            domainEvents.publishEvent(new SampleThingHappened("T-4", "doomed"));
            throw new IllegalStateException("business rule violated after the event was raised");
        })).isInstanceOf(IllegalStateException.class);

        tx.executeWithoutResult(status -> {
            domainEvents.publishEvent(new SampleThingHappened("T-5", "doomed too"));
            status.setRollbackOnly();
        });

        assertThat(rows()).isEmpty();
    }

    @Test
    void publishOutsideTransaction_failsFastAndRecordsNothing() {
        assertThatThrownBy(() -> publisher.publish(SampleTranslator.toIntegrationEvent(new SampleThingHappened("T-6", "direct"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> domainEvents.publishEvent(new SampleThingHappened("T-7", "via domain event")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    void readOnlyTransaction_failsFastAndRecordsNothing() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
                domainEvents.publishEvent(new SampleThingHappened("T-8", "read-only"))))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("read-only");

        assertThat(rows()).isEmpty();
    }

    @Test
    void oversizedTracestate_isRecordedInFullAndTheBusinessTransactionCommits() {
        // W3C maximum: 32 members, each with a 256-char value; far beyond the 512 chars vendors must at least propagate.
        TraceStateBuilder builder = TraceState.builder();
        for (int i = 0; i < 32; i++) {
            builder.put("vendor" + i, "v".repeat(256));
        }
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), builder.build());

        try (Scope ignored = Span.wrap(context).makeCurrent()) {
            tx.executeWithoutResult(status -> domainEvents.publishEvent(new SampleThingHappened("T-9", "long tracestate")));
        }

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.traceparent()).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
            assertThat(row.tracestate()).hasSizeGreaterThan(512).contains("vendor0=", "vendor31=");
        });
    }

    private static Scope currentSpan() {
        SpanContext context = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        return Span.wrap(context).makeCurrent();
    }

    private List<Row> rows() {
        return jdbc.sql("""
                SELECT event_id, event_type, event_source, destination, event_key, payload, traceparent, tracestate,
                       occurred_at, status, attempts, delivered_at
                FROM outbox_events ORDER BY id
                """).query((rs, n) -> new Row(
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("event_source"),
                rs.getString("destination"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getString("traceparent"),
                rs.getString("tracestate"),
                rs.getTimestamp("occurred_at"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getTimestamp("delivered_at"))).list();
    }

    private record Row(UUID eventId, String eventType, String eventSource, String destination, String eventKey,
            String payload, String traceparent, String tracestate, java.sql.Timestamp occurredAt, String status,
            int attempts, java.sql.Timestamp deliveredAt) {
    }

    /** Test-only domain event: context-internal, never published as-is. */
    record SampleThingHappened(String thingId, String note) {
    }

    /** Test-only integration contract payload. */
    record SampleThingPayload(String thingId, String note) {
    }

    /** Test-only translator, shaped as ADR-0018 prescribes: synchronous, same thread and transaction. */
    static class SampleTranslator {

        static final String TYPE = "vn.danang.polaris.test.sample.happened.v1";
        static final String SOURCE = "/polaris/test";
        static final String DESTINATION = "polaris.test.sample";

        static volatile UUID lastId;

        private final IntegrationEventPublisher publisher;

        SampleTranslator(IntegrationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @EventListener
        void on(SampleThingHappened event) {
            lastId = publisher.publish(toIntegrationEvent(event));
        }

        static IntegrationEvent toIntegrationEvent(SampleThingHappened event) {
            return new IntegrationEvent(TYPE, SOURCE, DESTINATION, event.thingId(),
                    new SampleThingPayload(event.thingId(), event.note()));
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
    @Import(SampleTranslator.class)
    static class TestApp {
    }
}
