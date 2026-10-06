package vn.danang.polaris.assistant.intent;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;
import vn.danang.polaris.assistant.observability.genai.InMemoryGenAiTelemetry;

/**
 * The TypeSafe intent gate as its own GenAI agent ({@code polaris-intent-classifier}), so its traffic, latency
 * and errors chart separately from the assistant's Gemini calls.
 */
class TypeSafeIntentClassifierTelemetryTest {

    private static final List<IntentDefinition> INTENTS = List.of(
            new IntentDefinition("information.lookup.order.status", "Order status lookup", List.of(), List.of(), 0.5));

    private InMemoryGenAiTelemetry telemetry;
    private HttpClient httpClient;
    private AssistantTypeSafeProperties properties;
    private TypeSafeIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        telemetry = new InMemoryGenAiTelemetry();
        httpClient = mock(HttpClient.class);
        properties = new AssistantTypeSafeProperties();
        properties.setApiKey("test-typesafe-key");
        classifier = telemetry.observed(new TypeSafeIntentClassifier(properties, httpClient, new ObjectMapper().findAndRegisterModules()));
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    @Test
    @DisplayName("Given a classification, when classify is invoked, then records a typesafe generation under the classifier agent")
    void records_generation_under_classifier_agent() throws Exception {
        respondWith(200, """
                { "answers": { "intent": { "type": "choice", "choice": "information.lookup.order.status", "confidence": 0.87 } } }
                """);

        IntentClassification result = classifier.classify("where is my order", List.of(), INTENTS);

        assertThat(result.intentId()).isEqualTo("information.lookup.order.status");
        SpanData span = telemetry.onlySpan();
        assertThat(span.getName()).isEqualTo("generateText jev-latest");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name"))).isEqualTo("typesafe");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name"))).isEqualTo("polaris-intent-classifier");
        assertThat(telemetry.histogramPoints("gen_ai.client.operation.duration")).singleElement()
                .satisfies(point -> assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name")))
                        .isEqualTo("polaris-intent-classifier"));
        assertThat(telemetry.client().debugSnapshot().getGenerations()).singleElement()
                .satisfies(generation -> assertThat(generation.getTags()).contains(
                        Map.entry("intent_result", "information.lookup.order.status"),
                        Map.entry("intent_confidence", "0.87"),
                        Map.entry("intent_fallback", "false"),
                        Map.entry("intents_count", "1")));
    }

    @Test
    @DisplayName("Given TypeSafe answers HTTP 500, when classify is invoked, then falls back and records a failed generation")
    void records_failed_generation_on_http_error() throws Exception {
        respondWith(500, "internal server error");

        IntentClassification result = classifier.classify("where is my order", List.of(), INTENTS);

        assertThat(result.fallback()).isTrue();
        SpanData span = telemetry.onlySpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("error.type"))).isEqualTo("provider_call_error");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("error.category"))).isEqualTo("server_error");
    }

    @Test
    @DisplayName("Given no API key, when classify is invoked, then the model is not consulted and nothing is recorded")
    void records_nothing_without_model_call() {
        properties.setApiKey("");

        classifier.classify("where is my order", List.of(), INTENTS);

        assertThat(telemetry.spans()).isEmpty();
        assertThat(telemetry.histogramPoints("gen_ai.client.operation.duration")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
