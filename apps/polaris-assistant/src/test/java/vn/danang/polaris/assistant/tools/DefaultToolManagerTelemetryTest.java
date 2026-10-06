package vn.danang.polaris.assistant.tools;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.genai.InMemoryGenAiTelemetry;

/**
 * Tool dispatch as GenAI tool executions: one {@code execute_tool <name>} span per call, nested under the
 * caller's active span even though calls run on worker threads, with tool I/O kept out of the span.
 */
class DefaultToolManagerTelemetryTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("sess-1", "user-1", 1);

    private InMemoryGenAiTelemetry telemetry;
    private PolarisMcpClient polarisMcpClient;
    private DefaultToolManager manager;

    @BeforeEach
    void setUp() {
        telemetry = new InMemoryGenAiTelemetry();
        polarisMcpClient = mock(PolarisMcpClient.class);
        // null executor: the manager's own virtual-thread executor, i.e. a real thread hop
        manager = new DefaultToolManager(polarisMcpClient, null, List.of(), telemetry.client());
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
        telemetry.close();
    }

    @Test
    @DisplayName("Given a tool call inside an active span, when dispatched, then records execute_tool as its child on the worker thread")
    void records_tool_execution_as_child_of_active_span() {
        when(polarisMcpClient.callTool(eq("search_available_products"), any()))
                .thenReturn(new CallToolResult(List.of(TextContent.builder("SECRET-RESULT 4111 1111 1111 1111").build()), false, null, Map.of()));

        Span turn = telemetry.openTelemetry().getTracer("test").spanBuilder("agent.turn").startSpan();
        try (Scope ignored = turn.makeCurrent()) {
            manager.handleToolCalls(List.of(new ToolCall("call-1", "search_available_products", Map.of("query", "SECRET-ARG"), null)), CONTEXT);
        } finally {
            turn.end();
        }

        SpanData tool = telemetry.spans().stream().filter(s -> s.getName().startsWith("execute_tool")).findFirst().orElseThrow();
        assertThat(tool.getName()).isEqualTo("execute_tool search_available_products");
        assertThat(tool.getParentSpanId()).isEqualTo(turn.getSpanContext().getSpanId());
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name"))).isEqualTo("search_available_products");
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.id"))).isEqualTo("call-1");
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("sess-1");
        assertThat(tool.getAttributes().toString()).doesNotContain("SECRET-ARG", "SECRET-RESULT", "4111");
    }

    @Test
    @DisplayName("Given a tool that fails, when dispatched, then the ERROR result marks the tool execution as failed")
    void marks_tool_execution_failed_on_error_result() {
        when(polarisMcpClient.callTool(eq("get_product_by_sku"), any())).thenThrow(new IllegalStateException("down"));

        List<ToolResult> results = manager.handleToolCalls(List.of(new ToolCall("call-2", "get_product_by_sku", Map.of(), null)), CONTEXT);

        assertThat(results).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(ToolResult.Status.ERROR));
        SpanData tool = telemetry.onlySpan();
        assertThat(tool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(tool.getAttributes().get(AttributeKey.stringKey("error.type"))).isNotBlank();
    }

    @Test
    @DisplayName("Given a refused tool call, when dispatched, then the DENIED outcome is not counted as a tool failure")
    void does_not_fail_tool_execution_on_denied_result() {
        when(polarisMcpClient.callTool(eq("cancel_order"), any()))
                .thenReturn(new CallToolResult(List.of(TextContent.builder("Forbidden").build()), true, Map.of("status", 403), Map.of()));

        List<ToolResult> results = manager.handleToolCalls(List.of(new ToolCall("call-3", "cancel_order", Map.of(), null)), CONTEXT);

        assertThat(results).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo(ToolResult.Status.DENIED));
        assertThat(telemetry.onlySpan().getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }
}
