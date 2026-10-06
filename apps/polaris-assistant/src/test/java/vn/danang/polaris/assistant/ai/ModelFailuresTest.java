package vn.danang.polaris.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonParseException;

class ModelFailuresTest {

    @ParameterizedTest(name = "HTTP {0}: outage={1}, retryable={2}, rejected={3}")
    @CsvSource({
            "400, false, false, true",
            "401, false, false, true",
            "403, false, false, true",
            "404, false, false, true",
            "429, true,  true,  false",
            "500, true,  true,  false",
            "503, true,  true,  false"
    })
    @DisplayName("Provider status codes: 429 and 5xx are outages worth retrying, other 4xx are our rejected requests")
    void classifies_provider_status(int status, boolean outage, boolean retryable, boolean rejected) {
        ModelUnavailableException failure = unavailable(new ModelProviderException(status));

        assertThat(ModelFailures.isOutage(failure)).isEqualTo(outage);
        assertThat(ModelFailures.isRetryable(failure)).isEqualTo(retryable);
        assertThat(ModelFailures.isRejectedRequest(failure)).isEqualTo(rejected);
    }

    @Test
    @DisplayName("Connection errors are outages worth retrying")
    void connection_errors_are_retryable_outages() {
        for (Throwable cause : new Throwable[] {new ConnectException("refused"), new IOException("Connection reset"),
                new HttpConnectTimeoutException("connect timed out")}) {
            assertThat(ModelFailures.isOutage(unavailable(cause))).as(cause.toString()).isTrue();
            assertThat(ModelFailures.isRetryable(unavailable(cause))).as(cause.toString()).isTrue();
            assertThat(ModelFailures.isRejectedRequest(unavailable(cause))).as(cause.toString()).isFalse();
        }
    }

    @Test
    @DisplayName("A request timeout is an outage but not retried; it already used its time")
    void request_timeout_is_an_outage_but_not_retryable() {
        ModelUnavailableException failure = unavailable(new HttpTimeoutException("request timed out"));

        assertThat(ModelFailures.isOutage(failure)).isTrue();
        assertThat(ModelFailures.isRetryable(failure)).isFalse();
    }

    @Test
    @DisplayName("An exhausted turn budget, a bad response body or an interrupt are neither outages nor retryable")
    void local_failures_are_not_outages() {
        for (Throwable cause : new Throwable[] {new TurnDeadlineExceededException("deadline"),
                new JsonParseException(null, "bad json"), new InterruptedException(), new IllegalStateException()}) {
            assertThat(ModelFailures.isOutage(unavailable(cause))).as(String.valueOf(cause)).isFalse();
            assertThat(ModelFailures.isRetryable(unavailable(cause))).as(String.valueOf(cause)).isFalse();
        }
    }

    @Test
    @DisplayName("Raw TypeSafe call failures are classified the same way as wrapped ones")
    void classifies_unwrapped_failures() {
        assertThat(ModelFailures.isOutage(new ModelProviderException(502))).isTrue();
        assertThat(ModelFailures.isOutage(new ModelProviderException(404))).isFalse();
        assertThat(ModelFailures.isOutage(new HttpTimeoutException("timed out"))).isTrue();
    }

    private static ModelUnavailableException unavailable(Throwable cause) {
        return new ModelUnavailableException("failed", cause);
    }
}
