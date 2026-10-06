package vn.danang.polaris.assistant.intent;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Core interface for intent resolution and tool filtering.
 * Single method interface implemented by {@link DefaultIntentResolver}.
 */
@FunctionalInterface
public interface IntentResolver {

    /**
     * Resolves user intent and computes accepted tools from the user message,
     * conversation history, and available MCP tools.
     *
     * @param messageText raw user prompt
     * @param history conversation context
     * @param tools all available tools
     * @return ResolvedIntent containing intent classification and accepted tools
     */
    ResolvedIntent resolve(String messageText, List<AssistantMessage> history, List<Tool> tools);
}
