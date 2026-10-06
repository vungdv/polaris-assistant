package vn.danang.polaris.assistant.tools;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * A model-callable tool implemented inside the assistant itself rather than by a remote MCP server
 * (e.g. staging an order draft, which only touches the assistant's own tables).
 * <p>
 * Local tools are offered and authorized exactly like remote ones: {@link DefaultToolManager} lists
 * their {@link #definition()} alongside the discovered MCP tools, and once a call has passed the intent
 * layer's intent and per-tool scope checks ({@code intents.json}) dispatches it to {@link #execute}
 * instead of MCP.
 */
public interface LocalTool {

    /**
     * @return the tool's name, description and input schema, in the same shape the model sees for MCP tools
     */
    Tool definition();

    default String name() {
        return definition().name();
    }

    /**
     * Whether the tool changes state (e.g. drafts). Mutating local tools of one batch run sequentially in
     * call order rather than concurrently. Defaults to true, the safe choice.
     */
    default boolean mutating() {
        return true;
    }

    /**
     * Runs an already-authorized call. Implementations report failures as {@link ToolResult#error} or
     * {@link ToolResult#denied} rather than throwing.
     *
     * @param toolCall the model's call
     * @param context  session, caller and intent of the turn
     */
    ToolResult execute(ToolCall toolCall, ToolExecutionContext context);
}
