package vn.danang.polaris.outbox.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Relay queries over {@code outbox_events} (Flyway V15), standard SQL that runs on PostgreSQL and H2.
 * Per-key ordering across relay instances comes from row locks on each key's head (E3 design §5).
 */
public class JdbcOutboxRelayStore implements OutboxRelayStore {

    /** V15 {@code last_error VARCHAR(2000)}. */
    static final int MAX_ERROR_LENGTH = 2000;

    static final String LOCK_DUE_HEADS = """
            SELECT e.id, e.event_id, e.event_type, e.event_source, e.destination, e.event_key, e.payload,
                   e.traceparent, e.tracestate, e.occurred_at, e.attempts
            FROM outbox_events e
            WHERE e.status = 'PENDING'
              AND e.next_attempt_at <= :now
              AND NOT EXISTS (SELECT 1 FROM outbox_events p
                              WHERE p.status = 'PENDING' AND p.event_key = e.event_key AND p.id < e.id)
            ORDER BY e.id
            FETCH FIRST :limit ROWS ONLY
            FOR UPDATE SKIP LOCKED
            """;

    static final String MARK_DELIVERED = """
            UPDATE outbox_events SET status = 'DELIVERED', delivered_at = :deliveredAt
            WHERE id = :id AND status = 'PENDING'
            """;

    static final String MARK_FAILED = """
            UPDATE outbox_events SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt, last_error = :error
            WHERE id = :id AND status = 'PENDING'
            """;

    static final String PURGE = "DELETE FROM outbox_events WHERE status = 'DELIVERED' AND delivered_at < :cutoff";

    private final JdbcClient jdbc;

    public JdbcOutboxRelayStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PendingEvent> lockDueHeads(Instant now, int limit) {
        return jdbc.sql(LOCK_DUE_HEADS)
                .param("now", utc(now))
                .param("limit", limit)
                .query(JdbcOutboxRelayStore::pendingEvent)
                .list();
    }

    @Override
    public void markDelivered(long id, Instant deliveredAt) {
        jdbc.sql(MARK_DELIVERED).param("deliveredAt", utc(deliveredAt)).param("id", id).update();
    }

    @Override
    public void markFailed(long id, Instant nextAttemptAt, String error) {
        jdbc.sql(MARK_FAILED)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    @Override
    public int purgeDeliveredBefore(Instant cutoff) {
        return jdbc.sql(PURGE).param("cutoff", utc(cutoff)).update();
    }

    @Override
    public long countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'").query(Long.class).single();
    }

    @Override
    public Optional<Instant> oldestPendingOccurredAt() {
        return jdbc.sql("SELECT MIN(occurred_at) FROM outbox_events WHERE status = 'PENDING'")
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Override
    public List<PendingByType> pendingByType() {
        return jdbc.sql("SELECT event_type, COUNT(*), MIN(occurred_at) FROM outbox_events "
                        + "WHERE status = 'PENDING' GROUP BY event_type")
                .query((rs, n) -> new PendingByType(rs.getString(1), rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant()))
                .list();
    }

    private static PendingEvent pendingEvent(ResultSet rs, int rowNum) throws SQLException {
        return new PendingEvent(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("event_source"),
                rs.getString("destination"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getString("traceparent"),
                rs.getString("tracestate"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempts"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
