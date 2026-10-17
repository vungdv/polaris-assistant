package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.config.QwenAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.observability.genai.GenAiGeneration;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;

/**
 * Qwen client for a local Ollama server ({@code POST /api/chat}, non-streaming). The counterpart of
 * {@link GeminiAiModelClient}: same contract, same {@link ModelResponse#modelCall()} reporting for
 * {@link GenAiGeneration}, same failure rule — an unreachable server, timeout or non-2xx raises
 * {@link ModelUnavailableException} and is never turned into reply text.
 * <p>
 * Server, model and timeout come from {@link QwenAiProperties}; the system prompt is shared with Gemini
 * ({@link AssistantAiProperties}). Thinking is switched off ({@code "think": false}): a small local model would
 * otherwise spend most of the turn deadline on reasoning tokens.
 */
@Component
public class QwenAiModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(QwenAiModelClient.class);

    private final AssistantAiProperties aiModelConfig;
    private final QwenAiProperties qwenConfig;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public QwenAiModelClient(AssistantAiProperties aiModelConfig, QwenAiProperties qwenConfig) {
        this(aiModelConfig, qwenConfig, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper().findAndRegisterModules());
    }

    public QwenAiModelClient(AssistantAiProperties aiModelConfig, QwenAiProperties qwenConfig, HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.aiModelConfig = aiModelConfig;
        this.qwenConfig = qwenConfig;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return generateResponse(messages, List.of()).text();
    }

    @Override
    @GenAiGeneration(provider = GenAiTelemetry.PROVIDER_OLLAMA)
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return generateResponse(messages, tools, ModelRequestContext.empty());
    }

    @Override
    @GenAiGeneration(
            provider = GenAiTelemetry.PROVIDER_OLLAMA,
            conversationId = "#context?.conversationId()",
            tags = {
                    @GenAiGeneration.Tag(key = "iteration", expression = "#context?.iteration()"),
                    @GenAiGeneration.Tag(key = "intent_id", expression = "#context?.intentId()"),
                    @GenAiGeneration.Tag(key = "intent_confidence", expression = "#context?.intentConfidence()"),
                    @GenAiGeneration.Tag(key = "tools_offered", expression = "#tools != null ? #tools.size() : 0")
            }
    )
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, @Nullable ModelRequestContext context) {
        ModelRequestContext callContext = Optional.ofNullable(context).orElseGet(ModelRequestContext::empty);
        String model = qwenConfig.getModel();
        Duration timeout = callTimeout(callContext);
        if (timeout.isNegative() || timeout.isZero()) {
            TurnDeadlineExceededException exhausted = new TurnDeadlineExceededException("Turn deadline exceeded before calling Qwen");
            log.error("Turn deadline exceeded before calling Qwen");
            throw new ModelUnavailableException(exhausted.getMessage(), exhausted, ModelCall.failed(model, exhausted));
        }
        // Building the request never reaches Ollama: its failures are not model calls.
        HttpRequest request;
        try {
            request = buildRequest(messages, tools, timeout);
        } catch (IllegalArgumentException e) {
            log.error("Invalid Qwen client configuration", e);
            throw new ModelUnavailableException("Invalid Qwen client configuration", e);
        } catch (RuntimeException e) {
            log.error("Unexpected error building Qwen request", e);
            throw new ModelUnavailableException("Unexpected error building Qwen request", e);
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseModelResponse(response, model);
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Qwen request interrupted", e);
            throw new ModelUnavailableException("Qwen request interrupted", e, ModelCall.failed(model, e));
        } catch (HttpTimeoutException e) {
            log.error("Qwen request timed out after {}", timeout, e);
            throw new ModelUnavailableException("Qwen request timed out", e, ModelCall.failed(model, e));
        } catch (IOException e) {
            log.error("Qwen request I/O error", e);
            throw new ModelUnavailableException("Qwen request I/O error", e, ModelCall.failed(model, e));
        } catch (RuntimeException e) {
            log.error("Unexpected error in Qwen client", e);
            throw new ModelUnavailableException("Unexpected error in Qwen client", e, ModelCall.failed(model, e));
        }
    }

    /** The per-call timeout, capped by what is left of the turn's budget. */
    private Duration callTimeout(ModelRequestContext context) {
        Duration perCall = Duration.ofSeconds(qwenConfig.getTimeoutSeconds());
        return context.deadline()
                .map(deadline -> Duration.between(Instant.now(), deadline))
                .filter(remaining -> remaining.compareTo(perCall) < 0)
                .orElse(perCall);
    }

    private HttpRequest buildRequest(List<AssistantMessage> messages, List<Tool> tools, Duration timeout) {
        String baseUrl = qwenConfig.getBaseUrl();
        String model = qwenConfig.getModel();
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Qwen base URL or model is not configured");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/api/chat";

        log.info("Sending request to Qwen model {}", model);
        return HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, messages, tools), StandardCharsets.UTF_8))
                .build();
    }

    private String requestBody(String model, List<AssistantMessage> messages, List<Tool> tools) {
        List<Map<String, Object>> chatMessages = new ArrayList<>();
        String systemPrompt = aiModelConfig.getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            chatMessages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (messages != null) {
            for (AssistantMessage msg : messages) {
                String content = msg.getContent() != null ? msg.getContent() : "";
                if (msg.getRole() == MessageRole.USER || msg.getRole() == null) {
                    if (!content.isBlank()) {
                        chatMessages.add(Map.of("role", "user", "content", content));
                    }
                } else if (msg.getRole() == MessageRole.ASSISTANT) {
                    if (msg.getToolCallId() != null && !msg.getToolCallId().isBlank()) {
                        Map<String, Object> function = Map.of(
                                "name", msg.getToolCallId(),
                                "arguments", parseJsonMap(msg.getWidgetPayload()));
                        chatMessages.add(Map.of("role", "assistant", "content", "",
                                "tool_calls", List.of(Map.of("function", function))));
                    } else if (!content.isBlank()) {
                        chatMessages.add(Map.of("role", "assistant", "content", content));
                    }
                } else if (msg.getRole() == MessageRole.TOOL) {
                    chatMessages.add(Map.of("role", "tool",
                            "tool_name", msg.getToolCallId() != null ? msg.getToolCallId() : "tool",
                            "content", content));
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", chatMessages);
        body.put("stream", false);
        body.put("think", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toToolDeclaration).toList());
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Qwen request body", e);
            throw new RuntimeException("Failed to serialize request body for Qwen", e);
        }
    }

    private Map<String, Object> toToolDeclaration(Tool tool) {
        Map<String, Object> function = new HashMap<>();
        function.put("name", tool.name());
        if (tool.description() != null && !tool.description().isBlank()) {
            function.put("description", tool.description());
        }
        if (tool.inputSchema() != null) {
            function.put("parameters", tool.inputSchema());
        }
        return Map.of("type", "function", "function", function);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON map from widget payload: {}", json);
            return Map.of();
        }
    }

    private ModelResponse parseModelResponse(HttpResponse<String> response, String model) throws IOException {
        if (response.statusCode() != 200) {
            log.error("Qwen API error status: {} body: {}", response.statusCode(), response.body());
            ModelProviderException failure = new ModelProviderException(response.statusCode());
            String message = "Qwen returned HTTP " + response.statusCode();
            ModelCall call = ModelCall.failed(model, failure);
            throw GeminiAiModelClient.retryAfter(response)
                    .map(delay -> new ModelUnavailableException(message, failure, call, delay))
                    .orElseGet(() -> new ModelUnavailableException(message, failure, call));
        }

        JsonNode root = objectMapper.readTree(response.body());
        ModelTokenUsage usage = new ModelTokenUsage(
                root.path("prompt_eval_count").asLong(0), root.path("eval_count").asLong(0), 0, 0);
        String responseModel = root.hasNonNull("model") ? root.path("model").asText() : null;

        JsonNode message = root.path("message");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String id = call.hasNonNull("id") ? call.path("id").asText() : null;
            toolCalls.add(new ToolCall(id, function.path("name").asText(), toolArguments(function.path("arguments"))));
        }
        String text = message.path("content").asText("").trim();
        if (text.isEmpty() && toolCalls.isEmpty()) {
            return new ModelResponse("The AI Model returned an empty response.")
                    .withModelCall(ModelCall.succeeded(model, responseModel, null, usage, "stop"));
        }
        String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
        return new ModelResponse(text, toolCalls)
                .withModelCall(ModelCall.succeeded(model, responseModel, null, usage, finishReason));
    }

    /** Ollama returns arguments as an object; some model templates return them as a JSON string. */
    private Map<String, Object> toolArguments(JsonNode arguments) {
        if (arguments.isObject()) {
            return objectMapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {});
        }
        return arguments.isTextual() ? parseJsonMap(arguments.asText()) : Map.of();
    }
}
