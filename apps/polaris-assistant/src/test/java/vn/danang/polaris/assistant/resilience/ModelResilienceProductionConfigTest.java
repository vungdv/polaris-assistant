package vn.danang.polaris.assistant.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;

/** The production {@code application.yml} breaker and retry settings bind to the intended values. */
class ModelResilienceProductionConfigTest {

    private static final String MAIN_CONFIG = "src/main/resources/application.yml";

    @Test
    @DisplayName("Breakers open at 50 % failures for 30 s (Gemini over the last 20 calls, TypeSafe over the last 60 s); Gemini retries twice from 500 ms with jitter")
    void production_settings() throws IOException {
        PropertySource<?> main = new YamlPropertySourceLoader()
                .load("main-application.yml", new FileSystemResource(MAIN_CONFIG)).get(0);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class))
                .withUserConfiguration(ModelCircuitBreakers.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(main))
                .run(context -> {
                    CircuitBreakerRegistry breakers = context.getBean(CircuitBreakerRegistry.class);
                    for (String name : new String[] {ModelCircuitBreakers.GEMINI, ModelCircuitBreakers.TYPESAFE}) {
                        CircuitBreakerConfig config = breakers.circuitBreaker(name).getCircuitBreakerConfig();
                        assertThat(config.getMinimumNumberOfCalls()).as(name).isEqualTo(10);
                        assertThat(config.getFailureRateThreshold()).as(name).isEqualTo(50f);
                        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).as(name).isEqualTo(30_000L);
                        assertThat(config.getIgnoreExceptionPredicate().test(new IllegalStateException())).as(name).isTrue();
                    }

                    CircuitBreakerConfig gemini = breakers.circuitBreaker(ModelCircuitBreakers.GEMINI).getCircuitBreakerConfig();
                    assertThat(gemini.getSlidingWindowType()).isEqualTo(SlidingWindowType.COUNT_BASED);
                    assertThat(gemini.getSlidingWindowSize()).isEqualTo(20);
                    CircuitBreakerConfig typeSafe = breakers.circuitBreaker(ModelCircuitBreakers.TYPESAFE).getCircuitBreakerConfig();
                    assertThat(typeSafe.getSlidingWindowType()).isEqualTo(SlidingWindowType.TIME_BASED);
                    assertThat(typeSafe.getSlidingWindowSize()).isEqualTo(60);

                    RetryConfig retry = context.getBean(RetryRegistry.class).getConfiguration(ModelCircuitBreakers.GEMINI)
                            .orElseThrow();
                    assertThat(retry.getMaxAttempts()).isEqualTo(2);
                    long firstWait = retry.<Object>getIntervalBiFunction().apply(1, Either.left(new RuntimeException()));
                    assertThat(Duration.ofMillis(firstWait)).isBetween(Duration.ofMillis(250), Duration.ofMillis(750));
                });
    }
}
