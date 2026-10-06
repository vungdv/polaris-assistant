package vn.danang.polaris.outbox.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.OutboxIntegrationEventPublisher;
import vn.danang.polaris.outbox.relay.OutboxRelay;
import vn.danang.polaris.outbox.relay.RetryBackoff;
import vn.danang.polaris.outbox.telemetry.HandOffTracing;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;

/**
 * V15 and the relay's queries must stay portable to the local H2 default, like V13/V14. PostgreSQL is covered
 * by the integration tests.
 */
class OutboxMigrationH2Test {

    @Test
    void migrationsApplyOnH2_andAnEventCanBeRecorded_butNotInAReadOnlyTransaction() {
        // One long-lived session, like the app's connection pool: H2 binds CHECK expressions to the creating session.
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:h2:mem:outbox-" + UUID.randomUUID(), "sa", "", true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        OutboxIntegrationEventPublisher publisher =
                new OutboxIntegrationEventPublisher(new JdbcOutboxStore(jdbc), new JsonMapper());
        UUID id = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status ->
                publisher.publish(new IntegrationEvent("t.v1", "/s", "d", "k-1", java.util.Map.of("a", 1))));

        assertThat(jdbc.sql("SELECT payload FROM outbox_events WHERE event_id = ?").param(id)
                .query(String.class).single()).isEqualTo("{\"a\":1}");
        assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE event_id = ?").param(id)
                .query(String.class).single()).isEqualTo("PENDING");

        // H2 treats Connection.setReadOnly as a hint, so only the publisher's check stops a read-only recording here.
        TransactionTemplate readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status ->
                publisher.publish(new IntegrationEvent("t.v1", "/s", "d", "k-2", java.util.Map.of("a", 2)))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void relayQueriesRunOnH2_andDeliverInOrderPerKey() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:h2:mem:outbox-relay-" + UUID.randomUUID(), "sa", "", true);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        OutboxIntegrationEventPublisher publisher =
                new OutboxIntegrationEventPublisher(new JdbcOutboxStore(jdbc), new JsonMapper());
        List<UUID> recorded = new ArrayList<>();
        for (String key : List.of("A", "B", "A")) {
            recorded.add(tx.execute(status ->
                    publisher.publish(new IntegrationEvent("t.v1", "/s", "d", key, java.util.Map.of("k", key)))));
        }
        JdbcOutboxRelayStore relayStore = new JdbcOutboxRelayStore(jdbc);
        List<UUID> sent = new ArrayList<>();
        OutboxRelay relay = new OutboxRelay(relayStore, event -> sent.add(event.id()), tx, RetryBackoff.DEFAULT, 10,
                new OutboxMetrics(new SimpleMeterRegistry(), relayStore, Clock.systemUTC()),
                new HandOffTracing(OpenTelemetry.noop()), Clock.systemUTC());

        assertThat(relay.relayOnce().delivered()).as("one head per key").isEqualTo(2);
        assertThat(relay.relayOnce().delivered()).isEqualTo(1);

        assertThat(sent).containsExactly(recorded.get(0), recorded.get(1), recorded.get(2));
        assertThat(relayStore.countPending()).isZero();
        assertThat(relayStore.oldestPendingOccurredAt()).isEmpty();
        assertThat(relayStore.purgeDeliveredBefore(Instant.now().plusSeconds(60))).isEqualTo(3);
    }
}
