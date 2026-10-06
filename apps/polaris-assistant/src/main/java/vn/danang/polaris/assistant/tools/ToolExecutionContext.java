package vn.danang.polaris.assistant.tools;

/**
 * Immutable context containing the turn metadata required to execute tool calls.
 *
 * @param sessionId the conversation session identifier
 * @param userId the caller user identity
 * @param iteration the 1-based ReAct loop iteration index
 */
public record ToolExecutionContext(
        String sessionId,
        String userId,
        int iteration
) {
}
