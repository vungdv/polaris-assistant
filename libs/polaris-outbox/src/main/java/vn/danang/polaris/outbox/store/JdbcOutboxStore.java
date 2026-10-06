package vn.danang.polaris.outbox.store;

import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes to {@code outbox_events} (Flyway V15). {@link JdbcClient} obtains its connection through
 * {@code DataSourceUtils}, so the insert joins the caller's transaction under both the JDBC and the
 * JPA transaction managers.
 *
 * <p>{@code next_attempt_at} is set from the application clock ({@code occurred_at}) rather than the column's
 * database default, because the relay compares it with the application clock. With one authoritative clock, an
 * app clock running behind the database's cannot delay new events (TR-E8).
 */
public class JdbcOutboxStore implements OutboxStore {

    static final String INSERT = """
            INSERT INTO outbox_events
                (event_id, event_type, event_source, destination, event_key, payload, traceparent, tracestate,
                 occurred_at, next_attempt_at)
            VALUES (:eventId, :type, :source, :destination, :key, :payload, :traceparent, :tracestate,
                    :occurredAt, :occurredAt)
            """;

    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(OutboxRecord record) {
        jdbc.sql(INSERT)
                .param("eventId", record.eventId())
                .param("type", record.type())
                .param("source", record.source())
                .param("destination", record.destination())
                .param("key", record.key())
                .param("payload", record.payload())
                .param("traceparent", record.traceparent())
                .param("tracestate", record.tracestate())
                .param("occurredAt", record.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
