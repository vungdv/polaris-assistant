package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * Verifies {@link GeminiAiModelClient} against a real HTTP server stubbed with WireMock,
 * exercising the actual request/response wire format instead of a mocked {@link java.net.http.HttpClient}.
 */
class GeminiAiModelClientWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/gemini-3.6-flash:generateContent";

    private AssistantAiProperties properties;
    private GeminiAiModelClient client;

    @BeforeEach
    void setUp() {
        properties = new AssistantAiProperties();
        properties.setApiKey("test-wiremock-api-key");
        properties.setModel("gemini-3.6-flash");
        properties.setBaseUrl(wireMock.baseUrl());
        properties.setTimeoutSeconds(5);
        client = new GeminiAiModelClient(properties);
    }

    // =========================================================================
    // 1. Happy path — real request/response over the wire
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a stubbed 200 response, when chat is invoked, then sends expected headers/body and returns text")
        void sends_expected_request_and_parses_text_reply() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("x-goog-api-key", equalTo("test-wiremock-api-key"))
                    .withRequestBody(containing("\"text\":\"Hello Polaris!\""))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "candidates": [
                                        {
                                          "content": {
                                            "parts": [
                                              { "text": "Hello! How can I help you today?" }
                                            ],
                                            "role": "model"
                                          }
                                        }
                                      ]
                                    }
                                    """)));

            String reply = client.chat(List.of(createUserMessage("Hello Polaris!")));

            assertThat(reply).isEqualTo("Hello! How can I help you today?");
            wireMock.verify(postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH))
                    .withHeader("x-goog-api-key", equalTo("test-wiremock-api-key")));
        }

        @Test
        @DisplayName("Given tool definitions, when generateResponse is invoked, then serializes functionDeclarations and parses returned tool call")
        void serializes_tools_and_parses_function_call_response() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query")
            );
            Tool tool = Tool.builder("search_available_products", schema)
                    .description("Search catalog products")
                    .build();

            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .withRequestBody(containing("\"functionDeclarations\""))
                    .withRequestBody(containing("\"search_available_products\""))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "candidates": [
                                        {
                                          "content": {
                                            "parts": [
                                              {
                                                "functionCall": {
                                                  "name": "search_available_products",
                                                  "args": { "query": "charger" }
                                                }
                                              }
                                            ],
                                            "role": "model"
                                          }
                                        }
                                      ]
                                    }
                                    """)));

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Find chargers")), List.of(tool));

            assertThat(response.hasToolCalls()).isTrue();
            assertThat(response.toolCalls()).hasSize(1);
            ToolCall call = response.toolCalls().get(0);
            assertThat(call.name()).isEqualTo("search_available_products");
            assertThat(call.args()).containsEntry("query", "charger");
        }

        @Test
        @DisplayName("Given usageMetadata in the stubbed response, when generateResponse is invoked, then parsing succeeds without error")
        void parses_response_containing_usage_metadata() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "candidates": [
                                        { "content": { "parts": [ { "text": "5 items in stock." } ], "role": "model" } }
                                      ],
                                      "usageMetadata": { "promptTokenCount": 12, "candidatesTokenCount": 6, "totalTokenCount": 18 }
                                    }
                                    """)));

            ModelResponse response = client.generateResponse(List.of(createUserMessage("Check stock")), List.of());

            assertThat(response.text()).isEqualTo("5 items in stock.");
        }
    }

    // =========================================================================
    // 2. Error handling — HTTP error statuses fail loudly, never as reply text
    // =========================================================================
    @Nested
    @DisplayName("2. Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Given the stub returns HTTP 400, when chat is invoked, then throws ModelUnavailableException carrying the status")
        void throws_model_unavailable_on_400_response() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "error": {
                                        "code": 400,
                                        "message": "API key not valid. Please pass a valid API key.",
                                        "status": "INVALID_ARGUMENT"
                                      }
                                    }
                                    """)));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.getCause()).isInstanceOfSatisfying(ModelProviderException.class,
                                cause -> assertThat(cause.statusCode()).isEqualTo(400));
                        assertThat(e.retryAfter()).isEmpty();
                    });
        }

        @Test
        @DisplayName("Given the stub returns HTTP 500, when generating, then throws ModelUnavailableException with a failed model call")
        void throws_model_unavailable_on_500_response() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "text/plain")
                            .withBody("internal server error")));

            assertThatThrownBy(() -> client.generateResponse(List.of(createUserMessage("Test")), List.of()))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.modelCall()).isNotNull();
                        assertThat(e.modelCall().isFailed()).isTrue();
                        assertThat(e.modelCall().failure()).isInstanceOfSatisfying(ModelProviderException.class,
                                cause -> assertThat(cause.statusCode()).isEqualTo(500));
                    });
        }

        @Test
        @DisplayName("Given the stub returns HTTP 503 with Retry-After seconds, when chat is invoked, then the exception carries the delay")
        void carries_retry_after_seconds_on_503_response() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(503)
                            .withHeader("Retry-After", "17")
                            .withBody("{ \"error\": { \"code\": 503, \"message\": \"The model is overloaded.\" } }")));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class,
                            e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(17)));
        }

        @Test
        @DisplayName("Given the stub returns HTTP 429 with Retry-After, when chat is invoked, then throws ModelUnavailableException with the delay")
        void throws_model_unavailable_with_retry_after_on_rate_limit_response() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(429)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("Retry-After", "30")
                            .withBody("""
                                    { "error": { "code": 429, "message": "Resource has been exhausted.", "status": "RESOURCE_EXHAUSTED" } }
                                    """)));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.retryAfter()).contains(Duration.ofSeconds(30));
                        assertThat(e.getCause()).isInstanceOfSatisfying(ModelProviderException.class,
                                cause -> assertThat(cause.statusCode()).isEqualTo(429));
                    });
        }

        @Test
        @DisplayName("Given the stub returns HTTP 429 with an HTTP-date Retry-After, when chat is invoked, then the delay is the time until that date")
        void parses_http_date_retry_after() {
            String inAMinute = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60));
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", inAMinute)));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> assertThat(e.retryAfter())
                            .hasValueSatisfying(delay -> assertThat(delay).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(60))));
        }

        @Test
        @DisplayName("Given the stub returns HTTP 429 with a garbage Retry-After, when chat is invoked, then no delay is reported")
        void ignores_unparseable_retry_after() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "soon")));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> assertThat(e.retryAfter()).isEmpty());
        }
    }

    // =========================================================================
    // 3. Network edge cases — timeouts and faults against a real socket
    // =========================================================================
    @Nested
    @DisplayName("3. Network edge cases")
    class NetworkEdgeCases {

        @Test
        @DisplayName("Given the stub delays beyond the configured timeout, when chat is invoked, then throws ModelUnavailableException")
        void throws_model_unavailable_when_server_response_exceeds_timeout() {
            properties.setTimeoutSeconds(1);
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay((int) Duration.ofSeconds(3).toMillis())
                            .withHeader("Content-Type", "application/json")
                            .withBody("{}")));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.getCause()).isInstanceOf(IOException.class);
                        assertThat(e.retryAfter()).isEmpty();
                    });
        }

        @Test
        @DisplayName("Given the stub resets the connection, when chat is invoked, then throws ModelUnavailableException with a failed model call")
        void throws_model_unavailable_on_connection_reset() {
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.getCause()).isInstanceOf(IOException.class);
                        assertThat(e.modelCall()).isNotNull();
                        assertThat(e.modelCall().failure()).isSameAs(e.getCause());
                    });
        }

        @Test
        @DisplayName("Given Gemini is unreachable (connection refused), when chat is invoked, then throws ModelUnavailableException")
        void throws_model_unavailable_when_gemini_is_unreachable() {
            properties.setBaseUrl("http://localhost:" + unusedPort());

            assertThatThrownBy(() -> client.chat(List.of(createUserMessage("Test"))))
                    .isInstanceOfSatisfying(ModelUnavailableException.class,
                            e -> assertThat(e.getCause()).isInstanceOf(IOException.class));
        }
    }

    // =========================================================================
    // 4. Turn deadline — per-call timeout capped by the remaining budget
    // =========================================================================
    @Nested
    @DisplayName("4. Turn deadline")
    class TurnDeadline {

        @Test
        @DisplayName("Given a turn deadline sooner than the per-call timeout, when Gemini is slow, then the call times out at the deadline")
        void call_timeout_is_capped_by_remaining_turn_budget() {
            properties.setTimeoutSeconds(10);
            wireMock.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                    .willReturn(aResponse().withStatus(200).withFixedDelay(5_000).withBody("{}")));
            ModelRequestContext context = new ModelRequestContext(2, "general.conversation", 1.0, 0, "sess-1",
                    Instant.now().plusMillis(500));

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.generateResponse(List.of(createUserMessage("Test")), List.of(), context))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.getCause()).isInstanceOf(HttpTimeoutException.class);
                        assertThat(e.modelCall()).isNotNull();
                        assertThat(e.modelCall().failure()).isSameAs(e.getCause());
                    });
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }

        @Test
        @DisplayName("Given the turn deadline has passed, when generateResponse is invoked, then fails with a timeout without calling Gemini")
        void fails_without_calling_gemini_when_budget_is_exhausted() {
            ModelRequestContext context = new ModelRequestContext(3, "general.conversation", 1.0, 0, "sess-1",
                    Instant.now().minusMillis(1));

            assertThatThrownBy(() -> client.generateResponse(List.of(createUserMessage("Test")), List.of(), context))
                    .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                        assertThat(e.getCause()).isInstanceOf(HttpTimeoutException.class);
                        assertThat(e.modelCall()).isNotNull();
                        assertThat(e.modelCall().isFailed()).isTrue();
                        assertThat(e.retryAfter()).isEmpty();
                    });
            wireMock.verify(0, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        }
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private AssistantMessage createUserMessage(String content) {
        AssistantMessage msg = new AssistantMessage();
        msg.setRole(MessageRole.USER);
        msg.setContent(content);
        return msg;
    }
}
