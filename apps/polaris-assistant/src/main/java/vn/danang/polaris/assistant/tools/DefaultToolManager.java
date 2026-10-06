package vn.danang.polaris.assistant.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.grafana.agento11y.sdk.Agento11yClient;
import com.grafana.agento11y.sdk.ToolExecutionRecorder;
import com.grafana.agento11y.sdk.ToolExecutionStart;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.opentelemetry.context.Context;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

/**
 * Tool registry and dispatcher for Polaris Assistant.
 * Implements {@link ToolManager} to discover tools and execute tool calls, dispatching them directly to
 * Polaris Core via {@link PolarisMcpClient}, concurrently for remote tool calls.
 * <p>
 * This class knows nothing about intents: callers (the intent layer) decide which calls may run and hand
 * only those over. Caller permissions are checked by Polaris Core on each remote call; a 401/403 refusal
 * becomes a {@link ToolResult.Status#DENIED} result. {@link LocalTool}s (implemented inside the assistant, e.g.
 * {@code stage_order_draft}) are listed next to the remote tools; only the dispatch differs, and a local
 * tool takes precedence over a remote tool of the same name.
 * <p>
 * Every dispatched call is recorded as a GenAI tool execution ({@code execute_tool <name>} span) through the
 * agento11y SDK; a tool result with status ERROR marks that execution as failed.
 */
@Component
public class DefaultToolManager implements ToolManager, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolManager.class);

    private final PolarisMcpClient polarisMcpClient;
    private final Executor executor;
    private final boolean managedExecutor;
    private final Map<String, LocalTool> localTools;
    private final Agento11yClient genAiTelemetry;

    @Autowired
    public DefaultToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<Executor> executorProvider,
            ObjectProvider<LocalTool> localToolsProvider,
            Agento11yClient genAiTelemetry) {
        this(polarisMcpClient,
                executorProvider != null ? executorProvider.getIfAvailable() : null,
                localToolsProvider != null ? localToolsProvider.orderedStream().toList() : List.of(),
                genAiTelemetry);
    }

    public DefaultToolManager(
            PolarisMcpClient polarisMcpClient,
            ObjectProvider<Executor> executorProvider,
            ObjectProvider<LocalTool> localToolsProvider) {
        this(polarisMcpClient, executorProvider, localToolsProvider, GenAiTelemetry.disabledClient());
    }

    public DefaultToolManager(PolarisMcpClient polarisMcpClient) {
        this(polarisMcpClient, (Executor) null, List.of());
    }

    public DefaultToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable Executor executor,
            @Nullable List<LocalTool> localTools) {
        this(polarisMcpClient, executor, localTools, GenAiTelemetry.disabledClient());
    }

    public DefaultToolManager(
            PolarisMcpClient polarisMcpClient,
            @Nullable Executor executor,
            @Nullable List<LocalTool> localTools,
            Agento11yClient genAiTelemetry) {
        this.polarisMcpClient = polarisMcpClient;
        this.genAiTelemetry = genAiTelemetry;
        this.localTools = indexByName(localTools != null ? localTools : List.of());
        if (executor != null) {
            this.executor = executor;
            this.managedExecutor = false;
        } else {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.managedExecutor = true;
        }
    }

    private static Map<String, LocalTool> indexByName(List<LocalTool> tools) {
        Map<String, LocalTool> byName = new LinkedHashMap<>();
        for (LocalTool tool : tools) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate local tool name: " + tool.name());
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    @Override
    public void destroy() {
        if (this.managedExecutor && this.executor instanceof ExecutorService es && !es.isShutdown()) {
            es.shutdown();
        }
    }

    /**
     * Discovers all tools available from Polaris Core, plus the assistant's own {@link LocalTool}s
     * (which replace a remote tool of the same name).
     */
    @Override
    @CustomNextSpan(
            name = "mcp.polaris.discovery",
            tags = {
                    @SpanTag(key = "mcp.tool_count", expression = "#result?.size()")
            }
    )
    public List<Tool> discoverAllTools() {
        List<Tool> remote = polarisMcpClient.listAvailableTools();
        List<Tool> tools = new ArrayList<>();
        if (remote != null) {
            for (Tool tool : remote) {
                if (localTools.containsKey(tool.name())) {
                    log.warn("Local tool '{}' shadows a remote MCP tool of the same name; the remote tool is not offered", tool.name());
                } else {
                    tools.add(tool);
                }
            }
        }
        localTools.values().forEach(local -> tools.add(local.definition()));
        return tools;
    }

    @Override
    public List<ToolResult> handleToolCalls(List<ToolCall> toolCalls, ToolExecutionContext context) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        // Carry the caller's security context and trace context (the active span) onto the worker threads,
        // so each execute_tool span and the MCP call it makes nest under the current turn.
        Executor delegatingExecutor = Context.current().wrap(new DelegatingSecurityContextExecutor(
                this.executor, SecurityContextHolder.getContext()
        ));

        // Results kept in call order. Remote calls run concurrently, while mutating local tools
        // (e.g. stage then discard a draft) run one after another in call order, so their effects,
        // and the cards they return, follow the order the model asked for.
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>(toolCalls.size());
        CompletableFuture<ToolResult> lastMutation = CompletableFuture.completedFuture(null);
        for (ToolCall toolCall : toolCalls) {
            LocalTool localTool = localTools.get(toolCall.name());
            if (localTool != null && localTool.mutating()) {
                // executeLocalToolCall never completes exceptionally, so the chain always continues
                lastMutation = lastMutation.thenApplyAsync(
                        previous -> recordExecution(toolCall, context, () -> executeLocalToolCall(localTool, toolCall, context)),
                        delegatingExecutor);
                futures.add(lastMutation);
            } else {
                futures.add(executeConcurrently(toolCall, context, delegatingExecutor));
            }
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException("Concurrent tool execution failed", cause);
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private CompletableFuture<ToolResult> executeConcurrently(
            ToolCall toolCall,
            @Nullable ToolExecutionContext context,
            Executor executor) {
        LocalTool localTool = localTools.get(toolCall.name());
        if (localTool != null) {
            return CompletableFuture.supplyAsync(
                    () -> recordExecution(toolCall, context, () -> executeLocalToolCall(localTool, toolCall, context)), executor);
        }
        return CompletableFuture.supplyAsync(() -> recordExecution(toolCall, context, () -> executeRemoteToolCall(toolCall)), executor);
    }

    /**
     * Runs one tool call as a GenAI tool execution. Arguments and results are not attached (the client's
     * content capture keeps tool I/O out of spans); a {@link ToolResult.Status#ERROR} result fails the execution.
     */
    private ToolResult recordExecution(ToolCall toolCall, @Nullable ToolExecutionContext context, Supplier<ToolResult> execution) {
        ToolExecutionStart start = new ToolExecutionStart()
                .setToolName(toolCall.name())
                .setToolCallId(toolCall.id())
                .setToolType("function");
        if (context != null && context.sessionId() != null) {
            start.setConversationId(context.sessionId());
        }
        try (ToolExecutionRecorder recorder = genAiTelemetry.startToolExecution(start)) {
            ToolResult result = execution.get();
            if (result.status() == ToolResult.Status.ERROR) {
                recorder.setCallError(new ToolExecutionException(toolCall.name(), result.errorDescription()));
            }
            return result;
        }
    }

    /** A tool call that completed with an ERROR result; only used to mark the tool execution as failed. */
    static final class ToolExecutionException extends RuntimeException {
        ToolExecutionException(String toolName, @Nullable String description) {
            super("Tool '" + toolName + "' failed" + (description != null ? ": " + description : ""));
        }
    }

    private ToolResult executeLocalToolCall(LocalTool localTool, ToolCall toolCall, @Nullable ToolExecutionContext context) {
        log.info("Dispatching execution for local tool: {}", toolCall.name());
        try {
            ToolResult result = localTool.execute(toolCall, context);
            return result != null
                    ? result
                    : ToolResult.error(toolCall, "Null result returned from tool: " + toolCall.name(), "Tool execution failure");
        } catch (Exception ex) {
            log.error("Local tool execution error for '{}': {}", toolCall.name(), ex.getMessage(), ex);
            return ToolResult.error(toolCall, "Tool execution error: " + ex.getMessage(), ex.getMessage());
        }
    }

    private ToolResult executeRemoteToolCall(ToolCall toolCall) {
        try {
            CallToolResult mcpResult = executeTool(toolCall.name(), toolCall.arguments());
            if (mcpResult == null) {
                return ToolResult.error(toolCall, "Null result returned from tool: " + toolCall.name(), "Tool execution failure");
            }

            if (Boolean.TRUE.equals(mcpResult.isError())) {
                String errorText = extractText(mcpResult);
                if (isRefused(mcpResult)) {
                    log.warn("Polaris Core denied tool '{}': {}", toolCall.name(), errorText);
                    return ToolResult.denied(toolCall, errorText);
                }
                return ToolResult.error(toolCall, errorText, "Tool execution failure");
            }

            String content = extractText(mcpResult);
            // structuredContent never reaches the model; it only becomes a card (e.g. PRODUCT_LIST)
            return RemoteToolWidgets.attach(ToolResult.success(toolCall, content), mcpResult);
        } catch (Exception ex) {
            log.error("Tool execution error for '{}': {}", toolCall.name(), ex.getMessage(), ex);
            return ToolResult.error(toolCall, "Tool execution error: " + ex.getMessage(), ex.getMessage());
        }
    }

    /**
     * Polaris Core authorizes every tool call against the caller's token: a refusal is a 401/403 problem
     * (RFC 7807) in {@code structuredContent}, whether the MCP endpoint or the tool itself refused.
     */
    private static boolean isRefused(CallToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> problem && problem.get("status") instanceof Number status) {
            return status.intValue() == 401 || status.intValue() == 403;
        }
        return false;
    }

    private CallToolResult executeTool(String toolName, Map<String, Object> arguments) {
        log.info("Dispatching execution for tool: {}", toolName);
        return polarisMcpClient.callTool(toolName, arguments);
    }

    private String extractText(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
