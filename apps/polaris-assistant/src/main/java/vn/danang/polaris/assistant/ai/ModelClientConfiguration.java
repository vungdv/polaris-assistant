package vn.danang.polaris.assistant.ai;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.config.ModelProviderProperties;
import vn.danang.polaris.assistant.config.QwenProperties;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;
import vn.danang.polaris.assistant.resilience.ModelCircuitBreakers;
import vn.danang.polaris.assistant.resilience.ResilientModelClient;

/** Wires each provider client behind its circuit breaker and exposes the selected one as the primary client. */
@Configuration
class ModelClientConfiguration {

    private static final String PROVIDER_QWEN = GenAiTelemetry.PROVIDER_QWEN;

    @Bean
    ResilientModelClient geminiModelClient(GeminiAiModelClient gemini, CircuitBreakerRegistry circuitBreakers,
            RetryRegistry retries, ObjectProvider<Tracer> tracer) {
        return new ResilientModelClient(ModelCircuitBreakers.GEMINI, gemini, circuitBreakers, retries, tracer);
    }

    @Bean
    ResilientModelClient qwenResilientModelClient(OpenAiCompatibleModelClient qwen,
            CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, ObjectProvider<Tracer> tracer) {
        return new ResilientModelClient(ModelCircuitBreakers.QWEN, qwen, circuitBreakers, retries, tracer);
    }

    /**
     * Registers each provider behind its own breaker; the default provider stays Gemini.
     *
     * @throws IllegalStateException when the provider is unknown, or {@code qwen} has no base URL
     */
    @Bean
    @Primary
    DelegatingModelClient delegatingModelClient(ModelProviderProperties properties, QwenProperties qwen,
            @Qualifier("geminiModelClient") ResilientModelClient geminiModelClient,
            @Qualifier("qwenResilientModelClient") ResilientModelClient qwenModelClient) {
        if (PROVIDER_QWEN.equals(properties.getProvider())
                && (qwen.getBaseUrl() == null || qwen.getBaseUrl().isBlank())) {
            throw new IllegalStateException(
                    "polaris.ai.provider=qwen requires QWEN_BASE_URL (polaris.ai.qwen.base-url), for example http://localhost:11434/v1");
        }
        return new DelegatingModelClient(properties,
                Map.of(ModelCircuitBreakers.GEMINI, geminiModelClient, ModelCircuitBreakers.QWEN, qwenModelClient));
    }
}
