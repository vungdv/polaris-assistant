package vn.danang.polaris.assistant.intent;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelProviderException;
import vn.danang.polaris.assistant.ai.ModelTokenUsage;
import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;

/**
 * The provider-call report on {@link IntentClassification#modelCall()}, with no telemetry involved.
 */
class TypeSafeIntentClassifierModelCallTest {

    private static final List<IntentDefinition> INTENTS = List.of(
            new IntentDefinition("information.lookup.order.status", "Order status lookup", List.of(), List.of(), 0.5));

    private HttpClient httpClient;
    private AssistantTypeSafeProperties properties;
    private TypeSafeIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        properties = new AssistantTypeSafeProperties();
        properties.setApiKey("test-typesafe-key");
        classifier = new TypeSafeIntentClassifier(properties, httpClient, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("Given a classification, when classify is invoked, then reports a successful call to the configured model")
    void reports_successful_call() throws Exception {
        respondWith(200, """
                { "answers": { "intent": { "type": "choice", "choice": "information.lookup.order.status", "confidence": 0.87 } } }
                """);

        IntentClassification result = classifier.classify("where is my order", List.of(), INTENTS);

        assertThat(result.modelCall()).isEqualTo(ModelCall.succeeded("jev-latest", null, null, ModelTokenUsage.NONE, "stop"));
    }

    @Test
    @DisplayName("Given TypeSafe answers HTTP 500, when classify is invoked, then falls back and reports a failed call with the status")
    void reports_http_failure_with_status() throws Exception {
        respondWith(500, "internal server error");

        IntentClassification result = classifier.classify("where is my order", List.of(), INTENTS);

        assertThat(result.fallback()).isTrue();
        assertThat(result.modelCall().failure())
                .isInstanceOfSatisfying(ModelProviderException.class, e -> assertThat(e.statusCode()).isEqualTo(500));
    }

    @Test
    @DisplayName("Given the network fails, when classify is invoked, then falls back and reports the I/O error")
    @SuppressWarnings("unchecked")
    void reports_transport_failure() throws Exception {
        IOException failure = new IOException("Connection reset");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(failure);

        IntentClassification result = classifier.classify("where is my order", List.of(), INTENTS);

        assertThat(result.fallbackReason()).isEqualTo("io_error");
        assertThat(result.modelCall()).isEqualTo(ModelCall.failed("jev-latest", failure));
    }

    @Test
    @DisplayName("Given no API key or no taxonomy, when classify is invoked, then the model is not consulted and no call is reported")
    void reports_no_call_without_consulting_the_model() {
        assertThat(classifier.classify("where is my order", List.of(), List.of()).modelCall()).isNull();

        properties.setApiKey("");
        assertThat(classifier.classify("where is my order", List.of(), INTENTS).modelCall()).isNull();
    }

    @Test
    @DisplayName("Given a classification with a model call, when serialized, then the model call is not part of the JSON")
    void keeps_model_call_out_of_json() throws Exception {
        IntentClassification classification = new IntentClassification("x", 0.9)
                .withModelCall(ModelCall.failed("jev-latest", new IOException("boom")));

        String json = new ObjectMapper().writeValueAsString(classification);

        assertThat(json).doesNotContain("model", "boom");
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
}
