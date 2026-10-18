package vn.danang.polaris.assistant.ai;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.config.ModelProviderProperties;
import vn.danang.polaris.assistant.resilience.ModelCircuitBreakers;
import vn.danang.polaris.assistant.resilience.ResilientModelClient;

/** Wires each provider client behind its circuit breaker and exposes the selected one as the primary client. */
@Configuration
class ModelClientConfiguration {

    @Bean
    ResilientModelClient geminiModelClient(GeminiAiModelClient gemini, CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries, ObjectProvider<Tracer> tracer) {
        return new ResilientModelClient(ModelCircuitBreakers.GEMINI, gemini, circuitBreakers, retries, tracer);
    }

    @Bean
    @Primary
    DelegatingModelClient delegatingModelClient(ModelProviderProperties properties,
            ResilientModelClient geminiModelClient) {
        return new DelegatingModelClient(properties, Map.of(ModelCircuitBreakers.GEMINI, geminiModelClient));
    }
}
