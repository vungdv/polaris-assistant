package vn.danang.polaris.assistant.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.ai.OpenAiCompatibleModelClient;
import vn.danang.polaris.assistant.config.QwenProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * The real {@link OpenAiCompatibleModelClient} behind {@link ResilientModelClient} with the {@code qwen} breaker,
 * over real HTTP (WireMock), with small breaker and retry settings (window of 4 calls, 50 ms backoff).
 */
class ResilientQwenClientTest {

    @RegisterExtension
    static WireMockExtension qwen = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/v1/chat/completions";
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    private static final String REPLY = """
            {"id":"c1","model":"qwen3:4b","choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"Hello there!"}}]}""";

    private CircuitBreaker circuitBreaker;
    private ResilientModelClient client;

    @BeforeEach
    void setUp() {
        QwenProperties properties = new QwenProperties();
        properties.setBaseUrl(qwen.baseUrl() + "/v1");
        properties.setTimeoutSeconds(2);

        CircuitBreakerConfig.Builder breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_DURATION);
        new ModelCircuitBreakers().qwenCircuitBreakerCustomizer().customize(breakerConfig);
        circuitBreaker = CircuitBreaker.of(ModelCircuitBreakers.QWEN, breakerConfig.build());

        RetryRegistry retries = RetryRegistry.of(Map.of(ModelCircuitBreakers.QWEN, RetryConfig.custom()
                .maxAttempts(2)
                .intervalBiFunction(IntervalBiFunction.ofIntervalFunction(
                        IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(50), 2, 0.5)))
                .build()));

        client = new ResilientModelClient(ModelCircuitBreakers.QWEN, new OpenAiCompatibleModelClient(properties),
                circuitBreaker, retries,
                new StaticListableBeanFactory(Map.<String, Object>of()).getBeanProvider(Tracer.class));
    }

    @Test
    @DisplayName("Given Qwen answers 503 then 200, when generating, then one retry succeeds")
    void retries_a_server_error_once_and_succeeds() {
        stubFirstThen(aResponse().withStatus(503), ok());

        ModelResponse response = generate();

        assertThat(response.text()).isEqualTo("Hello there!");
        qwen.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Given Qwen answers 400, when generating, then it is not retried and not counted against the breaker")
    void does_not_retry_or_count_a_rejected_request() {
        qwen.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(this::generate).isInstanceOf(ModelUnavailableException.class);

        qwen.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Given 4 failed calls opened the qwen breaker, when generating, then it fails fast without an HTTP request")
    void open_breaker_fails_fast_without_calling_qwen() {
        qwen.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(this::generate).isInstanceOf(ModelUnavailableException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        qwen.resetRequests();

        ModelUnavailableException failure = catchThrowableOfType(ModelUnavailableException.class, this::generate);

        qwen.verify(0, postRequestedFor(urlEqualTo(PATH)));
        assertThat(failure).hasMessageContaining("qwen circuit breaker is open");
        assertThat(failure.modelCall()).isNull();
        assertThat(failure.retryAfter()).hasValueSatisfying(
                retryAfter -> assertThat(retryAfter).isPositive().isLessThanOrEqualTo(OPEN_DURATION));
    }

    private ModelResponse generate() {
        AssistantMessage message = new AssistantMessage();
        message.setRole(MessageRole.USER);
        message.setContent("hello");
        return client.generateResponse(List.of(message), List.of());
    }

    private static ResponseDefinitionBuilder ok() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(REPLY);
    }

    private static void stubFirstThen(ResponseDefinitionBuilder first, ResponseDefinitionBuilder then) {
        qwen.stubFor(post(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(first).willSetStateTo("failed-once"));
        qwen.stubFor(post(urlEqualTo(PATH)).inScenario("retry").whenScenarioStateIs("failed-once").willReturn(then));
    }
}
