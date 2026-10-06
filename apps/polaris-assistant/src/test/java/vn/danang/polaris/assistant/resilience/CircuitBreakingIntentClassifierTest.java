package vn.danang.polaris.assistant.resilience;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.DefaultIntentResolver;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.TypeSafeIntentClassifier;

/**
 * The real {@link TypeSafeIntentClassifier} behind {@link CircuitBreakingIntentClassifier}, against WireMock fault
 * injection, with a breaker that opens after 4 failed calls.
 */
class CircuitBreakingIntentClassifierTest {

    @RegisterExtension
    static WireMockExtension typeSafe = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String SYSTEM_ONE_PATH = "/v1/systemone";
    private static final String ORDER_PLACE_INTENT = """
            { "answers": { "intent": { "type": "choice", "choice": "commerce.order.place", "confidence": 0.99 } } }
            """;

    private AssistantTypeSafeProperties properties;
    private CircuitBreaker circuitBreaker;
    private Span span;
    private CircuitBreakingIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        properties = new AssistantTypeSafeProperties();
        properties.setApiKey("test-wiremock-typesafe-key");
        properties.setBaseUrl(typeSafe.baseUrl());
        properties.setTimeoutSeconds(1);

        CircuitBreakerConfig.Builder breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30));
        new ModelCircuitBreakers().typeSafeCircuitBreakerCustomizer().customize(breakerConfig);
        circuitBreaker = CircuitBreaker.of(ModelCircuitBreakers.TYPESAFE, breakerConfig.build());

        Tracer tracer = mock(Tracer.class);
        span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);

        classifier = new CircuitBreakingIntentClassifier(new TypeSafeIntentClassifier(properties), circuitBreaker,
                new StaticListableBeanFactory(Map.of("tracer", tracer)).getBeanProvider(Tracer.class));
    }

    @Test
    @DisplayName("Given TypeSafe answers, when classifying, then the classification passes through and counts as a success")
    void passes_classification_through() {
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(ORDER_PLACE_INTENT)));

        IntentClassification result = classify();

        assertThat(result.intentId()).isEqualTo("commerce.order.place");
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given the breaker opened after 4 failures, when TypeSafe hangs, then the turn falls back to general.conversation without waiting or calling it")
    void open_breaker_falls_back_without_waiting() {
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        for (int i = 0; i < 4; i++) {
            assertThat(classify().fallbackReason()).isEqualTo("io_error");
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        typeSafe.resetAll();
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse()
                .withStatus(200).withFixedDelay(5_000).withBody(ORDER_PLACE_INTENT)));
        clearInvocations(span);

        long started = System.nanoTime();
        IntentClassification result = classify();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
        assertThat(result.fallback()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo("circuit_open");
        assertThat(result.modelCall()).isNull();
        assertThat(elapsed).isLessThan(Duration.ofMillis(50));
        typeSafe.verify(0, postRequestedFor(urlEqualTo(SYSTEM_ONE_PATH)));
        verify(span).tag("circuit_breaker.typeSafe.state", "OPEN");
    }

    @Test
    @DisplayName("Given TypeSafe answers 503 or times out, when classifying, then each counts as a breaker failure")
    void server_errors_and_timeouts_count_as_failures() {
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse().withStatus(503)));
        classify();
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        classify();

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("Given TypeSafe rejects our request with 400, when classifying repeatedly, then the breaker stays closed")
    void rejected_requests_do_not_open_the_breaker() {
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse().withStatus(400)));

        for (int i = 0; i < 5; i++) {
            assertThat(classify().fallbackReason()).isEqualTo("http_status_400");
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("Given no TypeSafe API key, when classifying, then TypeSafe is not called and the breaker records nothing")
    void classification_without_a_call_is_not_recorded() {
        properties.setApiKey("");

        assertThat(classify().fallbackReason()).isEqualTo("missing_api_key");

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private IntentClassification classify() {
        return classifier.classify("order 2 chargers", List.of(), new DefaultIntentManager().listIntents());
    }
}
