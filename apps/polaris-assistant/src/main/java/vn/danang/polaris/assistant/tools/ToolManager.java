package vn.danang.polaris.assistant.tools;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Hub contract for Model Context Protocol (MCP) operations.
 * Manages tool discovery and dispatches tool calls. It does not decide whether a call is acceptable for the
 * turn's intent or permitted for the caller; that is the caller's job (see the {@code intent} package).
 */
public interface ToolManager {

    /**
     * Discovers all available tools registered with MCP providers.
     *
     * @return list of available tools
     */
    List<Tool> discoverAllTools();

    /**
     * Executes a batch of already-approved tool calls, dispatching each to its local implementation or via MCP.
     *
     * @param toolCalls the tool calls to execute
     * @param context the execution context containing session and caller details
     * @return one tool result per tool call, in call order
     */
    List<ToolResult> handleToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context);
}
