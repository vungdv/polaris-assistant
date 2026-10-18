package vn.danang.polaris.assistant.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/**
 * Builds an OpenAI Chat Completions request body ({@code stream=false}) from the conversation.
 * <p>
 * The conversation store keeps a tool call as the tool's name, not the provider's call id. OpenAI-style servers
 * need an id that links a {@code tool} message to the assistant {@code tool_calls} entry, so ids are generated
 * here, in history order: each stored tool call gets {@code call_<n>}, and a tool result takes the oldest
 * unanswered call of the same name. Consecutive tool calls form one assistant message, as the model made them.
 */
class OpenAiRequestMapper {

    private static final Logger log = LoggerFactory.getLogger(OpenAiRequestMapper.class);

    private final ObjectMapper objectMapper;

    OpenAiRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toRequestBody(String model, String systemPrompt, List<AssistantMessage> messages, List<Tool> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", toMessages(systemPrompt, messages));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(OpenAiRequestMapper::toFunctionTool).toList());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request body for the OpenAI-compatible API", e);
        }
    }

    private List<Map<String, Object>> toMessages(String systemPrompt, List<AssistantMessage> history) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (hasText(systemPrompt)) {
            out.add(message("system", systemPrompt));
        }
        if (history == null) {
            return out;
        }
        Deque<PendingCall> pending = new ArrayDeque<>();
        int nextCall = 1;
        for (AssistantMessage msg : history) {
            MessageRole role = msg.getRole();
            if (role == null || role == MessageRole.USER) {
                if (hasText(msg.getContent())) {
                    out.add(message("user", msg.getContent()));
                }
            } else if (role == MessageRole.ASSISTANT) {
                if (hasText(msg.getToolCallId())) {
                    String id = "call_" + nextCall++;
                    pending.add(new PendingCall(id, msg.getToolCallId()));
                    appendToolCall(out, id, msg.getToolCallId(), argumentsJson(msg.getWidgetPayload()));
                } else if (hasText(msg.getContent())) {
                    out.add(message("assistant", msg.getContent()));
                }
            } else if (role == MessageRole.TOOL) {
                String name = msg.getToolCallId() != null ? msg.getToolCallId() : "tool";
                Map<String, Object> tool = message("tool", msg.getContent() != null ? msg.getContent() : "");
                tool.put("tool_call_id", takePending(pending, name).id());
                out.add(tool);
            }
        }
        return out;
    }

    /** Adds the call to the preceding assistant tool-call message, or starts a new one. */
    @SuppressWarnings("unchecked")
    private static void appendToolCall(List<Map<String, Object>> out, String id, String name, String arguments) {
        Map<String, Object> call = Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", arguments));
        if (!out.isEmpty()) {
            Map<String, Object> last = out.get(out.size() - 1);
            if (last.get("tool_calls") instanceof List<?> calls) {
                ((List<Object>) calls).add(call);
                return;
            }
        }
        Map<String, Object> assistant = message("assistant", "");
        List<Object> calls = new ArrayList<>();
        calls.add(call);
        assistant.put("tool_calls", calls);
        out.add(assistant);
    }

    /** The oldest unanswered call with this name; a result without a recorded call gets a fresh id. */
    private static PendingCall takePending(Deque<PendingCall> pending, String name) {
        for (PendingCall call : pending) {
            if (call.name().equals(name)) {
                pending.remove(call);
                return call;
            }
        }
        return new PendingCall("call_" + name, name);
    }

    private String argumentsJson(String widgetPayload) {
        if (!hasText(widgetPayload)) {
            return "{}";
        }
        try {
            // Normalised so the server always receives a JSON object string.
            return objectMapper.writeValueAsString(
                    objectMapper.readValue(widgetPayload, new TypeReference<Map<String, Object>>() { }));
        } catch (JsonProcessingException e) {
            log.warn("Stored tool call arguments are not a JSON object; sending an empty object");
            return "{}";
        }
    }

    private static Map<String, Object> toFunctionTool(Tool tool) {
        Map<String, Object> function = new HashMap<>();
        function.put("name", tool.name());
        if (hasText(tool.description())) {
            function.put("description", tool.description());
        }
        if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
            function.put("parameters", tool.inputSchema());
        }
        return Map.of("type", "function", "function", function);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PendingCall(String id, String name) {
    }
}
