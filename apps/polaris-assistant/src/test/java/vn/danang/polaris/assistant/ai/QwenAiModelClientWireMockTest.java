package vn.danang.polaris.assistant.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.config.QwenAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/** {@link QwenAiModelClient} against a real HTTP server stubbed with WireMock, speaking Ollama's {@code /api/chat}. */
class QwenAiModelClientWireMockTest {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String CHAT_PATH = "/api/chat";

    private QwenAiModelClient client;

    @BeforeEach
    void setUp() {
        AssistantAiProperties properties = new AssistantAiProperties();
        properties.setSystemPrompt("Be brief.");
        QwenAiProperties qwen = new QwenAiProperties();
        qwen.setBaseUrl(ollama.baseUrl());
        qwen.setModel("qwen3:4b");
        qwen.setTimeoutSeconds(5);
        client = new QwenAiModelClient(properties, qwen);
    }

    private static AssistantMessage message(MessageRole role, String content) {
        AssistantMessage message = new AssistantMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    @DisplayName("sends a non-streaming, non-thinking chat request and maps text and token usage")
    void text_reply() {
        ollama.stubFor(post(urlEqualTo(CHAT_PATH))
                .withRequestBody(equalToJson("""
                        { "model": "qwen3:4b", "stream": false, "think": false,
                          "messages": [
                            { "role": "system", "content": "Be brief." },
                            { "role": "user", "content": "Hello!" } ] }
                        """))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        { "model": "qwen3:4b", "done": true, "done_reason": "stop",
                          "message": { "role": "assistant", "content": " Hi, I am Qwen. " },
                          "prompt_eval_count": 12, "eval_count": 7 }
                        """)));

        ModelResponse response = client.generateResponse(List.of(message(MessageRole.USER, "Hello!")), List.of());

        assertThat(response.text()).isEqualTo("Hi, I am Qwen.");
        assertThat(response.hasToolCalls()).isFalse();
        ModelCall call = response.modelCall();
        assertThat(call.requestModel()).isEqualTo("qwen3:4b");
        assertThat(call.finishReason()).isEqualTo("stop");
        assertThat(call.usage()).isEqualTo(new ModelTokenUsage(12, 7, 0, 0));
    }

    @Test
    @DisplayName("offers tools, replays tool history, and maps proposed tool calls")
    void tool_calls() {
        ollama.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(aResponse().withStatus(200).withBody("""
                { "model": "qwen3:4b",
                  "message": { "role": "assistant", "content": "",
                    "tool_calls": [ { "function": { "name": "search_products", "arguments": { "query": "tea" } } } ] } }
                """)));
        Tool tool = Tool.builder().name("search_products").description("Search products")
                .inputSchema(new JsonSchema("object", Map.of("query", Map.of("type", "string")), List.of("query"), null, null, null))
                .build();
        AssistantMessage priorCall = message(MessageRole.ASSISTANT, null);
        priorCall.setToolCallId("search_products");
        priorCall.setWidgetPayload("{\"query\":\"coffee\"}");
        AssistantMessage priorResult = message(MessageRole.TOOL, "[]");
        priorResult.setToolCallId("search_products");

        ModelResponse response = client.generateResponse(
                List.of(message(MessageRole.USER, "tea?"), priorCall, priorResult), List.of(tool));

        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("search_products");
            assertThat(call.args()).containsEntry("query", "tea");
        });
        assertThat(response.modelCall().finishReason()).isEqualTo("tool_calls");
        ollama.verify(postRequestedFor(urlEqualTo(CHAT_PATH))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("search_products")))
                .withRequestBody(matchingJsonPath("$.messages[2].tool_calls[0].function.arguments.query", equalTo("coffee")))
                .withRequestBody(matchingJsonPath("$.messages[3].tool_name", equalTo("search_products"))));
    }

    @Test
    @DisplayName("a non-2xx answer raises ModelUnavailableException carrying the failed call and Retry-After")
    void provider_error() {
        ollama.stubFor(post(urlEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "7")));

        ModelUnavailableException failure = catchThrowableOfType(ModelUnavailableException.class,
                () -> client.chat(List.of(message(MessageRole.USER, "Hello!"))));

        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(7));
        assertThat(failure.modelCall().isFailed()).isTrue();
        assertThat(failure.modelCall().failure()).isInstanceOfSatisfying(ModelProviderException.class,
                e -> assertThat(e.statusCode()).isEqualTo(503));
    }

    @Test
    @DisplayName("a dropped connection raises ModelUnavailableException")
    void connection_fault() {
        ollama.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ModelUnavailableException failure = catchThrowableOfType(ModelUnavailableException.class,
                () -> client.chat(List.of(message(MessageRole.USER, "Hello!"))));

        assertThat(failure.modelCall().isFailed()).isTrue();
    }

    @Test
    @DisplayName("an exhausted turn deadline fails before any request is sent")
    void deadline_exhausted() {
        ModelRequestContext expired = new ModelRequestContext(1, "unknown", 1.0, 0, null, Instant.now().minusSeconds(1));

        ModelUnavailableException failure = catchThrowableOfType(ModelUnavailableException.class,
                () -> client.generateResponse(List.of(message(MessageRole.USER, "Hello!")), List.of(), expired));

        assertThat(failure.getCause()).isInstanceOf(TurnDeadlineExceededException.class);
        ollama.verify(0, postRequestedFor(urlEqualTo(CHAT_PATH)));
    }
}
