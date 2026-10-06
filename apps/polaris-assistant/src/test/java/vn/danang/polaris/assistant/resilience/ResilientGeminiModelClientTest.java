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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.ai.GeminiAiModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * The real {@link GeminiAiModelClient} behind {@link ResilientGeminiModelClient}, against WireMock fault injection,
 * with small breaker and retry settings (window of 4 calls, 50 ms backoff).
 */
class ResilientGeminiModelClientTest {

    @RegisterExtension
    static WireMockExtension gemini = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/gemini-3.6-flash:generateContent";
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    private static final String REPLY = """
            { "candidates": [{ "content": { "role": "model", "parts": [ { "text": "Hello there!" } ] } }] }
            """;

    private CircuitBreaker circuitBreaker;
    private Tracer tracer;
    private Span span;
    private ResilientGeminiModelClient client;

    @BeforeEach
    void setUp() {
        AssistantAiProperties properties = new AssistantAiProperties();
        properties.setApiKey("test-wiremock-api-key");
        properties.setModel("gemini-3.6-flash");
        properties.setBaseUrl(gemini.baseUrl());
        properties.setTimeoutSeconds(1);

        CircuitBreakerConfig.Builder breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(OPEN_DURATION);
        new ModelCircuitBreakers().geminiCircuitBreakerCustomizer().customize(breakerConfig);
        circuitBreaker = CircuitBreaker.of(ModelCircuitBreakers.GEMINI, breakerConfig.build());

        RetryRegistry retries = RetryRegistry.of(Map.of(ModelCircuitBreakers.GEMINI, RetryConfig.custom()
                .maxAttempts(2)
                .intervalBiFunction(IntervalBiFunction.ofIntervalFunction(
                        IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(50), 2, 0.5)))
                .build()));

        tracer = mock(Tracer.class);
        span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);

        client = new ResilientGeminiModelClient(new GeminiAiModelClient(properties), circuitBreaker, retries,
                new StaticListableBeanFactory(Map.of("tracer", tracer)).getBeanProvider(Tracer.class));
    }

    @Test
    @DisplayName("Given Gemini answers 503 then 200, when generating, then one retry succeeds")
    void retries_a_server_error_once_and_succeeds() {
        stubFirstThen(aResponse().withStatus(503), ok());

        ModelResponse response = generate();

        assertThat(response.text()).isEqualTo("Hello there!");
        gemini.verify(2, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Given Gemini resets the connection then answers, when generating, then one retry succeeds")
    void retries_a_connection_reset_and_succeeds() {
        stubFirstThen(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), ok());

        assertThat(generate().text()).isEqualTo("Hello there!");
        gemini.verify(2, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
    }

    @Test
    @DisplayName("Given Gemini keeps answering 503, when generating, then it stops after 2 attempts with 503")
    void stops_after_two_attempts() {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> generate()).isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("HTTP 503");
        gemini.verify(2, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given Gemini answers 429 with Retry-After 1, when generating, then the retry waits at least that long")
    void waits_retry_after_before_retrying() {
        stubFirstThen(aResponse().withStatus(429).withHeader("Retry-After", "1"), ok());

        assertThat(generateBefore(Instant.now().plusSeconds(5)).text()).isEqualTo("Hello there!");

        List<LoggedRequest> requests = gemini.findAll(postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(requests).hasSize(2);
        assertThat(Duration.between(requests.get(0).getLoggedDate().toInstant(), requests.get(1).getLoggedDate().toInstant()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(990));
    }

    @Test
    @DisplayName("Given Gemini answers 429 with a Retry-After past the turn deadline, when generating, then no retry and the Retry-After is passed on")
    void does_not_retry_past_the_turn_deadline() {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

        ModelUnavailableException failure = catchThrowableOfType(ModelUnavailableException.class,
                () -> generateBefore(Instant.now().plusSeconds(5)));

        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(30));
        gemini.verify(1, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
    }

    @Test
    @DisplayName("Given Gemini times out, when generating, then it is not retried but counts as a breaker failure")
    void does_not_retry_a_request_timeout() {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).willReturn(ok().withFixedDelay(2_000)));

        assertThatThrownBy(() -> generate()).isInstanceOf(ModelUnavailableException.class);

        gemini.verify(1, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given Gemini rejects our request with 400, when generating, then no retry and the breaker does not count it")
    void does_not_retry_or_count_a_rejected_request() {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).willReturn(aResponse().withStatus(400)));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> generate()).isInstanceOf(ModelUnavailableException.class);
        }

        gemini.verify(5, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Given 4 failed calls opened the breaker, when generating, then it fails in under 50 ms without an HTTP request, with Retry-After until half-open")
    void open_breaker_fails_fast_without_calling_gemini() {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> generate()).isInstanceOf(ModelUnavailableException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        gemini.resetRequests();

        long started = System.nanoTime();
        ModelUnavailableException failure = catchThrowableOfType(ModelUnavailableException.class, () -> generate());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofMillis(50));
        gemini.verify(0, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(failure.modelCall()).isNull();
        assertThat(failure.retryAfter()).hasValueSatisfying(retryAfter -> assertThat(retryAfter)
                .isPositive().isLessThanOrEqualTo(OPEN_DURATION));
    }

    @Test
    @DisplayName("When a call fails, then the active span is tagged with the breaker state")
    void tags_breaker_state_on_the_active_span() {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).willReturn(aResponse().withStatus(500)));
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> generate()).isInstanceOf(ModelUnavailableException.class);

        verify(span).tag("circuit_breaker.gemini.state", "OPEN");
    }

    private ModelResponse generate() {
        return generate(new ModelRequestContext(1, "general.conversation", 1.0, 0, "session-1"));
    }

    private ModelResponse generateBefore(Instant deadline) {
        return generate(new ModelRequestContext(1, "general.conversation", 1.0, 0, "session-1", deadline));
    }

    private ModelResponse generate(ModelRequestContext context) {
        AssistantMessage message = new AssistantMessage();
        message.setRole(MessageRole.USER);
        message.setContent("hello");
        return client.generateResponse(List.of(message), List.of(), context);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder ok() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(REPLY);
    }

    private static void stubFirstThen(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder first,
            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder then) {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(first).willSetStateTo("failed-once"));
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH)).inScenario("retry").whenScenarioStateIs("failed-once")
                .willReturn(then));
    }
}
