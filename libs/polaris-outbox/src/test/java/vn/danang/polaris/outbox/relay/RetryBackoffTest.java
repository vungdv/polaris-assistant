package vn.danang.polaris.outbox.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    @Test
    void defaultBackoff_growsExponentiallyFromOneSecond_andIsCappedAtFiveMinutes() {
        RetryBackoff backoff = RetryBackoff.DEFAULT;

        assertThat(backoff.delayAfter(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.delayAfter(8)).isEqualTo(Duration.ofSeconds(256));
        assertThat(backoff.delayAfter(9)).isEqualTo(Duration.ofMinutes(5));
        assertThat(backoff.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void negativeAttempts_areTreatedAsTheFirstAttempt() {
        assertThat(RetryBackoff.DEFAULT.delayAfter(-3)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void invalidSettings_areRejected() {
        assertThatThrownBy(() -> new RetryBackoff(Duration.ZERO, 2, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofSeconds(1), 0.5, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofSeconds(10), 2, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
