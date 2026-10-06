package vn.danang.polaris.assistant.intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.tools.DefaultToolManager;
import vn.danang.polaris.assistant.tools.LocalTool;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolManager;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * The intent layer's gate in front of {@link ToolManager}: intent validation, then dispatch; authorization
 * is Polaris Core's 401/403 answer. Runs against a real {@link DefaultToolManager}; only the MCP boundary
 * is mocked.
 */
@ExtendWith(MockitoExtension.class)
class IntentToolExecutorTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("sess-1", "user-1", 1);

    @Mock
    private PolarisMcpClient polarisMcpClient;

    private IntentToolExecutor executor(LocalTool... localTools) {
        return new IntentToolExecutor(new DefaultToolManager(polarisMcpClient, Runnable::run, List.of(localTools)));
    }

    private static ResolvedIntent placeIntent(double confidence, boolean meetsThreshold) {
        IntentDefinition place = new DefaultIntentManager().getIntent("commerce.order.place").orElseThrow();
        return new ResolvedIntent("commerce.order.place", confidence, meetsThreshold, List.of(), place);
    }

    private static CallToolResult text(String text) {
        return new CallToolResult(List.of(TextContent.builder(text).build()), false, null, Map.of());
    }

    private static CallToolResult refused(int status, String detail) {
        Map<String, Object> problem = Map.of("type", "about:blank", "title", "Refused", "status", status, "detail", detail);
        return new CallToolResult(List.of(TextContent.builder(detail).build()), true, problem, Map.of());
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a tool accepted by the intent and an authorized caller, when executed, then dispatched and its result returned")
        void executes_accepted_and_authorized_tool() {
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(text("Product found: Charger"));
            ResolvedIntent search = new ResolvedIntent("catalog.product.search", 0.95, true,
                    List.of(Tool.builder("search_available_products", Map.of()).build()));
            ToolCall call = new ToolCall("search_available_products", Map.of("query", "charger"), "sig-123");

            List<ToolResult> results = executor().execute(List.of(call), CONTEXT, search);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.isSuccess()).isTrue();
                assertThat(r.toolCall()).isEqualTo(call);
                assertThat(r.result()).isEqualTo("Product found: Charger");
            });
        }

        @Test
        @DisplayName("Given a custom intent definition, when one of its tools is executed, then dispatched over MCP")
        void executes_tool_of_custom_intent_definition() {
            IntentDefinition custom = new IntentDefinition("custom.intent", "", List.of(), List.of("custom_tool"), 0.5);
            ResolvedIntent resolved = new ResolvedIntent("custom.intent", 0.9, true, List.of(), custom);
            when(polarisMcpClient.callTool(eq("custom_tool"), any())).thenReturn(text("ok"));

            List<ToolResult> results = executor().execute(List.of(new ToolCall("custom_tool", Map.of())), CONTEXT, resolved);

            assertThat(results).singleElement().satisfies(r -> assertThat(r.isSuccess()).isTrue());
        }

        @Test
        @DisplayName("Given commerce.order.place, when stage_order_draft is called, then dispatched to the local tool, never over MCP")
        void dispatches_local_tool_after_intent_check() {
            List<ToolCall> executed = new ArrayList<>();
            LocalTool stage = new LocalTool() {
                @Override
                public Tool definition() {
                    return Tool.builder("stage_order_draft", Map.of()).build();
                }

                @Override
                public ToolResult execute(ToolCall toolCall, ToolExecutionContext context) {
                    executed.add(toolCall);
                    return ToolResult.success(toolCall, "staged for " + context.userId());
                }
            };

            List<ToolResult> results = executor(stage)
                    .execute(List.of(new ToolCall("stage_order_draft", Map.of())), CONTEXT, placeIntent(0.97, true));

            assertThat(results).singleElement().satisfies(r -> assertThat(r.result()).isEqualTo("staged for user-1"));
            assertThat(executed).hasSize(1);
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }
    }

    // =========================================================================
    // 2. Intent validation: hallucinated or out-of-intent tools never run
    // =========================================================================
    @Nested
    @DisplayName("2. Intent validation")
    class IntentValidation {

        @Test
        @DisplayName("Given a tool the intent doesn't accept, when executed, then corrective error without dispatch")
        void rejects_tool_not_accepted_by_intent() {
            ResolvedIntent search = new ResolvedIntent("catalog.product.search", 0.95, true,
                    List.of(Tool.builder("search_available_products", Map.of()).build()));

            List<ToolResult> results = executor()
                    .execute(List.of(new ToolCall("get_order_status", Map.of("order_id", "123"))), CONTEXT, search);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.result()).contains("not permitted for intent 'catalog.product.search'");
                assertThat(r.errorDescription()).isEqualTo("Tool 'get_order_status' is not permitted under intent 'catalog.product.search'.");
            });
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }

        @Test
        @DisplayName("Given no resolved intent, when a tool is called, then rejected")
        void null_intent_accepts_no_tool() {
            List<ToolResult> results = executor()
                    .execute(List.of(new ToolCall("stage_order_draft", Map.of())), CONTEXT, null);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.result()).contains("not permitted for intent 'general.conversation'");
            });
        }

        @Test
        @DisplayName("Given general.conversation (also the low-confidence fallback), when any tool is called, then everything is rejected and nothing executes")
        void intent_without_tools_rejects_everything() {
            ResolvedIntent conversation = new ResolvedIntent("general.conversation", 0.99, true, List.of(),
                    new DefaultIntentManager().getIntent("general.conversation").orElseThrow());

            List<ToolResult> results = executor().execute(List.of(
                    new ToolCall("search_available_products", Map.of()),
                    new ToolCall("stage_order_draft", Map.of())), CONTEXT, conversation);

            assertThat(results).hasSize(2).noneMatch(ToolResult::isSuccess);
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }
    }

    // =========================================================================
    // 3. Authorization: Polaris Core refuses the call with 401/403
    // =========================================================================
    @Nested
    @DisplayName("3. Polaris Core authorization")
    class CoreAuthorization {

        private final ResolvedIntent lookup = new ResolvedIntent("information.lookup.order.status", 0.95, true, List.of(),
                new DefaultIntentManager().getIntent("information.lookup.order.status").orElseThrow());

        @Test
        @DisplayName("Given Polaris Core answers 401, when the tool is called, then the result is DENIED with Core's reason")
        void maps_401_to_denied() {
            when(polarisMcpClient.callTool(eq("get_order_status"), any()))
                    .thenReturn(refused(401, "Polaris Core refused tool get_order_status: HTTP 401 Unauthorized"));

            List<ToolResult> results = executor().execute(List.of(new ToolCall("get_order_status", Map.of())), CONTEXT, lookup);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.isDenied()).isTrue();
                assertThat(r.result()).isEqualTo("Polaris Core refused tool get_order_status: HTTP 401 Unauthorized");
            });
        }

        @Test
        @DisplayName("Given a tool refuses with a 403 problem, when called, then the result is DENIED")
        void maps_403_problem_to_denied() {
            when(polarisMcpClient.callTool(eq("get_order_status"), any()))
                    .thenReturn(refused(403, "Forbidden: the 'order.read' permission is required."));

            List<ToolResult> results = executor().execute(List.of(new ToolCall("get_order_status", Map.of())), CONTEXT, lookup);

            assertThat(results).singleElement().satisfies(r -> assertThat(r.isDenied()).isTrue());
        }

        @Test
        @DisplayName("Given a tool fails with a non-auth problem (404), when called, then the result is an ERROR, not DENIED")
        void keeps_other_problems_as_errors() {
            when(polarisMcpClient.callTool(eq("get_order_status"), any())).thenReturn(refused(404, "Order not found."));

            List<ToolResult> results = executor().execute(List.of(new ToolCall("get_order_status", Map.of())), CONTEXT, lookup);

            assertThat(results).singleElement().satisfies(r -> assertThat(r.isError()).isTrue());
        }
    }

    // =========================================================================
    // 4. Batches & edge cases
    // =========================================================================
    @Nested
    @DisplayName("4. Batches & edge cases")
    class Batches {

        @Test
        @DisplayName("Given a batch mixing a rejected and a valid call, when executed, then only the valid call is dispatched and results keep call order")
        void dispatches_only_approved_calls_and_keeps_order() {
            ToolManager toolManager = mock(ToolManager.class);
            ToolCall rejected = new ToolCall("unauthorized_action", Map.of());
            ToolCall valid = new ToolCall("search_available_products", Map.of("query", "charger"));
            ToolCall outOfIntent = new ToolCall("get_order_status", Map.of());
            when(toolManager.handleToolCalls(List.of(valid), CONTEXT)).thenReturn(List.of(ToolResult.success(valid, "Charger")));
            ResolvedIntent search = new ResolvedIntent("catalog.product.search", 0.95, true, List.of(),
                    new DefaultIntentManager().getIntent("catalog.product.search").orElseThrow());

            List<ToolResult> results = new IntentToolExecutor(toolManager)
                    .execute(List.of(rejected, valid, outOfIntent), CONTEXT, search);

            assertThat(results).extracting(r -> r.toolCall().name())
                    .containsExactly("unauthorized_action", "search_available_products", "get_order_status");
            assertThat(results.get(0).isError()).isTrue();
            assertThat(results.get(1).result()).isEqualTo("Charger");
            assertThat(results.get(2).isError()).isTrue();
            verify(toolManager, times(1)).handleToolCalls(List.of(valid), CONTEXT);
        }

        @Test
        @DisplayName("Given every call is rejected, when executed, then the ToolManager is never called")
        void never_calls_tool_manager_when_nothing_is_approved() {
            ToolManager toolManager = mock(ToolManager.class);

            List<ToolResult> results = new IntentToolExecutor(toolManager)
                    .execute(List.of(new ToolCall("get_order_status", Map.of())), CONTEXT, placeIntent(0.99, true));

            assertThat(results).singleElement().satisfies(r -> assertThat(r.isError()).isTrue());
            verify(toolManager, never()).handleToolCalls(any(), any());
        }

        @Test
        @DisplayName("Given the ToolManager returns a different number of results, when executed, then fails loudly rather than misattributing results")
        void fails_when_tool_manager_breaks_result_contract() {
            ToolManager toolManager = mock(ToolManager.class);
            when(toolManager.handleToolCalls(any(), any())).thenReturn(List.of());
            ResolvedIntent lookup = new ResolvedIntent("catalog.product.lookup", 0.95, true, List.of(),
                    new DefaultIntentManager().getIntent("catalog.product.lookup").orElseThrow());
            IntentToolExecutor gate = new IntentToolExecutor(toolManager);

            assertThatThrownBy(() -> gate.execute(List.of(new ToolCall("get_product_by_sku", Map.of())), CONTEXT, lookup))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Given the CustomNextSpanAspect, when a batch is executed, then the span carries the intent and the batch outcome")
        void tags_execute_span_with_intent_and_outcome() {
            io.micrometer.tracing.Span span = mock(io.micrometer.tracing.Span.class);
            io.micrometer.tracing.Tracer tracer = mock(io.micrometer.tracing.Tracer.class);
            when(tracer.nextSpan()).thenReturn(span);
            when(span.name(anyString())).thenReturn(span);
            when(span.tag(anyString(), anyString())).thenReturn(span);
            when(span.start()).thenReturn(span);
            when(tracer.withSpan(span)).thenReturn(mock(io.micrometer.tracing.Tracer.SpanInScope.class));
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(text("Charger"));

            org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                    new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(executor());
            factory.setProxyTargetClass(true);
            factory.addAspect(new vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect(tracer));
            IntentToolExecutor proxy = factory.getProxy();
            ResolvedIntent search = new ResolvedIntent("catalog.product.search", 0.95, true,
                    List.of(Tool.builder("search_available_products", Map.of()).build()));

            proxy.execute(List.of(new ToolCall("search_available_products", Map.of()), new ToolCall("get_order_status", Map.of())),
                    CONTEXT, search);

            verify(span).name("mcp.polaris.execute");
            verify(span).tag("mcp.itent_id", "catalog.product.search");
            verify(span).tag("mcp.tool_call.count", "2");
            verify(span).tag("mcp.tool_call.success_count", "1");
            verify(span).tag("mcp.tool_call.error_count", "1");
            verify(span).tag("mcp.tool_call.outcome", "partial_failure");
            verify(span).tag("mcp.tool_call.failed_tools", "get_order_status");
        }

        @Test
        @DisplayName("Given a null tool call in the batch, when executed, then an error result is returned in its place")
        void handles_null_tool_call() {
            List<ToolResult> results = executor().execute(Arrays.asList((ToolCall) null), CONTEXT, null);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.isError()).isTrue();
                assertThat(r.result()).isEqualTo("Tool call cannot be null");
            });
        }

        @Test
        @DisplayName("Given null or empty tool calls, when executed, then returns empty results")
        void returns_empty_for_null_or_empty_calls() {
            IntentToolExecutor gate = executor();

            assertThat(gate.execute(null, CONTEXT, null)).isEmpty();
            assertThat(gate.execute(List.of(), CONTEXT, null)).isEmpty();
        }
    }
}
