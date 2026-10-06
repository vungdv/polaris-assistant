package vn.danang.polaris.outbox.relay;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;

/** Purges delivered events older than the retention period (TR-E6). Pending events are never touched. */
public class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    private final OutboxRelayStore store;
    private final Duration period;
    private final OutboxMetrics metrics;
    private final Clock clock;

    public OutboxRetention(OutboxRelayStore store, Duration period, OutboxMetrics metrics, Clock clock) {
        if (period.isNegative()) {
            throw new IllegalArgumentException("retention period must not be negative");
        }
        this.store = store;
        this.period = period;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @return the number of delivered events removed */
    public int purgeOnce() {
        int purged = store.purgeDeliveredBefore(clock.instant().minus(period));
        if (purged > 0) {
            metrics.recordPurged(purged);
            log.info("Outbox retention purged={} retention={}", purged, period);
        }
        return purged;
    }
}
