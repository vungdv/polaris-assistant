package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolManager;
import vn.danang.polaris.assistant.tools.ToolResult;
import vn.danang.polaris.assistant.tools.ToolResultsSummary;

/**
 * Gate between the model's proposed tool calls and {@link ToolManager}: a call runs only if the turn's
 * intent accepts the tool ({@link IntentToolPolicy#acceptsTool}), so a hallucinated or out-of-scope call
 * never runs.
 * Caller authorization is left to Polaris Core, which checks the caller's token on every MCP tool call;
 * {@link ToolManager} turns its 401/403 answers into {@link ToolResult.Status#DENIED} results.
 * Only the calls that pass are handed to {@link ToolManager}; rejections are returned in their place, so
 * results keep the call order.
 */
@Component
public class IntentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(IntentToolExecutor.class);

    private final ToolManager toolManager;

    public IntentToolExecutor(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    /**
     * @param toolCalls      the tool calls proposed by the model
     * @param context        session and caller of the turn
     * @param resolvedIntent the turn's resolved intent, or null if none was resolved
     * @return one tool result per tool call, in call order
     */
    @CustomNextSpan(
            name = "mcp.polaris.execute",
            tags = {
                    @SpanTag(key = "mcp.itent_id", expression = "#resolvedIntent?.intentId()"),
                    @SpanTag(key = "mcp.itent_confidence", expression = "#resolvedIntent?.confidence()"),
            },
            resultTags = {
                    @SpanTag(key = "mcp.tool_call.count", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).total()"),
                    @SpanTag(key = "mcp.tool_call.success_count", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).successCount()"),
                    @SpanTag(key = "mcp.tool_call.denied_count", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).deniedCount()"),
                    @SpanTag(key = "mcp.tool_call.error_count", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).errorCount()"),
                    @SpanTag(key = "mcp.tool_call.outcome", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).outcome()"),
                    @SpanTag(key = "mcp.tool_call.failure_reason", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).failureReason()"),
                    @SpanTag(key = "mcp.tool_call.failed_tools", expression = "T(vn.danang.polaris.assistant.tools.ToolResultsSummary).summarize(#result).failedTools()"),
            }
    )
    public List<ToolResult> execute(
            List<ToolCall> toolCalls,
            @Nullable ToolExecutionContext context,
            @Nullable ResolvedIntent resolvedIntent) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        ToolResult[] results = new ToolResult[toolCalls.size()];
        List<Integer> approvedIndexes = new ArrayList<>();
        List<ToolCall> approved = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolResult rejection = check(toolCalls.get(i), resolvedIntent);
            if (rejection != null) {
                results[i] = rejection;
            } else {
                approvedIndexes.add(i);
                approved.add(toolCalls.get(i));
            }
        }

        if (!approved.isEmpty()) {
            List<ToolResult> executed = toolManager.handleToolCalls(approved, context);
            if (executed == null || executed.size() != approved.size()) {
                throw new IllegalStateException("ToolManager returned " + (executed == null ? 0 : executed.size())
                        + " result(s) for " + approved.size() + " tool call(s)");
            }
            for (int j = 0; j < approved.size(); j++) {
                results[approvedIndexes.get(j)] = executed.get(j);
            }
        }

        List<ToolResult> ordered = Arrays.asList(results);
        logOutcome(ordered);
        return List.copyOf(ordered);
    }

    /**
     * @return the rejection to return in place of {@code toolCall}, or null if it may be executed
     */
    private @Nullable ToolResult check(
            ToolCall toolCall,
            @Nullable ResolvedIntent resolvedIntent) {
        if (toolCall == null) {
            ToolCall nullCall = new ToolCall("unknown", Map.of());
            return ToolResult.error(nullCall, "Tool call cannot be null", "Invalid tool call");
        }

        String intentId = resolvedIntent != null && resolvedIntent.intentId() != null
                ? resolvedIntent.intentId()
                : DefaultIntentResolver.DEFAULT_INTENT;

        log.info("Model requested tool call: '{}' with arguments: {}", toolCall.name(), toolCall.arguments());

        // The turn's intent must accept the tool
        if (!IntentToolPolicy.acceptsTool(resolvedIntent, toolCall.name())) {
            log.warn("Tool '{}' is not permitted for intent '{}'", toolCall.name(), intentId);
            String correctiveMessage = "Tool execution denied: Tool '" + toolCall.name() + "' is not permitted for intent '" + intentId + "'. Please provide a direct response or use permitted tools.";
            String semanticNote = "Tool '" + toolCall.name() + "' is not permitted under intent '" + intentId + "'.";
            return ToolResult.error(toolCall, correctiveMessage, semanticNote);
        }
        return null;
    }

    private void logOutcome(List<ToolResult> results) {
        ToolResultsSummary summary = ToolResultsSummary.summarize(results);
        if (summary.deniedCount() > 0 || summary.errorCount() > 0) {
            log.warn("Tool call batch outcome '{}': {} succeeded, {} denied, {} error (of {}); reason: {}",
                    summary.outcome(), summary.successCount(), summary.deniedCount(), summary.errorCount(),
                    summary.total(), summary.failureReason());
        } else {
            log.info("Tool call batch outcome '{}': {}/{} succeeded",
                    summary.outcome(), summary.successCount(), summary.total());
        }
    }
}
