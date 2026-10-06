package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

class GeminiAiModelClientTest {

    private AssistantAiProperties properties;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private GeminiAiModelClient client;

    @BeforeEach
    void setUp() {
        properties = new AssistantAiProperties();
        httpClient = mock(HttpClient.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        client = new GeminiAiModelClient(properties, httpClient, objectMapper);
    }

    // =========================================================================
    // 1. Happy path — live model invocation, function calling & telemetry
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid API key, when chat is invoked, then sends x-goog-api-key header and returns reply")
        @SuppressWarnings("unchecked")
        void sends_api_key_header_and_returns_reply_when_configured() throws Exception {
            properties.setApiKey("test-valid-api-key-12345");
            properties.setModel("gemini-3.6-flash");

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "Hello! How can I help you today?"
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            AssistantMessage userMessage = createUserMessage("Hello Polaris!");

            String reply = client.chat(List.of(userMessage));

            assertThat(reply).isEqualTo("Hello! How can I help you today?");

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());

            HttpRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.uri().toString())
                    .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent");
            assertThat(capturedRequest.headers().firstValue("x-goog-api-key"))
                    .hasValue("test-valid-api-key-12345");
            assertThat(capturedRequest.headers().firstValue("Content-Type"))
                    .hasValue("application/json");
        }

        @Test
        @DisplayName("Given tool definitions, when generating response, then serializes functionDeclarations and parses tool call")
        @SuppressWarnings("unchecked")
        void serializes_tools_as_function_declarations_in_request_body() throws Exception {
            properties.setApiKey("test-api-key");
            properties.setModel("gemini-3.6-flash");

            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")
            );
            Tool tool = Tool.builder("search_available_products", schema)
                    .description("Search catalog products")
                    .build();

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "functionCall": {
                                  "name": "search_available_products",
                                  "args": {
                                    "query": "charger"
                                  }
                                }
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Find chargers")), List.of(tool));

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.name()).isEqualTo("search_available_products");
            assertThat(call.arguments()).containsEntry("query", "charger");
            assertThat(call.args()).containsEntry("query", "charger");
        }

        @Test
        @DisplayName("Given candidate part with functionCall containing id, name, and args, when parsed, then populates all fields in ToolCall")
        @SuppressWarnings("unchecked")
        void parses_tool_call_with_id_name_and_args() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "functionCall": {
                                  "id": "call_12345",
                                  "name": "lookup_customer_by_id",
                                  "args": {
                                    "customer_id": "CUST-007"
                                  }
                                }
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Lookup customer")), List.of());

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.id()).isEqualTo("call_12345");
            assertThat(call.name()).isEqualTo("lookup_customer_by_id");
            assertThat(call.args()).containsEntry("customer_id", "CUST-007");
            assertThat(call.arguments()).containsEntry("customer_id", "CUST-007");
        }

        @Test
        @DisplayName("Given TOOL message in history, when generating response, then serializes functionResponse in request payload")
        @SuppressWarnings("unchecked")
        void serializes_tool_message_as_function_response_in_request() throws Exception {
            properties.setApiKey("test-api-key");
            properties.setModel("gemini-3.6-flash");

            AssistantMessage userMsg = createUserMessage("Find chargers");

            AssistantMessage modelMsg = new AssistantMessage();
            modelMsg.setRole(MessageRole.ASSISTANT);
            modelMsg.setToolCallId("search_available_products");
            modelMsg.setWidgetPayload("{\"query\":\"charger\"}");

            AssistantMessage toolMsg = new AssistantMessage();
            toolMsg.setRole(MessageRole.TOOL);
            toolMsg.setToolCallId("search_available_products");
            toolMsg.setContent("Found 2 items");

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "Found 2 chargers in stock."
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            ModelResponse response = client.generateResponse(List.of(userMsg, modelMsg, toolMsg), List.of());

            assertThat(response.hasToolCalls()).isFalse();
            assertThat(response.text()).isEqualTo("Found 2 chargers in stock.");
        }

@Test
        @DisplayName("Given candidate part with thoughtSignature, when parsed, then populates thoughtSignature in ToolCall")
        @SuppressWarnings("unchecked")
        void parses_thought_signature_from_candidate_part() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "functionCall": {
                                  "name": "default_api:search_available_products",
                                  "args": { "query": "charger" }
                                },
                                "thoughtSignature": "sig_base64_encoded_12345"
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Find chargers")), List.of());

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.name()).isEqualTo("default_api:search_available_products");
            assertThat(call.thoughtSignature()).isEqualTo("sig_base64_encoded_12345");
        }

        @Test
        @DisplayName("Given candidate parts with thought text, when response parsed, then ignores internal thought text in reply")
        @SuppressWarnings("unchecked")
        void ignores_internal_thought_text_in_final_reply() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "thought": true,
                                "text": "Internal chain-of-thought: user wants a phone, looking at catalog...",
                                "thoughtSignature": "internal_sig_111"
                              },
                              {
                                "text": "Here are the top phones available in store.",
                                "thoughtSignature": "final_text_sig_222"
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Find phones")), List.of());

            assertThat(response.text()).isEqualTo("Here are the top phones available in store.");
            assertThat(response.thoughtSignature()).isEqualTo("final_text_sig_222");
        }

        @Test
        @DisplayName("Given saved thoughtSignature on message, when generating response, then serializes signature in request body")
        @SuppressWarnings("unchecked")
        void serializes_saved_thought_signature_in_request_body() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            AssistantMessage userMsg = createUserMessage("Find chargers");

            AssistantMessage modelMsg = new AssistantMessage();
            modelMsg.setRole(MessageRole.ASSISTANT);
            modelMsg.setToolCallId("default_api:search_available_products");
            modelMsg.setWidgetPayload("{\"query\":\"charger\"}");
            modelMsg.setThoughtSignature("sig_previous_turn_token_456");

            AssistantMessage toolMsg = new AssistantMessage();
            toolMsg.setRole(MessageRole.TOOL);
            toolMsg.setToolCallId("default_api:search_available_products");
            toolMsg.setContent("Found 2 items");

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Found 2 chargers\"}],\"role\":\"model\"}}]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            client.generateResponse(List.of(userMsg, modelMsg, toolMsg), List.of());

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());

            String requestPayload = extractRequestBody(requestCaptor.getValue());

            assertThat(requestPayload).contains("\"thoughtSignature\":\"sig_previous_turn_token_456\"");
            assertThat(requestPayload).contains("\"name\":\"default_api:search_available_products\"");
        }

}

    // =========================================================================
    // 2. Invalid input & model error handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & model error handling")
    class InvalidInput {

        @Test
        @DisplayName("Given null apiKey, when chat is invoked, then throws IllegalArgumentException")
        void rejects_null_api_key_with_illegal_argument_exception() {
            properties.setApiKey(null);

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Hello Polaris!"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.");

            try {
                verify(httpClient, never()).send(any(), any());
            } catch (Exception e) {
                // Ignore for mock verification
            }
        }

        @Test
        @DisplayName("Given API returns HTTP 400 error, when chat is invoked, then throws ModelUnavailableException without leaking the error text as a reply")
        @SuppressWarnings("unchecked")
        void throws_model_unavailable_when_api_returns_error_status() throws Exception {
            properties.setApiKey("test-invalid-key");

            String mockErrorResponseBody = """
                    {
                      "error": {
                        "code": 400,
                        "message": "API key not valid. Please pass a valid API key.",
                        "status": "INVALID_ARGUMENT"
                      }
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(400);
            when(mockResponse.body()).thenReturn(mockErrorResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> assertThat(e.getCause())
                            .isInstanceOfSatisfying(ModelProviderException.class, cause -> assertThat(cause.statusCode()).isEqualTo(400)));
        }

}

    // =========================================================================
    // 3. Edge cases — fallbacks, placeholders, network errors, null context & privacy
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @ParameterizedTest(name = "Blank API key \"{0}\" triggers local fallback")
        @ValueSource(strings = {"   ", "\t\n", " "})
        @DisplayName("Given blank apiKey, when chat is invoked, then returns local assistant fallback")
        void returns_local_fallback_when_api_key_is_blank(String blankKey) throws Exception {
            properties.setApiKey(blankKey);

            String reply = client.chat(List.of(createUserMessage("Hello Polaris!")));

            assertThat(reply).contains("Live AI Model key is not configured");
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("Given unresolved placeholder apiKey '${GEMINI_API_KEY}', when chat invoked, then returns fallback without URI syntax error")
        void returns_fallback_when_api_key_is_unresolved_placeholder() throws Exception {
            properties.setApiKey("${GEMINI_API_KEY}");

            String reply = client.chat(List.of(createUserMessage("Hello Polaris!")));

            assertThat(reply).contains("Live AI Model key is not configured");
            assertThat(reply).contains("Echo: \"Hello Polaris!\"");
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("Given unresolved placeholder and empty message list, when chat invoked, then returns fallback with empty echo")
        void returns_fallback_with_empty_echo_when_messages_empty() throws Exception {
            properties.setApiKey("${GEMINI_API_KEY}");

            String reply = client.chat(List.of());

            assertThat(reply).contains("Live AI Model key is not configured");
            assertThat(reply).contains("Echo: \"\"");
            verify(httpClient, never()).send(any(), any());
        }

        @Test
        @DisplayName("Given network failure with IOException, when chat invoked, then throws ModelUnavailableException carrying the cause")
        @SuppressWarnings("unchecked")
        void throws_model_unavailable_when_network_throws_io_exception() throws Exception {
            properties.setApiKey("test-key");
            IOException failure = new IOException("Connection refused");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(failure);

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOf(ModelUnavailableException.class)
                    .hasCause(failure);
        }

@Test
        @DisplayName("Given candidate part with snake_case thought_signature, when parsed, then populates thoughtSignature in ToolCall")
        @SuppressWarnings("unchecked")
        void parses_snake_case_thought_signature_from_candidate_part() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            String mockResponseBody = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "functionCall": {
                                  "name": "default_api:search_available_products",
                                  "args": { "query": "phone" },
                                  "thought_signature": "nested_snake_case_sig_999"
                                }
                              }
                            ],
                            "role": "model"
                          }
                        }
                      ]
                    }
                    """;

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn(mockResponseBody);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Find phone")), List.of());

            assertThat(response.hasToolCalls()).isTrue();
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.thoughtSignature()).isEqualTo("nested_snake_case_sig_999");
        }

        @Test
        @DisplayName("Given function call lacking signature, when serializing request, then injects bypass signature 'skip_thought_signature_validator'")
        @SuppressWarnings("unchecked")
        void injects_bypass_signature_when_function_call_lacks_thought_signature() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            AssistantMessage userMsg = createUserMessage("Find chargers");

            AssistantMessage modelMsg = new AssistantMessage();
            modelMsg.setRole(MessageRole.ASSISTANT);
            modelMsg.setToolCallId("default_api:search_available_products");
            modelMsg.setWidgetPayload("{\"query\":\"charger\"}");

            AssistantMessage toolMsg = new AssistantMessage();
            toolMsg.setRole(MessageRole.TOOL);
            toolMsg.setToolCallId("default_api:search_available_products");
            toolMsg.setContent("Found 2 items");

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Found 2 chargers\"}],\"role\":\"model\"}}]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            client.generateResponse(List.of(userMsg, modelMsg, toolMsg), List.of());

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());

            String requestPayload = extractRequestBody(requestCaptor.getValue());

            assertThat(requestPayload).contains("\"thoughtSignature\":\"skip_thought_signature_validator\"");
        }

        @Test
        @DisplayName("Given parallel function calls in history, when serializing request, then merges calls and attaches signature to first part only")
        @SuppressWarnings("unchecked")
        void merges_parallel_tool_calls_and_only_signs_first_part() throws Exception {
            properties.setApiKey("test-valid-api-key");
            properties.setModel("gemini-3.6-flash");

            AssistantMessage userMsg = createUserMessage("Find products and deals");

            AssistantMessage modelMsg1 = new AssistantMessage();
            modelMsg1.setRole(MessageRole.ASSISTANT);
            modelMsg1.setToolCallId("search_products");
            modelMsg1.setWidgetPayload("{\"query\":\"charger\"}");
            modelMsg1.setThoughtSignature("sig_parallel_first");

            AssistantMessage modelMsg2 = new AssistantMessage();
            modelMsg2.setRole(MessageRole.ASSISTANT);
            modelMsg2.setToolCallId("search_promotions");
            modelMsg2.setWidgetPayload("{\"type\":\"discount\"}");
            modelMsg2.setThoughtSignature(null);

            AssistantMessage toolMsg1 = new AssistantMessage();
            toolMsg1.setRole(MessageRole.TOOL);
            toolMsg1.setToolCallId("search_products");
            toolMsg1.setContent("Found products");

            AssistantMessage toolMsg2 = new AssistantMessage();
            toolMsg2.setRole(MessageRole.TOOL);
            toolMsg2.setToolCallId("search_promotions");
            toolMsg2.setContent("Found deals");

            HttpResponse<String> mockResponse = mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Found deals\"}],\"role\":\"model\"}}]}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            client.generateResponse(List.of(userMsg, modelMsg1, modelMsg2, toolMsg1, toolMsg2), List.of());

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(requestCaptor.capture(), any());

            String requestPayload = extractRequestBody(requestCaptor.getValue());

            assertThat(requestPayload).contains("\"thoughtSignature\":\"sig_parallel_first\"");
            assertThat(requestPayload).doesNotContain("skip_thought_signature_validator");
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

    private AssistantMessage createUserMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
