package vn.danang.polaris.assistant.intent;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Which tools the turn's {@link ResolvedIntent} lets the model call, as enforced by
 * {@link IntentToolExecutor}. Authorization is not decided here: Polaris Core checks the caller's token on
 * every MCP tool call and answers 401/403 when it is refused.
 */
public final class IntentToolPolicy {

    private IntentToolPolicy() {
    }

    /**
     * Guards against the model calling a tool the turn's intent never offered it (a hallucinated or
     * out-of-scope call). Accepts the resolved intent's allowed tools (a low-confidence turn is already
     * resolved to {@link DefaultIntentResolver#DEFAULT_INTENT}), or the turn's accepted tools if no
     * definition was matched; without a resolved intent, none.
     *
     * @return whether {@code toolName} may be called under {@code resolvedIntent}
     */
    public static boolean acceptsTool(ResolvedIntent resolvedIntent, String toolName) {
        if (toolName == null || resolvedIntent == null) {
            return false;
        }
        IntentDefinition definition = resolvedIntent.intentDefinition();
        if (definition != null) {
            return definition.allowedTools() != null && definition.allowedTools().contains(toolName);
        }
        List<Tool> accepted = resolvedIntent.filteredTools();
        return accepted != null && accepted.stream().anyMatch(t -> toolName.equals(t.name()));
    }
}
