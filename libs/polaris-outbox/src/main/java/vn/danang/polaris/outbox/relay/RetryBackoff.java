package vn.danang.polaris.outbox.relay;

import java.time.Duration;
import java.util.Objects;

/**
 * Capped exponential backoff for failed hand-offs (E3 design §6): {@code initial × multiplier^attempts},
 * never more than {@code max}. There is no attempt limit: events are retried until delivered (TR-E6).
 */
public record RetryBackoff(Duration initial, double multiplier, Duration max) {

    public static final RetryBackoff DEFAULT = new RetryBackoff(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5));

    public RetryBackoff {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial backoff must be positive");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1");
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max backoff must not be below the initial backoff");
        }
    }

    /** Delay before the next attempt, given the number of failed attempts before this failure. */
    public Duration delayAfter(int previousAttempts) {
        double millis = initial.toMillis() * Math.pow(multiplier, Math.max(0, previousAttempts));
        if (Double.isInfinite(millis) || millis >= max.toMillis()) {
            return max;
        }
        return Duration.ofMillis((long) millis);
    }
}
