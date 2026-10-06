package vn.danang.polaris.assistant.intent;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

class TypeSafeIntentClassifierTest {

    private AssistantTypeSafeProperties properties;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private TypeSafeIntentClassifier classifier;

    private final List<IntentDefinition> intents = List.of(
            new IntentDefinition("general.conversation", "Greetings and small talk", List.of("hello")),
            new IntentDefinition("catalog.product.lookup", "Details on a specific SKU", List.of("is SM-PH-001 in stock")),
            new IntentDefinition("information.lookup.order.status", "Status of a known order", List.of("where is order ORD-1001"))
    );

    @BeforeEach
    void setUp() {
        properties = new AssistantTypeSafeProperties();
        httpClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        classifier = new TypeSafeIntentClassifier(properties, httpClient, objectMapper);
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid API key, when classify is invoked, then sends Bearer auth and returns the chosen intent with confidence")
        @SuppressWarnings("unchecked")
        void classifies_via_choice_request_and_returns_result() throws Exception {
            properties.setApiKey("test-typesafe-key");

            String responseBody = """
                    {
                      "model": "jev-1.13.0",
                      "answers": {
                        "intent": {
                          "type": "choice",
                          "choice": "information.lookup.order.status",
                          "probabilities": {"information.lookup.order.status": 0.9},
                          "confidence": 0.87
                        }
                      }
                    }
                    """;
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(responseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            IntentClassification result = classifier.classify("what's going on with order ORD-1001", List.of(), intents);

            assertThat(result.intentId()).isEqualTo("information.lookup.order.status");
            assertThat(result.confidence()).isEqualTo(0.87);

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());
            HttpRequest capturedRequest = requestCaptor.getValue();

            assertThat(capturedRequest.uri().toString()).isEqualTo("https://api.typesafe.ai/v1/systemone");
            assertThat(capturedRequest.headers().firstValue("Authorization")).hasValue("Bearer test-typesafe-key");
            assertThat(capturedRequest.headers().firstValue("Content-Type")).hasValue("application/json");
        }

        @Test
        @DisplayName("Given the intent taxonomy, when classify is invoked, then sends every intent as a Choice criterion")
        @SuppressWarnings("unchecked")
        void sends_every_intent_as_choice_criterion() throws Exception {
            properties.setApiKey("test-typesafe-key");
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("""
                    {"answers": {"intent": {"choice": "general.conversation", "confidence": 0.99}}}
                    """);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            classifier.classify("hello there", List.of(), intents);

            String payload = extractRequestBody(captureRequest());
            assertThat(payload).contains("\"general.conversation\"");
            assertThat(payload).contains("\"catalog.product.lookup\"");
            assertThat(payload).contains("\"information.lookup.order.status\"");
            assertThat(payload).contains("\"type\":\"choice\"");
            assertThat(payload).contains("\"model\":\"jev-latest\"");
        }

        @Test
        @DisplayName("Given conversation history, when classify is invoked, then includes recent non-blank turns as state context")
        @SuppressWarnings("unchecked")
        void includes_recent_history_as_state_context() throws Exception {
            properties.setApiKey("test-typesafe-key");
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("""
                    {"answers": {"intent": {"choice": "commerce.order.cancel", "confidence": 0.95}}}
                    """);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            AssistantMessage priorUser = userMessage("I want to cancel order ORD-1001");
            AssistantMessage blankAssistant = new AssistantMessage();
            blankAssistant.setRole(MessageRole.ASSISTANT);
            blankAssistant.setContent("   ");

            classifier.classify("yes, cancel that one", List.of(priorUser, blankAssistant), intents);

            String payload = extractRequestBody(captureRequest());
            assertThat(payload).contains("\"I want to cancel order ORD-1001\"");
        }

        private HttpRequest captureRequest() throws Exception {
            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());
            return requestCaptor.getValue();
        }
    }

    // =========================================================================
    // 2. Invalid input & service error handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & service error handling")
    class InvalidInput {

        @ParameterizedTest(name = "Blank API key \"{0}\" fails closed without calling the network")
        @ValueSource(strings = {"", "   ", "\t\n"})
        @DisplayName("Given a blank API key, when classify is invoked, then fails closed to the default intent")
        void fails_closed_when_api_key_is_blank(String blankKey) throws Exception {
            properties.setApiKey(blankKey);

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
            assertThat(result.confidence()).isEqualTo(0.0);
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("Given an unresolved placeholder API key, when classify is invoked, then fails closed without calling the network")
        void fails_closed_when_api_key_is_unresolved_placeholder() throws Exception {
            properties.setApiKey("${TYPESAFE_API_KEY}");

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("Given an empty intent taxonomy, when classify is invoked, then fails closed without calling the network")
        void fails_closed_when_taxonomy_is_empty() throws Exception {
            properties.setApiKey("test-typesafe-key");

            IntentClassification result = classifier.classify("hello", List.of(), List.of());

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("Given the API returns a non-200 status, when classify is invoked, then fails closed to the default intent")
        @SuppressWarnings("unchecked")
        void fails_closed_on_non_200_status() throws Exception {
            properties.setApiKey("test-typesafe-key");
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(401);
            when(mockResponse.body()).thenReturn("{\"error\":\"unauthorized\"}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
            assertThat(result.confidence()).isEqualTo(0.0);
        }
    }

    // =========================================================================
    // 3. Edge cases — network failure, malformed responses
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given a network IOException, when classify is invoked, then fails closed to the default intent")
        @SuppressWarnings("unchecked")
        void fails_closed_on_network_io_exception() throws Exception {
            properties.setApiKey("test-typesafe-key");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
            assertThat(result.confidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Given default properties, when classify is invoked, then the request times out after 3 s and a timeout falls back to the default intent")
        @SuppressWarnings("unchecked")
        void times_out_after_3_seconds_and_falls_back() throws Exception {
            properties.setApiKey("test-typesafe-key");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new HttpTimeoutException("request timed out"));

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
            assertThat(result.fallbackReason()).isEqualTo("timeout");
            assertThat(result.modelCall()).isNotNull();
            assertThat(result.modelCall().isFailed()).isTrue();

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());
            assertThat(requestCaptor.getValue().timeout()).hasValue(Duration.ofSeconds(3));
        }

        @Test
        @DisplayName("Given a response missing the intent answer, when classify is invoked, then fails closed to the default intent")
        @SuppressWarnings("unchecked")
        void fails_closed_when_answer_missing() throws Exception {
            properties.setApiKey("test-typesafe-key");
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"answers\": {}}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
        }

        @Test
        @DisplayName("Given a response with a blank choice, when classify is invoked, then fails closed to the default intent")
        @SuppressWarnings("unchecked")
        void fails_closed_when_choice_is_blank() throws Exception {
            properties.setApiKey("test-typesafe-key");
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"answers\": {\"intent\": {\"choice\": \"\", \"confidence\": 0.5}}}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            IntentClassification result = classifier.classify("hello", List.of(), intents);

            assertThat(result.intentId()).isEqualTo(DefaultIntentResolver.DEFAULT_INTENT);
        }

        @Test
        @DisplayName("Given a base URL with a trailing slash, when classify is invoked, then normalizes the endpoint URL")
        @SuppressWarnings("unchecked")
        void normalizes_trailing_slash_in_base_url() throws Exception {
            properties.setApiKey("test-typesafe-key");
            properties.setBaseUrl("https://api.typesafe.ai/");
            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"answers\": {\"intent\": {\"choice\": \"general.conversation\", \"confidence\": 1.0}}}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockResponse);

            classifier.classify("hello", List.of(), intents);

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());
            assertThat(requestCaptor.getValue().uri().toString()).isEqualTo("https://api.typesafe.ai/v1/systemone");
        }
    }

    private String extractRequestBody(HttpRequest request) {
        if (request.bodyPublisher().isEmpty()) {
            return "";
        }
        var flowSubscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
        request.bodyPublisher().get().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                flowSubscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                flowSubscriber.onNext(List.of(item));
            }

            @Override
            public void onError(Throwable throwable) {
                flowSubscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                flowSubscriber.onComplete();
            }
        });
        return flowSubscriber.getBody().toCompletableFuture().join();
    }

    private AssistantMessage userMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
