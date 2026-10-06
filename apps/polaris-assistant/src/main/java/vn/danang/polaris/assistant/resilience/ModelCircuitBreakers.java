package vn.danang.polaris.assistant.resilience;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import vn.danang.polaris.assistant.ai.ModelFailures;

/**
 * One circuit breaker per AI model provider, configured under {@code resilience4j.circuitbreaker.instances}.
 * Only provider outages ({@link ModelFailures#isOutage}) are recorded; any other failure is ignored.
 * <p>
 * Each breaker's state is shown on {@code /actuator/health} as component {@code circuitBreakers}; it is not in
 * the readiness group and an open breaker does not change the aggregate status.
 */
@Configuration
public class ModelCircuitBreakers {

    public static final String GEMINI = "gemini";
    public static final String TYPESAFE = "typeSafe";

    static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    static final String CIRCUIT_HALF_OPEN = "CIRCUIT_HALF_OPEN";

    @Bean
    CircuitBreakerConfigCustomizer geminiCircuitBreakerCustomizer() {
        return recordOutagesOnly(GEMINI);
    }

    @Bean
    CircuitBreakerConfigCustomizer typeSafeCircuitBreakerCustomizer() {
        return recordOutagesOnly(TYPESAFE);
    }

    @Bean("circuitBreakers")
    CompositeHealthContributor circuitBreakersHealthContributor(CircuitBreakerRegistry registry) {
        return CompositeHealthContributor.fromMap(Map.of(
                GEMINI, stateIndicator(registry.circuitBreaker(GEMINI)),
                TYPESAFE, stateIndicator(registry.circuitBreaker(TYPESAFE))));
    }

    private static CircuitBreakerConfigCustomizer recordOutagesOnly(String name) {
        return CircuitBreakerConfigCustomizer.of(name,
                builder -> builder.ignoreException(failure -> !ModelFailures.isOutage(failure)));
    }

    static HealthIndicator stateIndicator(CircuitBreaker circuitBreaker) {
        return () -> switch (circuitBreaker.getState()) {
            case CLOSED -> Health.up().build();
            case OPEN, FORCED_OPEN -> Health.status(CIRCUIT_OPEN).build();
            case HALF_OPEN -> Health.status(CIRCUIT_HALF_OPEN).build();
            default -> Health.unknown().build();
        };
    }
}
