package vn.danang.polaris.assistant.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Nullable;

/**
 * The model provider could not produce a reply: it was unreachable, timed out, or answered with a non-2xx status.
 * Raised instead of returning error text as a reply, so callers fail loudly (HTTP 503, or 500 when the provider
 * rejected our own request) and never persist or show provider error details to the user.
 * <p>
 * Carries the failed {@link ModelCall} (absent when the provider was never reached, e.g. missing configuration) so
 * GenAI telemetry still records the failed generation, and the provider's {@code Retry-After} hint when it sent one.
 */
public class ModelUnavailableException extends RuntimeException implements ModelCallResult {

    @Nullable
    private final transient ModelCall modelCall;
    @Nullable
    private final Duration retryAfter;

    /** The provider was never reached. */
    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.modelCall = null;
        this.retryAfter = null;
    }

    /** The provider was never reached; calling again makes sense after {@code retryAfter}. */
    public ModelUnavailableException(String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.modelCall = null;
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    /** The provider call failed without a {@code Retry-After} hint. */
    public ModelUnavailableException(String message, Throwable cause, ModelCall modelCall) {
        super(message, cause);
        this.modelCall = Objects.requireNonNull(modelCall, "modelCall");
        this.retryAfter = null;
    }

    /** The provider call failed and the provider asked us to wait {@code retryAfter}. */
    public ModelUnavailableException(String message, Throwable cause, ModelCall modelCall, Duration retryAfter) {
        super(message, cause);
        this.modelCall = Objects.requireNonNull(modelCall, "modelCall");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
    }

    @Override
    @Nullable
    public ModelCall modelCall() {
        return modelCall;
    }

    /** How long the provider asked us to wait before calling again, when it said so. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
