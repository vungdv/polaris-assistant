package vn.danang.polaris.assistant.tools;

import java.util.List;

/**
 * Aggregate outcome computed over a batch of {@link ToolResult}s.
 * <p>
 * Used to attach clear success/failure counts and a failure reason to the
 * {@code mcp.polaris.execute} trace span, and to summarize a batch outcome in logs.
 *
 * @param total total number of tool calls in the batch
 * @param successCount number of tool calls that succeeded
 * @param deniedCount number of tool calls rejected by intent validation or policy authorization
 * @param errorCount number of tool calls that failed during dispatch/execution
 * @param outcome {@code "success"} if all calls succeeded, {@code "failure"} if none did,
 *                otherwise {@code "partial_failure"}
 * @param failureReason the {@link ToolResult#errorDescription()} of the first non-successful
 *                       call, or {@code null} if every call succeeded
 * @param failedTools comma-separated names of the tools that did not succeed, or {@code null}
 *                     if every call succeeded
 */
public record ToolResultsSummary(
        int total,
        int successCount,
        int deniedCount,
        int errorCount,
        String outcome,
        String failureReason,
        String failedTools
) {

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_PARTIAL_FAILURE = "partial_failure";
    private static final String OUTCOME_FAILURE = "failure";

    public static ToolResultsSummary summarize(List<ToolResult> results) {
        if (results == null || results.isEmpty()) {
            return new ToolResultsSummary(0, 0, 0, 0, OUTCOME_SUCCESS, null, null);
        }

        int success = 0;
        int denied = 0;
        int error = 0;
        String firstFailureReason = null;
        StringBuilder failedTools = new StringBuilder();

        for (ToolResult result : results) {
            switch (result.status()) {
                case SUCCESS -> success++;
                case DENIED -> denied++;
                case ERROR -> error++;
            }
            if (!result.isSuccess()) {
                if (firstFailureReason == null) {
                    firstFailureReason = result.errorDescription();
                }
                if (!failedTools.isEmpty()) {
                    failedTools.append(", ");
                }
                failedTools.append(result.toolCall().name());
            }
        }

        int failedCount = denied + error;
        String outcome = failedCount == 0
                ? OUTCOME_SUCCESS
                : (success == 0 ? OUTCOME_FAILURE : OUTCOME_PARTIAL_FAILURE);

        return new ToolResultsSummary(
                results.size(), success, denied, error, outcome,
                firstFailureReason,
                failedTools.isEmpty() ? null : failedTools.toString());
    }
}
