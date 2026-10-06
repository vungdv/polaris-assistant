package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.trace.data.SpanData;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.observability.genai.InMemoryGenAiTelemetry;

/**
 * The Gemini call as seen by Grafana AI Observability: one OTel GenAI generation span plus the
 * {@code gen_ai.client.*} metrics, recorded by {@code @GenAiGeneration} (agento11y SDK) from the client's
 * {@link ModelCall}, against a real in-memory OTel SDK.
 */
class GeminiAiModelClientTelemetryTest {

    private static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("gen_ai.provider.name");
    private static final AttributeKey<String> REQUEST_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<String> AGENT_NAME = AttributeKey.stringKey("gen_ai.agent.name");
    private static final AttributeKey<String> TOKEN_TYPE = AttributeKey.stringKey("gen_ai.token.type");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final AttributeKey<String> ERROR_CATEGORY = AttributeKey.stringKey("error.category");

    private static final String DURATION = "gen_ai.client.operation.duration";
    private static final String TOKEN_USAGE = "gen_ai.client.token.usage";

    private static final String PROMPT = "My card is 4111 1111 1111 1111, find me a charger";
    private static final String COMPLETION = "Here is the SECRET-COMPLETION-TEXT you asked for";
    private static final String THOUGHT_SIGNATURE = "opaque-thought-signature-blob";

    private InMemoryGenAiTelemetry telemetry;
    private HttpClient httpClient;
    private AssistantAiProperties properties;
    private GeminiAiModelClient client;

    @BeforeEach
    void setUp() {
        telemetry = new InMemoryGenAiTelemetry();
        httpClient = mock(HttpClient.class);
        properties = new AssistantAiProperties();
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");
        client = telemetry.observed(new GeminiAiModelClient(properties, httpClient, new ObjectMapper().findAndRegisterModules()));
    }

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a tool-call response with usage, when generating, then records one GenAI generation span with semconv attributes")
        void records_generation_span_with_semconv_attributes() throws Exception {
            respondWith(200, """
                    {
                      "responseId": "resp-123",
                      "modelVersion": "gemini-3.6-flash-001",
                      "candidates": [{ "content": { "role": "model", "parts": [
                        { "functionCall": { "id": "call-1", "name": "search_available_products", "args": { "query": "charger" } } }
                      ] } }],
                      "usageMetadata": { "promptTokenCount": 28, "candidatesTokenCount": 45, "totalTokenCount": 73 }
                    }
                    """);

            ModelResponse response = client.generateResponse(List.of(userMessage("Find chargers")), List.of(tool()),
                    new ModelRequestContext(2, "catalog.product.search", 0.95, 1, "sess-1"));

            assertThat(response.hasToolCalls()).isTrue();
            SpanData span = telemetry.onlySpan();
            assertThat(span.getName()).isEqualTo("generateText gemini-3.6-flash");
            assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
            Attributes attributes = span.getAttributes();
            assertThat(attributes.get(PROVIDER)).isEqualTo("gemini");
            assertThat(attributes.get(REQUEST_MODEL)).isEqualTo("gemini-3.6-flash");
            assertThat(attributes.get(AGENT_NAME)).isEqualTo("polaris-assistant");
            assertThat(attributes.get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("generateText");
            assertThat(attributes.get(AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("sess-1");
            assertThat(attributes.get(AttributeKey.stringKey("gen_ai.response.model"))).isEqualTo("gemini-3.6-flash-001");
            assertThat(attributes.get(AttributeKey.stringKey("gen_ai.response.id"))).isEqualTo("resp-123");
            assertThat(attributes.get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))).containsExactly("tool_calls");
            assertThat(attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(28L);
            assertThat(attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(45L);
        }

        @Test
        @DisplayName("Given a ReAct request context, when generating, then carries it as generation tags, never as span or metric labels")
        void carries_request_context_as_generation_tags_only() throws Exception {
            respondWith(200, textResponse("Products found"));

            client.generateResponse(List.of(userMessage("Find products")), List.of(tool()),
                    new ModelRequestContext(2, "catalog.product.search", 0.95, 1, "sess-1"));

            assertThat(telemetry.client().debugSnapshot().getGenerations()).singleElement()
                    .satisfies(generation -> assertThat(generation.getTags()).containsOnly(
                            Map.entry("iteration", "2"),
                            Map.entry("intent_id", "catalog.product.search"),
                            Map.entry("intent_confidence", "0.95"),
                            Map.entry("tools_offered", "1")));
            assertThat(telemetry.onlySpan().getAttributes().asMap().keySet())
                    .noneMatch(key -> key.getKey().startsWith("agento11y.tag."));
            assertThat(telemetry.histogramPoints(DURATION))
                    .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet())
                            .noneMatch(key -> key.getKey().startsWith("agento11y.tag.")));
        }

        @Test
        @DisplayName("Given usage metadata, when generating, then records duration and per-type token metrics labelled by provider, model and agent")
        void records_duration_and_token_usage_metrics() throws Exception {
            respondWith(200, """
                    {
                      "candidates": [{ "content": { "role": "model", "parts": [{ "text": "Done" }] } }],
                      "usageMetadata": { "promptTokenCount": 120, "candidatesTokenCount": 30, "thoughtsTokenCount": 12,
                                         "cachedContentTokenCount": 100, "totalTokenCount": 162 }
                    }
                    """);

            client.generateResponse(List.of(userMessage("Hello")), List.of(), ModelRequestContext.empty());

            List<HistogramPointData> duration = telemetry.histogramPoints(DURATION);
            assertThat(duration).singleElement().satisfies(point -> {
                assertThat(point.getCount()).isEqualTo(1);
                assertThat(point.getAttributes().get(PROVIDER)).isEqualTo("gemini");
                assertThat(point.getAttributes().get(REQUEST_MODEL)).isEqualTo("gemini-3.6-flash");
                assertThat(point.getAttributes().get(AGENT_NAME)).isEqualTo("polaris-assistant");
                assertThat(point.getAttributes().get(ERROR_TYPE)).isEmpty();
            });
            assertThat(telemetry.metric(DURATION)).get().satisfies(m -> assertThat(m.getUnit()).isEqualTo("s"));

            Map<String, Double> tokensByType = telemetry.histogramPoints(TOKEN_USAGE).stream()
                    .collect(java.util.stream.Collectors.toMap(p -> p.getAttributes().get(TOKEN_TYPE), HistogramPointData::getSum));
            assertThat(tokensByType).containsOnly(
                    Map.entry("input", 120.0),
                    Map.entry("output", 30.0),
                    Map.entry("reasoning", 12.0),
                    Map.entry("cache_read", 100.0));
            assertThat(telemetry.histogramPoints(TOKEN_USAGE))
                    .allSatisfy(point -> assertThat(point.getAttributes().get(AttributeKey.stringKey("gen_ai.token.semantics")))
                            .isEqualTo("inclusive"));
        }
    }

    @Nested
    @DisplayName("2. Model errors")
    class ModelErrors {

        @Test
        @DisplayName("Given Gemini answers HTTP 429, when generating, then the generation fails with a rate_limit error on span and metric")
        void classifies_http_429_as_rate_limited_failure() throws Exception {
            respondWith(429, """
                    { "error": { "code": 429, "message": "Resource has been exhausted" } }
                    """);

            assertThatThrownBy(() -> client.generateResponse(List.of(userMessage("Hello")), List.of(), ModelRequestContext.empty()))
                    .isInstanceOf(ModelUnavailableException.class);

            SpanData span = telemetry.onlySpan();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().get(ERROR_TYPE)).isEqualTo("provider_call_error");
            assertThat(span.getAttributes().get(ERROR_CATEGORY)).isEqualTo("rate_limit");
            assertThat(telemetry.histogramPoints(DURATION)).singleElement()
                    .satisfies(point -> {
                        assertThat(point.getAttributes().get(ERROR_TYPE)).isEqualTo("provider_call_error");
                        assertThat(point.getAttributes().get(ERROR_CATEGORY)).isEqualTo("rate_limit");
                    });
            assertThat(telemetry.histogramPoints(TOKEN_USAGE)).isEmpty();
        }

        @Test
        @DisplayName("Given the network fails, when generating, then throws ModelUnavailableException and records a failed generation")
        @SuppressWarnings("unchecked")
        void records_failed_generation_on_network_error() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection reset"));

            assertThatThrownBy(() -> client.generateResponse(List.of(userMessage("Hello")), List.of(), ModelRequestContext.empty()))
                    .isInstanceOf(ModelUnavailableException.class);

            SpanData span = telemetry.onlySpan();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getAttributes().get(ERROR_TYPE)).isEqualTo("provider_call_error");
            assertThat(telemetry.histogramPoints(DURATION)).singleElement()
                    .satisfies(point -> assertThat(point.getAttributes().get(ERROR_TYPE)).isEqualTo("provider_call_error"));
        }
    }

    @Nested
    @DisplayName("3. Edge cases & data protection")
    class EdgeCases {

        @Test
        @DisplayName("Given no API key, when generating, then no model call happens and no generation is recorded")
        void records_nothing_for_local_fallback() {
            properties.setApiKey("");

            client.generateResponse(List.of(userMessage("Hello")), List.of(), ModelRequestContext.empty());

            assertThat(telemetry.spans()).isEmpty();
            assertThat(telemetry.histogramPoints(DURATION)).isEmpty();
        }

        @Test
        @DisplayName("Given sensitive prompt, completion and thought signature, when generating, then none reach span attributes or events")
        void keeps_prompt_completion_and_thought_signature_out_of_spans() throws Exception {
            respondWith(200, """
                    {
                      "candidates": [{ "content": { "role": "model", "parts": [
                        { "text": "%s", "thoughtSignature": "%s" }
                      ] } }],
                      "usageMetadata": { "promptTokenCount": 10, "candidatesTokenCount": 5 }
                    }
                    """.formatted(COMPLETION, THOUGHT_SIGNATURE));

            client.generateResponse(List.of(userMessage(PROMPT)), List.of(), ModelRequestContext.empty());

            SpanData span = telemetry.onlySpan();
            String exported = span.getAttributes() + " " + span.getEvents() + " " + span.getStatus();
            assertThat(exported).doesNotContain("4111", "SECRET-COMPLETION-TEXT", THOUGHT_SIGNATURE, "You are Polaris Assistant");
        }

        @Test
        @DisplayName("Given a live call, when sending the request, then no traceparent is sent to the third-party model API")
        @SuppressWarnings("unchecked")
        void does_not_send_trace_context_to_google() throws Exception {
            respondWith(200, textResponse("OK"));

            client.generateResponse(List.of(userMessage("Hello")), List.of(), ModelRequestContext.empty());

            ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
            assertThat(request.getValue().headers().firstValue("traceparent")).isEmpty();
        }

        @Test
        @DisplayName("Given a null request context, when generating, then still records the generation without a conversation id")
        void records_generation_without_request_context() throws Exception {
            respondWith(200, textResponse("OK"));

            ModelResponse response = client.generateResponse(List.of(userMessage("Hello")), List.of(), null);

            assertThat(response.text()).isEqualTo("OK");
            SpanData span = telemetry.onlySpan();
            assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.conversation.id"))).isNull();
            assertThat(span.getAttributes().get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))).containsExactly("stop");
        }
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private static String textResponse(String text) {
        return """
                { "candidates": [{ "content": { "role": "model", "parts": [{ "text": "%s" }] } }] }
                """.formatted(text);
    }

    private static Tool tool() {
        return Tool.builder("search_available_products", Map.of()).description("Search catalog products").build();
    }

    private static AssistantMessage userMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
