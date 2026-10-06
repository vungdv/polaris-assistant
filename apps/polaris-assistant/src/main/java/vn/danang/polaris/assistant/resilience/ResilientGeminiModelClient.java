package vn.danang.polaris.assistant.resilience;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

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
import vn.danang.polaris.assistant.ai.GeminiAiModelClient;
import vn.danang.polaris.assistant.ai.ModelFailures;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Guards {@link GeminiAiModelClient} with the {@value ModelCircuitBreakers#GEMINI} circuit breaker and, inside it,
 * the {@value ModelCircuitBreakers#GEMINI} retry ({@code resilience4j.retry.configs.gemini}).
 * <p>
 * Retries only {@link ModelFailures#isRetryable retryable} failures, waits the provider's {@code Retry-After}
 * when it sent one (otherwise the configured backoff), and never when that wait would end past the turn's
 * deadline. While the breaker is open, calls fail at once with a {@code Retry-After} of the time until it
 * half-opens.
 */
@Component
@Primary
public class ResilientGeminiModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientGeminiModelClient.class);

    private final AssistantModelClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final CircuitBreakerObserver observer;

    @Autowired
    public ResilientGeminiModelClient(GeminiAiModelClient delegate, CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries, ObjectProvider<Tracer> tracer) {
        this(delegate, circuitBreakers.circuitBreaker(ModelCircuitBreakers.GEMINI), retries, tracer);
    }

    ResilientGeminiModelClient(AssistantModelClient delegate, CircuitBreaker circuitBreaker, RetryRegistry retries,
            ObjectProvider<Tracer> tracer) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        RetryConfig configured = retries.getConfiguration(ModelCircuitBreakers.GEMINI).orElseGet(retries::getDefaultConfig);
        this.retry = retries.retry(ModelCircuitBreakers.GEMINI, retryConfig(configured));
        this.observer = new CircuitBreakerObserver(circuitBreaker, tracer);
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
            log.warn("Gemini circuit breaker is {}; failing fast, retry after {}", circuitBreaker.getState(), retryAfter);
            throw new ModelUnavailableException("Gemini circuit breaker is open", e, retryAfter);
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
