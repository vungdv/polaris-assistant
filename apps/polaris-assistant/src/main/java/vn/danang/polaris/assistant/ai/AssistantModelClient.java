package vn.danang.polaris.assistant.ai;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Contract for forwarding conversation messages to an external Foundation AI Model.
 */
public interface AssistantModelClient {

    /**
     * Send conversation messages to the AI Model and receive its text response.
     *
     * @param messages conversation messages in this session including the latest user prompt
     * @return AI model text response
     */
    default String chat(List<AssistantMessage> messages) {
        return generateResponse(messages, List.of()).text();
    }

    /**
     * Send conversation messages and available MCP tools to the AI Model.
     * The model may return a text response or propose tool call(s).
     *
     * @param messages conversation messages in this session
     * @param tools available tools discovered via MCP
     * @return ModelResponse containing either text or tool calls
     */
    default ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        String text = chat(messages);
        return new ModelResponse(text, List.of());
    }

    /**
     * Send conversation messages and available MCP tools to the AI Model with reasoning context.
     *
     * @param messages conversation messages in this session
     * @param tools available tools discovered via MCP
     * @param context reasoning context including iteration and resolved intent
     * @return ModelResponse containing either text or tool calls
     * @throws ModelUnavailableException when the model provider is unreachable, times out or answers with an error
     */
    default ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context) {
        return generateResponse(messages, tools);
    }
}

