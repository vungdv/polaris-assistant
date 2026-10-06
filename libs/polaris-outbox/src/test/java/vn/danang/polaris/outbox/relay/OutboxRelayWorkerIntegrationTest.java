package vn.danang.polaris.outbox.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.autoconfigure.OutboxAutoConfiguration;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;
import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * The auto-configured relay worker with a transport bean (the test transport, the only stub) against real
 * PostgreSQL: commit → hand-off in under 1 s (TR-E8) with the default settings, and metrics in the app registry.
 */
// @Testcontainers first so its afterAll runs last: the context (and its polling worker) closes before Postgres stops.
@Testcontainers
@SpringBootTest(classes = OutboxRelayWorkerIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class OutboxRelayWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private IntegrationEventPublisher publisher;
    @Autowired
    private OutboxRelayWorker worker;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void committedEvent_isHandedOffWithinOneSecond_andMarkedDelivered() throws Exception {
        QueueTransport.SENT.clear();
        assertThat(worker.isRelaying()).isTrue();

        UUID recorded;
        try (Scope ignored = Span.wrap(SpanContext.create("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7",
                TraceFlags.getSampled(), TraceState.getDefault())).makeCurrent()) {
            recorded = tx.execute(status -> publisher.publish(new IntegrationEvent(
                    "vn.danang.polaris.test.thing.happened.v1", "/polaris/test", "polaris.test.thing", "K-1",
                    Map.of("key", "K-1"))));
        }
        long committedAt = System.nanoTime();

        OutgoingEvent handedOff = QueueTransport.SENT.poll(1, TimeUnit.SECONDS);
        long handOffMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - committedAt);

        assertThat(handedOff).as("handed off within 1 s of commit").isNotNull();
        assertThat(handOffMillis).isLessThan(1000);
        assertThat(handedOff.id()).isEqualTo(recorded);
        assertThat(handedOff.traceContext().traceparent()).isEqualTo(TRACEPARENT);

        assertThat(awaitStatus(recorded, "DELIVERED", Duration.ofSeconds(2))).isTrue();
        assertThat(meters.get(OutboxMetrics.DELIVERY_LAG).timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get(OutboxMetrics.BACKLOG).gauge().value()).isZero();
        assertThat(meters.find(OutboxMetrics.OLDEST_PENDING_AGE).gauge()).isNotNull();
    }

    @Test
    void aSingleKeyBacklog_drainsBackToBack_notOneEventPerPoll() throws Exception {
        QueueTransport.SENT.clear();
        int events = 12; // at one per 250 ms poll this would take 3 s
        List<UUID> recorded = tx.execute(status -> {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < events; i++) {
                ids.add(publisher.publish(new IntegrationEvent("vn.danang.polaris.test.thing.happened.v1",
                        "/polaris/test", "polaris.test.thing", "HOT-KEY", Map.of("seq", i))));
            }
            return ids;
        });
        long committedAt = System.nanoTime();

        List<UUID> handedOff = new ArrayList<>();
        while (handedOff.size() < events) {
            OutgoingEvent next = QueueTransport.SENT.poll(1, TimeUnit.SECONDS);
            assertThat(next).as("hand-off %d", handedOff.size()).isNotNull();
            handedOff.add(next.id());
        }

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - committedAt)).isLessThan(1000);
        assertThat(handedOff).containsExactlyElementsOf(recorded);
    }

    private boolean awaitStatus(UUID eventId, String status, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String current = jdbc.sql("SELECT status FROM outbox_events WHERE event_id = :id")
                    .param("id", eventId).query(String.class).single();
            if (status.equals(current)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** Test transport, the only stub. */
    static final class QueueTransport implements EventTransport {

        static final BlockingQueue<OutgoingEvent> SENT = new LinkedBlockingQueue<>();

        @Override
        public void send(OutgoingEvent event) {
            SENT.add(event);
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

        @Bean
        EventTransport testTransport() {
            return new QueueTransport();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
