package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Classifies model provider failures for the circuit breakers, the retry and the HTTP mapping. A
 * {@link ModelUnavailableException} is classified by its cause.
 */
public final class ModelFailures {

    private ModelFailures() {
    }

    /** The provider is failing: transport error, timeout, 429 or 5xx. Counts against its circuit breaker. */
    public static boolean isOutage(Throwable failure) {
        return unwrap(failure).map(cause -> providerStatus(cause)
                        .map(ModelFailures::isRateLimitOrServerError)
                        .orElseGet(() -> isTransportError(cause)))
                .orElse(false);
    }

    /** Worth calling again: connection error, 429 or 5xx. A request timeout is not; it already used its time. */
    public static boolean isRetryable(Throwable failure) {
        return unwrap(failure).map(cause -> providerStatus(cause)
                        .map(ModelFailures::isRateLimitOrServerError)
                        .orElseGet(() -> isTransportError(cause) && !isRequestTimeout(cause)))
                .orElse(false);
    }

    /** The provider rejected our request (4xx other than 429): our defect, not an outage. */
    public static boolean isRejectedRequest(Throwable failure) {
        return unwrap(failure).flatMap(ModelFailures::providerStatus)
                .map(status -> status >= 400 && status < 500 && status != 429)
                .orElse(false);
    }

    private static boolean isRateLimitOrServerError(int status) {
        return status == 429 || status >= 500;
    }

    private static boolean isTransportError(Throwable cause) {
        return cause instanceof IOException
                && !(cause instanceof TurnDeadlineExceededException)
                && !(cause instanceof JsonProcessingException);
    }

    private static boolean isRequestTimeout(Throwable cause) {
        return cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException);
    }

    private static Optional<Integer> providerStatus(Throwable cause) {
        return cause instanceof ModelProviderException provider ? Optional.of(provider.statusCode()) : Optional.empty();
    }

    /** A {@link ModelUnavailableException} is classified by its cause, which may be absent. */
    private static Optional<Throwable> unwrap(Throwable failure) {
        return failure instanceof ModelUnavailableException unavailable
                ? Optional.ofNullable(unavailable.getCause())
                : Optional.of(failure);
    }
}
