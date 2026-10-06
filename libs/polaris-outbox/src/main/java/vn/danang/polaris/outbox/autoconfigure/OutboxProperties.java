package vn.danang.polaris.outbox.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code polaris.outbox.*} settings (E3 design §10). {@code polaris.outbox.enabled} and
 * {@code polaris.outbox.relay.enabled} are read by the auto-configuration conditions.
 */
@ConfigurationProperties("polaris.outbox")
public record OutboxProperties(@DefaultValue Relay relay, @DefaultValue Retention retention, @DefaultValue Kafka kafka) {

    /**
     * @param pollInterval wait between relay cycles when there is nothing more to drain (TR-E8: well under 1 s)
     * @param batchSize    keys handed off per relay transaction
     */
    public record Relay(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("250ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue Backoff backoff) {
    }

    /** Capped exponential retry backoff. */
    public record Backoff(
            @DefaultValue("1s") Duration initial,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("5m") Duration max) {
    }

    /**
     * @param period        how long delivered events are kept
     * @param purgeInterval how often the purge runs
     */
    public record Retention(
            @DefaultValue("7d") Duration period,
            @DefaultValue("1h") Duration purgeInterval) {
    }

    /**
     * Kafka transport (ADR-0019), active when Spring Kafka and the CloudEvents Kafka binding are on the classpath.
     *
     * @param enabled     {@code false} leaves the relay without this transport (events stay pending)
     * @param sendTimeout bound of each phase of one send: metadata wait, then broker acknowledgement
     */
    public record Kafka(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10s") Duration sendTimeout) {
    }
}
