package vn.danang.polaris.assistant.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelFailures;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Guards one provider's {@link AssistantModelClient} with the circuit breaker named after the provider (for example
 * {@value ModelCircuitBreakers#GEMINI}) and, inside it, the retry of the same name
 * ({@code resilience4j.retry.configs.<provider>}).
 * <p>
 * Retries only {@link ModelFailures#isRetryable retryable} failures, waits the provider's {@code Retry-After}
 * when it sent one (otherwise the configured backoff), and never when that wait would end past the turn's
 * deadline. While the breaker is open, calls fail at once with a {@code Retry-After} of the time until it
 * half-opens.
 */
public class ResilientModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientModelClient.class);

    private final String provider;
    private final AssistantModelClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final CircuitBreakerObserver observer;

    public ResilientModelClient(String provider, AssistantModelClient delegate, CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries, ObjectProvider<Tracer> tracer) {
        this(provider, delegate, circuitBreakers.circuitBreaker(provider), retries, tracer);
    }

    ResilientModelClient(String provider, AssistantModelClient delegate, CircuitBreaker circuitBreaker,
            RetryRegistry retries, ObjectProvider<Tracer> tracer) {
        this.provider = provider;
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        RetryConfig configured = retries.getConfiguration(provider).orElseGet(retries::getDefaultConfig);
        this.retry = retries.retry(provider, retryConfig(configured));
        this.observer = new CircuitBreakerObserver(circuitBreaker, tracer);
    }

    @Override
    public Duration turnDeadline() {
        return delegate.turnDeadline();
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return generateResponse(messages, List.of()).text();
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return generateResponse(messages, tools, ModelRequestContext.empty());
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools,
            ModelRequestContext context) {
        ModelRequestContext callContext = Optional.ofNullable(context).orElseGet(ModelRequestContext::empty);
        Supplier<ModelResponse> retrying = Retry.decorateSupplier(retry, () -> attempt(messages, tools, callContext));
        try {
            return circuitBreaker.executeSupplier(() -> {
                try {
                    return retrying.get();
                } catch (RetryableFailure exhausted) {
                    throw exhausted.failure;
                }
            });
        } catch (CallNotPermittedException e) {
            Duration retryAfter = observer.untilHalfOpen();
            log.warn("{} circuit breaker is {}; failing fast, retry after {}", provider, circuitBreaker.getState(),
                    retryAfter);
            throw new ModelUnavailableException(provider + " circuit breaker is open", e, retryAfter);
        } finally {
            observer.tagState();
        }
    }

    private ModelResponse attempt(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context) {
        try {
            return delegate.generateResponse(messages, tools, context);
        } catch (ModelUnavailableException e) {
            if (ModelFailures.isRetryable(e) && retryEndsBefore(context, e)) {
                throw new RetryableFailure(e);
            }
            throw e;
        }
    }

    /** A retry that first waits the provider's Retry-After must still start before the turn's deadline. */
    private static boolean retryEndsBefore(ModelRequestContext context, ModelUnavailableException failure) {
        Instant retryAt = Instant.now().plus(failure.retryAfter().orElse(Duration.ZERO));
        return context.deadline().map(retryAt::isBefore).orElse(true);
    }

    private static RetryConfig retryConfig(RetryConfig configured) {
        IntervalBiFunction<Object> backoff = configured.getIntervalBiFunction();
        return RetryConfig.from(configured)
                .retryOnException(RetryableFailure.class::isInstance)
                .intervalBiFunction((attempt, outcome) -> {
                    if (outcome.isLeft() && outcome.getLeft() instanceof RetryableFailure retryable) {
                        return retryable.failure.retryAfter().map(Duration::toMillis)
                                .orElseGet(() -> backoff.apply(attempt, outcome));
                    }
                    return backoff.apply(attempt, outcome);
                })
                .build();
    }

    /** Marks a failure the retry may repeat; unwrapped before the circuit breaker sees it. */
    private static final class RetryableFailure extends RuntimeException {

        private final ModelUnavailableException failure;

        RetryableFailure(ModelUnavailableException failure) {
            super(failure.getMessage(), failure, false, false);
            this.failure = failure;
        }
    }
}
