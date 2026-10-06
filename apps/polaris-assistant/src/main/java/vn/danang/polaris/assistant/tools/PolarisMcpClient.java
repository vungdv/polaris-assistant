package vn.danang.polaris.assistant.tools;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Contract for interacting with the Polaris Core backend via Model Context Protocol (MCP).
 * Enforces strict architectural decoupling: Polaris Assistant consumes Catalog & Order
 * operations purely across the MCP protocol boundary.
 */
public interface PolarisMcpClient {

    /**
     * Lists available tools exposed by the Polaris Core MCP server.
     */
    List<Tool> listAvailableTools();

    /**
     * Invokes a tool by name on the Polaris Core MCP server with the provided arguments.
     *
     * @param toolName the name of the tool (e.g. "search_available_products", "get_product_by_sku")
     * @param arguments input arguments for the tool
     * @return the result of the tool invocation
     */
    CallToolResult callTool(String toolName, Map<String, Object> arguments);
}
