package vn.danang.polaris.assistant.intent;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Encapsulates the resolved intent along with its confidence, threshold evaluation,
 * accepted tools, and the underlying intent definition for the current conversation turn.
 */
public record ResolvedIntent(
        String intentId,
        double confidence,
        boolean meetsThreshold,
        List<Tool> acceptedTools,
        IntentDefinition intentDefinition
) {

    public ResolvedIntent(
            String intentId,
            double confidence,
            boolean meetsThreshold,
            List<Tool> acceptedTools
    ) {
        this(intentId, confidence, meetsThreshold, acceptedTools, null);
    }

    public ResolvedIntent {
        acceptedTools = acceptedTools != null ? List.copyOf(acceptedTools) : List.of();
    }

    public List<Tool> tools() {
        return acceptedTools;
    }

    public List<Tool> filteredTools() {
        return acceptedTools;
    }
}
