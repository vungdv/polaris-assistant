package vn.danang.polaris.assistant.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.QwenProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * F1.1-F1.7 and the provider-specific X items (X1, X2, X4, X8) for {@link OpenAiCompatibleModelClient}, over the real
 * HTTP interface with the plan's §8 fixtures as stub bodies.
 */
class OpenAiCompatibleModelClientWireMockTest {

    private static final Path FIXTURES = Path.of("../../docs/development/plan/qwen-model-client-fixtures");
    private static final String PATH = "/v1/chat/completions";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Start of the (truncated) reasoning in text.response.json; must never surface anywhere. */
    private static final String REASONING_MARKER = "We are to say";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private QwenProperties properties;
    private OpenAiCompatibleModelClient client;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        properties = new QwenProperties();
        properties.setBaseUrl(wireMock.baseUrl() + "/v1");
        properties.setTimeoutSeconds(5);
        properties.setSystemPrompt("");
        client = new OpenAiCompatibleModelClient(properties);
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
    }

    // ---- F1 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("F1.1 Given a text reply, when generating, then text is the content and there are no tool calls")
    void f1_1_text_reply() {
        stub(200, "text.response.json");

        ModelResponse response = client.generateResponse(List.of(user("Say hi in five words.")), List.of());

        assertThat(response.text()).isEqualTo("Hi how are you doing");
        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    @DisplayName("F1.2 Given a tool call with string arguments, when generating, then one call with parsed args")
    void f1_2_tool_call() {
        stub(200, "tool-call.response.json");

        ModelResponse response = client.generateResponse(List.of(user("Weather in Hanoi?")), List.of(weatherTool()));

        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_bcon1mjg");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.args()).isEqualTo(Map.of("city", "Hanoi"));
        assertThat(response.text()).isEmpty();
    }

    @Test
    @DisplayName("F1.3 Given malformed arguments, when generating, then empty args, a warning and no exception")
    void f1_3_malformed_arguments() {
        stub(200, "synthetic-malformed-args.response.json");

        ModelResponse response = client.generateResponse(List.of(user("Weather?")), List.of(weatherTool()));

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).args()).isEmpty();
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("get_weather");
        });
    }

    @Test
    @DisplayName("F1.4 Given a reasoning field, when generating, then it is absent from text, logs and the response")
    void f1_4_reasoning_field_is_dropped() {
        stub(200, "text.response.json");

        ModelResponse response = client.generateResponse(List.of(user("Say hi in five words.")), List.of());

        assertThat(response.text()).doesNotContain(REASONING_MARKER);
        assertThat(response.toString()).doesNotContain(REASONING_MARKER);
        assertThat(logs.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains(REASONING_MARKER));
    }

    @Test
    @DisplayName("F1.5 Given an inline think block, when generating, then it is stripped from the text")
    void f1_5_inline_think_is_stripped() {
        stub(200, "synthetic-inline-think.response.json");

        assertThat(client.generateResponse(List.of(user("Hi")), List.of()).text()).isEqualTo("Hi there");
    }

    @Test
    @DisplayName("F1.6 Given a tool call without id, then it gets a generated id and the next tool message links to its call")
    void f1_6_missing_id_is_generated_and_linked() throws Exception {
        stub(200, "synthetic-no-id-tool-call.response.json");
        ModelResponse first = client.generateResponse(List.of(user("Weather?")), List.of(weatherTool()));
        assertThat(first.toolCalls().get(0).id()).isNotBlank();

        stub(200, "tool-result.response.json");
        client.generateResponse(toolRoundTripHistory(), List.of(weatherTool()));

        JsonNode messages = lastRequest().path("messages");
        String callId = messages.get(1).path("tool_calls").get(0).path("id").asText();
        assertThat(callId).isNotBlank();
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo(callId);
    }

    @Test
    @DisplayName("F1.7 Given a tool call and result in history, then the tool message carries tool_call_id and the reply is returned")
    void f1_7_tool_round_trip() throws Exception {
        stub(200, "tool-result.response.json");

        ModelResponse response = client.generateResponse(toolRoundTripHistory(), List.of(weatherTool()));

        assertThat(response.text()).isEqualTo("The current weather in Hanoi is 31°C and humid.");
        JsonNode request = lastRequest();
        JsonNode fixture = JSON.readTree(Files.readString(FIXTURES.resolve("tool-result.request.json")));
        assertThat(request.path("model")).isEqualTo(fixture.path("model"));
        assertThat(request.path("stream").asBoolean(true)).isFalse();
        assertThat(request.path("tools")).isEqualTo(fixture.path("tools"));
        JsonNode messages = request.path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isEqualTo(fixture.path("messages").get(0));
        JsonNode assistant = messages.get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("tool_calls").get(0).path("type").asText()).isEqualTo("function");
        assertThat(assistant.path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("get_weather");
        assertThat(JSON.readTree(assistant.path("tool_calls").get(0).path("function").path("arguments").asText()))
                .isEqualTo(JSON.readTree("{\"city\":\"Hanoi\"}"));
        JsonNode tool = messages.get(2);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("content").asText()).isEqualTo("31C, humid");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo(assistant.path("tool_calls").get(0).path("id").asText());
    }

    @Test
    @DisplayName("Given several tool calls in history, then they form one assistant message and results link by name in order")
    void parallel_tool_calls_are_grouped() throws Exception {
        stub(200, "tool-result.response.json");
        List<AssistantMessage> history = List.of(user("Two cities"),
                toolCall("get_weather", "{\"city\":\"Hanoi\"}"), toolCall("get_weather", "{\"city\":\"Hue\"}"),
                toolResult("get_weather", "31C"), toolResult("get_weather", "28C"));

        client.generateResponse(history, List.of(weatherTool()));

        JsonNode messages = lastRequest().path("messages");
        assertThat(messages).hasSize(4);
        JsonNode calls = messages.get(1).path("tool_calls");
        assertThat(calls).hasSize(2);
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo(calls.get(0).path("id").asText());
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo(calls.get(1).path("id").asText());
    }

    @Test
    @DisplayName("Given a system prompt, when generating, then it is the first message")
    void system_prompt_is_first_message() throws Exception {
        properties.setSystemPrompt("Be brief.");
        stub(200, "text.response.json");

        client.generateResponse(List.of(user("Hi")), List.of());

        JsonNode messages = lastRequest().path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("Be brief.");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
    }

    // ---- X4 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("X4 Given usage in the response, then the ModelCall carries model, id, tokens and finish reason")
    void model_call_is_filled() {
        stub(200, "tool-call.response.json");

        ModelCall call = client.generateResponse(List.of(user("Weather?")), List.of(weatherTool())).modelCall();

        assertThat(call.isFailed()).isFalse();
        assertThat(call.requestModel()).isEqualTo("qwen3:4b");
        assertThat(call.responseModel()).isEqualTo("qwen3:4b");
        assertThat(call.responseId()).isEqualTo("chatcmpl-955");
        assertThat(call.usage()).isEqualTo(new ModelTokenUsage(141, 1697, 0, 0));
        assertThat(call.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    @DisplayName("X4 Given a text reply, then finish reason is stop and cached prompt tokens are reported")
    void model_call_for_text() {
        stub(200, "tool-result.response.json");

        ModelCall call = client.generateResponse(List.of(user("Hi")), List.of()).modelCall();

        assertThat(call.finishReason()).isEqualTo("stop");
        assertThat(call.usage()).isEqualTo(new ModelTokenUsage(179, 452, 0, 1));
    }

    // ---- X8 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("X8 Given a non-blank key, then the request carries a Bearer header")
    void bearer_header_with_key() {
        properties.setApiKey("secret-key");
        stub(200, "text.response.json");

        client.generateResponse(List.of(user("Hi")), List.of());

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer secret-key")));
        assertThat(logs.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("secret-key"));
    }

    @Test
    @DisplayName("X8 Given a blank key, then no Authorization header is sent")
    void no_header_without_key() {
        properties.setApiKey("  ");
        stub(200, "text.response.json");

        client.generateResponse(List.of(user("Hi")), List.of());

        wireMock.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("Authorization", absent()));
    }

    // ---- X1 / X2 ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("X1 Given 503 with Retry-After, then ModelUnavailableException carries the status, a failed call and the hint")
    void server_error_is_unavailable_with_retry_after() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)
                .withHeader("Retry-After", "7").withBody("{\"error\":\"secret upstream detail\"}")));

        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ModelProviderException.class,
                            cause -> assertThat(cause.statusCode()).isEqualTo(503));
                    assertThat(e.modelCall().isFailed()).isTrue();
                    assertThat(e.retryAfter()).contains(java.time.Duration.ofSeconds(7));
                    assertThat(e.getMessage()).doesNotContain("secret upstream detail");
                });
        assertThat(logs.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("secret upstream detail"));
    }

    @Test
    @DisplayName("X1 Given 400, then ModelUnavailableException with a 4xx cause")
    void client_error_is_unavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400).withBody("{}")));

        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                    assertThat(ModelFailures.isRejectedRequest(e)).isTrue();
                    assertThat(e.retryAfter()).isEmpty();
                });
    }

    @Test
    @DisplayName("X1 Given a dropped connection, then ModelUnavailableException with an I/O cause")
    void connection_fault_is_unavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(IOException.class);
                    assertThat(e.modelCall().isFailed()).isTrue();
                    assertThat(ModelFailures.isOutage(e)).isTrue();
                });
    }

    @Test
    @DisplayName("X1 Given a non-JSON 200 body, then ModelUnavailableException and not an outage")
    void unreadable_body_is_unavailable() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("<html>")));

        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of()))
                .isInstanceOfSatisfying(ModelUnavailableException.class,
                        e -> assertThat(e.modelCall().isFailed()).isTrue());
    }

    @Test
    @DisplayName("X2 Given the server is slower than timeout-seconds, then it fails with a request timeout")
    void per_call_timeout() {
        properties.setTimeoutSeconds(1);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));

        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of()))
                .isInstanceOfSatisfying(ModelUnavailableException.class,
                        e -> assertThat(e.getCause()).isInstanceOf(HttpTimeoutException.class));
    }

    @Test
    @DisplayName("X2 Given the turn deadline is exhausted, then TurnDeadlineExceededException and no HTTP request")
    void exhausted_deadline_makes_no_request() {
        ModelRequestContext expired = new ModelRequestContext(1, "intent", 1.0, 0, null, Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of(), expired))
                .isInstanceOfSatisfying(ModelUnavailableException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(TurnDeadlineExceededException.class);
                    assertThat(e.modelCall().isFailed()).isTrue();
                });
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("X2 Given little turn time left, then the call timeout is capped by it")
    void deadline_caps_the_call_timeout() {
        properties.setTimeoutSeconds(60);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(3000).withBody("{}")));
        ModelRequestContext soon = new ModelRequestContext(1, "intent", 1.0, 0, null, Instant.now().plusSeconds(1));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.generateResponse(List.of(user("Hi")), List.of(), soon))
                .isInstanceOfSatisfying(ModelUnavailableException.class,
                        e -> assertThat(e.getCause()).isInstanceOf(HttpTimeoutException.class));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
    }

    @Test
    @DisplayName("The turn deadline comes from QwenProperties")
    void turn_deadline_from_properties() {
        properties.setTurnDeadline(java.time.Duration.ofSeconds(42));

        assertThat(client.turnDeadline()).isEqualTo(java.time.Duration.ofSeconds(42));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static void stub(int status, String fixture) {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(fixture(fixture))));
    }

    private static String fixture(String name) {
        try {
            return Files.readString(FIXTURES.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode lastRequest() throws IOException {
        List<com.github.tomakehurst.wiremock.verification.LoggedRequest> requests =
                wireMock.findAll(postRequestedFor(urlEqualTo(PATH)));
        return JSON.readTree(requests.get(requests.size() - 1).getBodyAsString());
    }

    private static List<AssistantMessage> toolRoundTripHistory() {
        return List.of(user("What is the weather in Hanoi? Use the tool."),
                toolCall("get_weather", "{\"city\":\"Hanoi\"}"), toolResult("get_weather", "31C, humid"));
    }

    private static Tool weatherTool() {
        return Tool.builder("get_weather", Map.of("type", "object",
                        "properties", Map.of("city", Map.of("type", "string")), "required", List.of("city")))
                .description("Get weather for a city").build();
    }

    private static AssistantMessage user(String content) {
        AssistantMessage message = new AssistantMessage();
        message.setRole(MessageRole.USER);
        message.setContent(content);
        return message;
    }

    private static AssistantMessage toolCall(String name, String argumentsJson) {
        AssistantMessage message = new AssistantMessage();
        message.setRole(MessageRole.ASSISTANT);
        message.setToolCallId(name);
        message.setWidgetPayload(argumentsJson);
        return message;
    }

    private static AssistantMessage toolResult(String name, String content) {
        AssistantMessage message = new AssistantMessage();
        message.setRole(MessageRole.TOOL);
        message.setToolCallId(name);
        message.setContent(content);
        return message;
    }
}
