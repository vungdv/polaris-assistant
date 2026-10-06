package vn.danang.polaris.assistant.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.intent.IntentResolver;
import vn.danang.polaris.assistant.intent.IntentToolExecutor;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolManager;
import vn.danang.polaris.assistant.tools.ToolResult;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Facade that orchestrates tool discovery from {@link ToolManager}, intent classification via {@link IntentResolver},
 * and intent- and policy-gated tool execution via {@link IntentToolExecutor}.
 */
@Component
public class IntentResolutionFacade {

    private final IntentResolver intentResolver;
    private final ToolManager toolManager;
    private final IntentToolExecutor intentToolExecutor;

    @Autowired
    public IntentResolutionFacade(
            IntentResolver intentResolver,
            ToolManager toolManager,
            IntentToolExecutor intentToolExecutor) {
        this.intentResolver = intentResolver;
        this.toolManager = toolManager;
        this.intentToolExecutor = intentToolExecutor;
    }

    /**
     * Resolves the user intent and computes the accepted tools using the configured ToolManager.
     *
     * @param messageText the incoming user prompt
     * @param history current conversation history
     * @return ResolvedIntent containing resolved intent metadata and accepted tools
     */
    public ResolvedIntent resolve(String messageText, List<AssistantMessage> history) {
        List<AssistantMessage> context = history != null ? new ArrayList<>(history) : new ArrayList<>();
        List<Tool> availableTools = this.toolManager != null ? this.toolManager.discoverAllTools() : List.of();
        return this.intentResolver.resolve(messageText, context, availableTools);
    }

    /**
     * Executes the proposed tool calls through {@link IntentToolExecutor}, so every call is validated against
     * the turn's intent and authorized for the caller before {@link ToolManager} dispatches it.
     *
     * @param toolCalls the tool calls proposed by the model
     * @param context the tool execution context containing session and caller metadata
     * @param resolvedIntent the turn's resolved intent
     * @return list of tool results representing the outcome of each tool execution
     */
    public List<ToolResult> executeToolCalls(
            List<ToolCall> toolCalls,
            ToolExecutionContext context,
            ResolvedIntent resolvedIntent) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        if (this.intentToolExecutor == null) {
            return List.of();
        }
        return this.intentToolExecutor.execute(toolCalls, context, resolvedIntent);
    }
}
