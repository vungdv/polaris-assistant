package vn.danang.polaris.assistant.resilience;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.intent.DefaultIntentResolver;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentClassifier;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.TypeSafeIntentClassifier;

/**
 * Guards {@link TypeSafeIntentClassifier} with the {@value ModelCircuitBreakers#TYPESAFE} circuit breaker. While
 * it is open, the turn falls back to {@value DefaultIntentResolver#DEFAULT_INTENT} at once, with reason
 * {@value #CIRCUIT_OPEN}. Classifications made without calling TypeSafe (no API key, empty taxonomy) don't
 * affect the breaker.
 */
@Component
@Primary
public class CircuitBreakingIntentClassifier implements IntentClassifier {

    static final String CIRCUIT_OPEN = "circuit_open";

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingIntentClassifier.class);

    private final IntentClassifier delegate;
    private final CircuitBreaker circuitBreaker;
    private final CircuitBreakerObserver observer;

    @Autowired
    public CircuitBreakingIntentClassifier(TypeSafeIntentClassifier delegate, CircuitBreakerRegistry circuitBreakers,
            ObjectProvider<Tracer> tracer) {
        this(delegate, circuitBreakers.circuitBreaker(ModelCircuitBreakers.TYPESAFE), tracer);
    }

    CircuitBreakingIntentClassifier(IntentClassifier delegate, CircuitBreaker circuitBreaker,
            ObjectProvider<Tracer> tracer) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.observer = new CircuitBreakerObserver(circuitBreaker, tracer);
    }

    @Override
    public IntentClassification classify(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("TypeSafe circuit breaker is {}; falling back to {}", circuitBreaker.getState(),
                    DefaultIntentResolver.DEFAULT_INTENT);
            observer.tagState();
            return IntentClassification.fallback(DefaultIntentResolver.DEFAULT_INTENT, CIRCUIT_OPEN);
        }
        long start = circuitBreaker.getCurrentTimestamp();
        try {
            IntentClassification result = delegate.classify(query, history, intents);
            long duration = circuitBreaker.getCurrentTimestamp() - start;
            result.findModelCall()
                    .ifPresentOrElse(call -> record(call, duration), circuitBreaker::releasePermission);
            return result;
        } catch (RuntimeException e) {
            circuitBreaker.onError(circuitBreaker.getCurrentTimestamp() - start, circuitBreaker.getTimestampUnit(), e);
            throw e;
        } finally {
            observer.tagState();
        }
    }

    private void record(ModelCall call, long duration) {
        if (call.isFailed()) {
            circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), call.failure());
        } else {
            circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
        }
    }
}
