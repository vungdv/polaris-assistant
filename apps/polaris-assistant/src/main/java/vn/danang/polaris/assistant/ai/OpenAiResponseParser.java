package vn.danang.polaris.assistant.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads an OpenAI Chat Completions response. Reasoning never leaves this class: the {@code reasoning} /
 * {@code reasoning_content} message fields are not read, an inline {@code <think>} block is stripped from the
 * text, and nothing of either is logged.
 */
class OpenAiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponseParser.class);
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    OpenAiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @throws JsonProcessingException when the body is not JSON */
    ModelResponse parse(String body, String requestModel) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(body);
        String responseModel = text(root, "model");
        String responseId = text(root, "id");
        ModelTokenUsage usage = tokenUsage(root.path("usage"));

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return new ModelResponse("The AI Model returned an empty response.")
                    .withModelCall(ModelCall.succeeded(requestModel, responseModel, responseId, usage, "stop"));
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode calls = message.path("tool_calls");
        if (calls.isArray()) {
            for (int i = 0; i < calls.size(); i++) {
                toolCalls.add(toToolCall(calls.get(i), responseId, i));
            }
        }
        String finishReason = text(choice, "finish_reason");
        if (finishReason == null) {
            finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
        }
        return new ModelResponse(visibleText(message.path("content")), toolCalls)
                .withModelCall(ModelCall.succeeded(requestModel, responseModel, responseId, usage, finishReason));
    }

    private ToolCall toToolCall(JsonNode call, String responseId, int index) {
        JsonNode function = call.path("function");
        String name = function.path("name").asText();
        String id = text(call, "id");
        if (id == null) {
            id = "call_" + (responseId != null ? responseId : "gen") + "_" + index;
        }
        return new ToolCall(id, name, arguments(function.path("arguments"), name));
    }

    /** {@code arguments} is a JSON string; malformed input gives no arguments, never a failure. */
    private Map<String, Object> arguments(JsonNode arguments, String toolName) {
        try {
            if (arguments.isObject()) {
                return objectMapper.convertValue(arguments, MAP);
            }
            if (arguments.isTextual() && !arguments.asText().isBlank()) {
                Map<String, Object> parsed = objectMapper.readValue(arguments.asText(), MAP);
                return parsed != null ? parsed : new HashMap<>();
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Tool call '{}' arguments are not a valid JSON object; using empty arguments", toolName);
        }
        return new HashMap<>();
    }

    private static String visibleText(JsonNode content) {
        if (content.isMissingNode() || content.isNull()) {
            return "";
        }
        return THINK_BLOCK.matcher(content.asText()).replaceAll("").trim();
    }

    /**
     * {@code completion_tokens} includes reasoning on servers that report {@code reasoning_tokens}; output is
     * then reported without it, as the contract expects.
     */
    private static ModelTokenUsage tokenUsage(JsonNode usage) {
        long completion = usage.path("completion_tokens").asLong(0);
        long reasoning = Math.min(completion, usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0));
        return new ModelTokenUsage(
                usage.path("prompt_tokens").asLong(0),
                completion - reasoning,
                reasoning,
                usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) && !node.path(field).asText().isBlank() ? node.path(field).asText() : null;
    }
}
