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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.observability.genai.GenAiGeneration;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;

/**
 * Gemini {@code generateContent} client. Each live call is reported on {@link ModelResponse#modelCall()}
 * (model, token usage, finish reason, failure); {@link GenAiGeneration} turns that into GenAI telemetry, so this
 * class only talks to Gemini.
 * <p>
 * Gemini failures (unreachable, timeout, non-2xx) are never turned into reply text: they raise
 * {@link ModelUnavailableException}, carrying the failed {@link ModelCall} and any {@code Retry-After} hint, so
 * {@link GenAiGeneration} still records the failed generation and the API can answer 503.
 * <p>
 * A call waits at most the per-call timeout, capped by what is left of the turn's
 * {@link ModelRequestContext#deadline()}.
 */
@Component
public class GeminiAiModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiModelClient.class);

    private final AssistantAiProperties aiModelConfig;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiAiModelClient(AssistantAiProperties aiModelConfig) {
        this(aiModelConfig, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), new ObjectMapper().findAndRegisterModules());
    }

    public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper) {
        this.aiModelConfig = aiModelConfig;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return generateResponse(messages, List.of()).text();
    }

    @Override
    @GenAiGeneration(provider = GenAiTelemetry.PROVIDER_GEMINI)
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return generateResponse(messages, tools, ModelRequestContext.empty());
    }

    @Override
    @GenAiGeneration(
            provider = GenAiTelemetry.PROVIDER_GEMINI,
            conversationId = "#context?.conversationId()",
            tags = {
                    @GenAiGeneration.Tag(key = "iteration", expression = "#context?.iteration()"),
                    @GenAiGeneration.Tag(key = "intent_id", expression = "#context?.intentId()"),
                    @GenAiGeneration.Tag(key = "intent_confidence", expression = "#context?.intentConfidence()"),
                    @GenAiGeneration.Tag(key = "tools_offered", expression = "#tools != null ? #tools.size() : 0")
            }
    )
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, @Nullable ModelRequestContext context) {
        String apiKey = aiModelConfig.getApiKey();
        if (apiKey == null) {
            throw new IllegalArgumentException("Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini.");
        }
        if (apiKey.isBlank() || (apiKey.startsWith("${") && apiKey.endsWith("}"))) {
            log.info("No AI API key configured; returning local assistant fallback.");
            String lastUserMessage = (messages != null && !messages.isEmpty())
                    ? messages.stream()
                            .filter(msg -> msg.getRole() == null || msg.getRole() == MessageRole.USER)
                            .map(AssistantMessage::getContent)
                            .filter(content -> content != null && !content.isBlank())
                            .reduce((first, second) -> second)
                            .orElse("")
                    : "";
            return new ModelResponse("I am Polaris Assistant! (Live AI Model key is not configured. Set GEMINI_API_KEY or polaris.ai.api-key to connect to live Gemini. Echo: \"" + lastUserMessage + "\")");
        }

        return executeAndParse(messages, tools, Optional.ofNullable(context).orElseGet(ModelRequestContext::empty));
    }

    private ModelResponse executeAndParse(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context) {
        String model = aiModelConfig.getModel();
        Duration timeout = callTimeout(context);
        if (timeout.isNegative() || timeout.isZero()) {
            TurnDeadlineExceededException exhausted = new TurnDeadlineExceededException("Turn deadline exceeded before calling Gemini");
            log.error("Turn deadline exceeded before calling Gemini");
            throw new ModelUnavailableException(exhausted.getMessage(), exhausted, ModelCall.failed(model, exhausted));
        }
        // Building the request never reaches Gemini: its failures are not model calls.
        HttpRequest request;
        try {
            request = buildRequest(messages, tools, timeout);
        } catch (IllegalArgumentException e) {
            log.error("Invalid Gemini client configuration", e);
            throw new ModelUnavailableException("Invalid Gemini client configuration", e);
        } catch (RuntimeException e) {
            log.error("Unexpected error building Gemini request", e);
            throw new ModelUnavailableException("Unexpected error building Gemini request", e);
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseModelResponse(response, model);
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Gemini request interrupted", e);
            throw new ModelUnavailableException("Gemini request interrupted", e, ModelCall.failed(model, e));
        } catch (HttpTimeoutException e) {
            log.error("Gemini request timed out after {}", timeout, e);
            throw new ModelUnavailableException("Gemini request timed out", e, ModelCall.failed(model, e));
        } catch (IOException e) {
            log.error("Gemini request I/O error", e);
            throw new ModelUnavailableException("Gemini request I/O error", e, ModelCall.failed(model, e));
        } catch (RuntimeException e) {
            log.error("Unexpected error in Gemini client", e);
            throw new ModelUnavailableException("Unexpected error in Gemini client", e, ModelCall.failed(model, e));
        }
    }

    /** The per-call timeout, capped by what is left of the turn's budget. */
    private Duration callTimeout(ModelRequestContext context) {
        Duration perCall = Duration.ofSeconds(aiModelConfig.getTimeoutSeconds());
        return context.deadline()
                .map(deadline -> Duration.between(Instant.now(), deadline))
                .filter(remaining -> remaining.compareTo(perCall) < 0)
                .orElse(perCall);
    }

    private HttpRequest buildRequest(List<AssistantMessage> messages, List<Tool> tools, Duration timeout) throws IllegalArgumentException {
        String apiKey = aiModelConfig.getApiKey();
        if (aiModelConfig.getBaseUrl() == null || aiModelConfig.getBaseUrl().isBlank()
                || aiModelConfig.getModel() == null || aiModelConfig.getModel().isBlank()) {
            throw new IllegalArgumentException("AI model URL or model is not configured");
        }
        String payload = getGeminiRequestBody(messages, tools);

        String url = aiModelConfig.getBaseUrl().replaceAll("/+$", "")
                + "/v1beta/models/" + aiModelConfig.getModel() + ":generateContent";

        log.info("Sending request to Gemini model {}", aiModelConfig.getModel());
        return HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private String getGeminiRequestBody(List<AssistantMessage> messages, List<Tool> tools) {
        List<Map<String, Object>> contents = new ArrayList<>();
        if (messages != null) {
            for (AssistantMessage msg : messages) {
                if (msg.getRole() == MessageRole.USER || msg.getRole() == null) {
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        addContentPart(contents, "user", Map.of("text", msg.getContent()));
                    }
                } else if (msg.getRole() == MessageRole.ASSISTANT) {
                    if (msg.getToolCallId() != null && !msg.getToolCallId().isBlank()) {
                        Map<String, Object> functionCall = new HashMap<>();
                        functionCall.put("name", msg.getToolCallId());
                        functionCall.put("args", parseJsonMap(msg.getWidgetPayload()));

                        Map<String, Object> part = new HashMap<>();
                        part.put("functionCall", functionCall);

                        String sig = msg.getThoughtSignature();
                        if (sig != null && !sig.isBlank()) {
                            part.put("thoughtSignature", sig);
                        } else if (isFirstFunctionCallInCurrentModelTurn(contents)) {
                            // Gemini 3/2.5 requires thoughtSignature on functionCall parts.
                            // If missing, use the official bypass signature to prevent 400 validation error.
                            part.put("thoughtSignature", "skip_thought_signature_validator");
                        }
                        addContentPart(contents, "model", part);
                    } else if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        Map<String, Object> part = new HashMap<>();
                        part.put("text", msg.getContent());
                        if (msg.getThoughtSignature() != null && !msg.getThoughtSignature().isBlank()) {
                            part.put("thoughtSignature", msg.getThoughtSignature());
                        }
                        addContentPart(contents, "model", part);
                    }
                } else if (msg.getRole() == MessageRole.TOOL) {
                    String toolName = msg.getToolCallId() != null ? msg.getToolCallId() : "tool";
                    String toolContent = msg.getContent() != null ? msg.getContent() : "";
                    Map<String, Object> part = Map.of(
                            "functionResponse", Map.of(
                                    "name", toolName,
                                    "response", Map.of("result", toolContent)
                            )
                    );
                    addContentPart(contents, "user", part);
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        if (aiModelConfig.getSystemPrompt() != null && !aiModelConfig.getSystemPrompt().isBlank()) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", aiModelConfig.getSystemPrompt()))));
        }
        body.put("contents", contents);

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> functionDeclarations = tools.stream()
                    .map(this::toFunctionDeclaration)
                    .collect(Collectors.toList());
            body.put("tools", List.of(Map.of("functionDeclarations", functionDeclarations)));
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Gemini request body", e);
            throw new RuntimeException("Failed to serialize request body for Gemini API", e);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isFirstFunctionCallInCurrentModelTurn(List<Map<String, Object>> contents) {
        if (contents.isEmpty()) {
            return true;
        }
        Map<String, Object> lastContent = contents.get(contents.size() - 1);
        if (!"model".equals(lastContent.get("role"))) {
            return true;
        }
        List<Map<String, Object>> parts = (List<Map<String, Object>>) lastContent.get("parts");
        if (parts == null || parts.isEmpty()) {
            return true;
        }
        for (Map<String, Object> p : parts) {
            if (p.containsKey("functionCall")) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void addContentPart(List<Map<String, Object>> contents, String role, Map<String, Object> part) {
        if (!contents.isEmpty()) {
            Map<String, Object> last = contents.get(contents.size() - 1);
            if (role.equals(last.get("role"))) {
                List<Map<String, Object>> parts = (List<Map<String, Object>>) last.get("parts");
                if (parts != null) {
                    parts.add(part);
                    return;
                }
            }
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("role", role);
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(part);
        entry.put("parts", parts);
        contents.add(entry);
    }

    private Map<String, Object> toFunctionDeclaration(Tool tool) {
        Map<String, Object> decl = new HashMap<>();
        decl.put("name", tool.name());
        if (tool.description() != null && !tool.description().isBlank()) {
            decl.put("description", tool.description());
        }
        if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
            decl.put("parameters", tool.inputSchema());
        }
        return decl;
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
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            ModelTokenUsage usage = tokenUsage(root.path("usageMetadata"));
            String responseModel = root.hasNonNull("modelVersion") ? root.path("modelVersion").asText() : null;
            String responseId = root.hasNonNull("responseId") ? root.path("responseId").asText() : null;

            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    List<ToolCall> toolCalls = new ArrayList<>();
                    StringBuilder textBuilder = new StringBuilder();
                    String responseThoughtSignature = null;

                    for (JsonNode part : parts) {
                        String partSignature = null;
                        if (part.hasNonNull("thoughtSignature")) {
                            partSignature = part.path("thoughtSignature").asText();
                        } else if (part.hasNonNull("thought_signature")) {
                            partSignature = part.path("thought_signature").asText();
                        }

                        if (part.has("functionCall")) {
                            JsonNode fc = part.path("functionCall");
                            String id = fc.hasNonNull("id") ? fc.path("id").asText() : null;
                            String fnName = fc.path("name").asText();
                            Map<String, Object> args = new HashMap<>();
                            if (fc.has("args") && fc.path("args").isObject()) {
                                args = objectMapper.convertValue(fc.path("args"), new TypeReference<Map<String, Object>>() {});
                            } else if (fc.has("arguments") && fc.path("arguments").isObject()) {
                                args = objectMapper.convertValue(fc.path("arguments"), new TypeReference<Map<String, Object>>() {});
                            }
                            if (partSignature == null) {
                                if (fc.hasNonNull("thoughtSignature")) {
                                    partSignature = fc.path("thoughtSignature").asText();
                                } else if (fc.hasNonNull("thought_signature")) {
                                    partSignature = fc.path("thought_signature").asText();
                                }
                            }
                            toolCalls.add(new ToolCall(id, fnName, args, partSignature));
                        } else {
                            boolean isThought = part.path("thought").asBoolean(false);
                            if (!isThought && part.has("text")) {
                                String text = part.path("text").asText();
                                if (text != null && !text.isBlank()) {
                                    if (!textBuilder.isEmpty()) {
                                        textBuilder.append(" ");
                                    }
                                    textBuilder.append(text.trim());
                                }
                            }
                            if (partSignature != null) {
                                responseThoughtSignature = partSignature;
                            }
                        }
                    }

                    String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
                    return new ModelResponse(textBuilder.toString(), toolCalls, responseThoughtSignature)
                            .withModelCall(ModelCall.succeeded(model, responseModel, responseId, usage, finishReason));
                }
            }
            return new ModelResponse("The AI Model returned an empty response.")
                    .withModelCall(ModelCall.succeeded(model, responseModel, responseId, usage, "stop"));
        } else {
            log.error("Gemini API error status: {} body: {}", response.statusCode(), response.body());
            ModelProviderException failure = new ModelProviderException(response.statusCode());
            String message = "Gemini returned HTTP " + response.statusCode();
            ModelCall call = ModelCall.failed(model, failure);
            throw retryAfter(response)
                    .map(delay -> new ModelUnavailableException(message, failure, call, delay))
                    .orElseGet(() -> new ModelUnavailableException(message, failure, call));
        }
    }

    /**
     * The provider's {@code Retry-After} hint (RFC 9110 §10.2.3): either delay-seconds or an HTTP-date.
     * Empty when absent or unparseable.
     */
    static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return Optional.ofNullable(response.headers())
                .flatMap(headers -> headers.firstValue("Retry-After"))
                .map(String::trim)
                .flatMap(GeminiAiModelClient::parseRetryAfter);
    }

    private static Optional<Duration> parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
        } catch (NumberFormatException notSeconds) {
            try {
                Duration untilDate = Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return Optional.of(untilDate.isNegative() ? Duration.ZERO : untilDate);
            } catch (DateTimeParseException notDate) {
                log.debug("Ignoring unparseable Retry-After header: {}", value);
                return Optional.empty();
            }
        }
    }

    /**
     * Maps Gemini {@code usageMetadata}. {@code promptTokenCount} already includes cached tokens; thinking tokens
     * are reported separately from {@code candidatesTokenCount}.
     */
    private static ModelTokenUsage tokenUsage(JsonNode usage) {
        return new ModelTokenUsage(
                usage.path("promptTokenCount").asLong(0),
                usage.path("candidatesTokenCount").asLong(0),
                usage.path("thoughtsTokenCount").asLong(0),
                usage.path("cachedContentTokenCount").asLong(0));
    }
}
