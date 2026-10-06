package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * The provider-call report on {@link ModelResponse#modelCall()}: what the client saw on the wire, with no
 * telemetry involved.
 */
class GeminiAiModelClientModelCallTest {

    private HttpClient httpClient;
    private AssistantAiProperties properties;
    private GeminiAiModelClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        properties = new AssistantAiProperties();
        properties.setApiKey("test-valid-api-key");
        properties.setModel("gemini-3.6-flash");
        client = new GeminiAiModelClient(properties, httpClient, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @DisplayName("Given a tool-call response with usage, when generating, then reports model, ids, token usage and tool_calls finish reason")
    void reports_successful_call() throws Exception {
        respondWith(200, """
                {
                  "responseId": "resp-123",
                  "modelVersion": "gemini-3.6-flash-001",
                  "candidates": [{ "content": { "role": "model", "parts": [
                    { "functionCall": { "name": "search_available_products", "args": {} } }
                  ] } }],
                  "usageMetadata": { "promptTokenCount": 120, "candidatesTokenCount": 30, "thoughtsTokenCount": 12,
                                     "cachedContentTokenCount": 100 }
                }
                """);

        ModelCall call = client.generateResponse(List.of(userMessage()), List.of(), ModelRequestContext.empty()).modelCall();

        assertThat(call).isEqualTo(ModelCall.succeeded("gemini-3.6-flash", "gemini-3.6-flash-001", "resp-123",
                new ModelTokenUsage(120, 30, 12, 100), "tool_calls"));
    }

    @Test
    @DisplayName("Given a text-only response, when generating, then reports a stop finish reason")
    void reports_stop_finish_reason() throws Exception {
        respondWith(200, """
                { "candidates": [{ "content": { "role": "model", "parts": [{ "text": "Hi" }] } }] }
                """);

        ModelCall call = client.generateResponse(List.of(userMessage()), List.of(), ModelRequestContext.empty()).modelCall();

        assertThat(call.finishReason()).isEqualTo("stop");
        assertThat(call.usage()).isEqualTo(ModelTokenUsage.NONE);
        assertThat(call.isFailed()).isFalse();
    }

    @Test
    @DisplayName("Given Gemini answers HTTP 429, when generating, then throws carrying a failed call with the status")
    void reports_http_failure_with_status() throws Exception {
        respondWith(429, """
                { "error": { "message": "Resource has been exhausted" } }
                """);

        assertThatThrownBy(() -> client.generateResponse(List.of(userMessage()), List.of(), ModelRequestContext.empty()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> assertThat(e.modelCall().failure())
                        .isInstanceOfSatisfying(ModelProviderException.class, cause -> assertThat(cause.statusCode()).isEqualTo(429)));
    }

    @Test
    @DisplayName("Given the network fails, when generating, then throws carrying a failed call with the I/O error")
    @SuppressWarnings("unchecked")
    void reports_transport_failure() throws Exception {
        IOException failure = new IOException("Connection reset");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(failure);

        assertThatThrownBy(() -> client.generateResponse(List.of(userMessage()), List.of(), ModelRequestContext.empty()))
                .isInstanceOfSatisfying(ModelUnavailableException.class,
                        e -> assertThat(e.modelCall()).isEqualTo(ModelCall.failed("gemini-3.6-flash", failure)));
    }

    @Test
    @DisplayName("Given no API key or no model configured, when generating, then reports no model call")
    void reports_no_call_when_gemini_is_not_reached() {
        properties.setApiKey("");
        assertThat(client.generateResponse(List.of(userMessage()), List.of(), ModelRequestContext.empty()).modelCall()).isNull();

        properties.setApiKey("test-valid-api-key");
        properties.setModel("");
        assertThatThrownBy(() -> client.generateResponse(List.of(userMessage()), List.of(), ModelRequestContext.empty()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> assertThat(e.modelCall()).isNull());
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private static AssistantMessage userMessage() {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent("Hello");
        return msg;
    }
}
