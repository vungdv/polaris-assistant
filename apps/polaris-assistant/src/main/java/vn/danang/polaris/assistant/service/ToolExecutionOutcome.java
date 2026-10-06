package vn.danang.polaris.assistant.service;

import java.util.List;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * Encapsulates the outcome of executing a batch of tool calls during a conversation turn.
 *
 * @param turns the generated conversation turns (model turns followed by tool turns)
 * @param policyDenied whether any tool call was denied by policy authorization
 * @param denialMessage user-facing denial explanation if policy denied, or null
 * @param results the batch's tool results in call order (source of cards and card retractions)
 */
public record ToolExecutionOutcome(
        List<AssistantMessage> turns,
        boolean policyDenied,
        @Nullable String denialMessage,
        List<ToolResult> results
) {
    public ToolExecutionOutcome {
        turns = turns != null ? List.copyOf(turns) : List.of();
        results = results != null ? List.copyOf(results) : List.of();
    }

    public ToolExecutionOutcome(List<AssistantMessage> turns, boolean policyDenied, @Nullable String denialMessage) {
        this(turns, policyDenied, denialMessage, List.of());
    }
}
