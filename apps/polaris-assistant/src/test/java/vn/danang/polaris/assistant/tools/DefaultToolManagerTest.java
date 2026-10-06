package vn.danang.polaris.assistant.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.beans.factory.ObjectProvider;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;

/**
 * Discovery and dispatch only: intent validation and policy authorization happen before a call reaches
 * {@link DefaultToolManager} (see {@code IntentToolExecutorTest}).
 */
@ExtendWith(MockitoExtension.class)
class DefaultToolManagerTest {

    private static final ToolExecutionContext CONTEXT = new ToolExecutionContext("sess-1", "user-1", 1);

    @Mock
    private PolarisMcpClient polarisMcpClient;

    private DefaultToolManager manager;

    @BeforeEach
    void setUp() {
        manager = new DefaultToolManager(polarisMcpClient);
    }

    // =========================================================================
    // 1. Happy path — standard discovery and execution
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given registered client, when discovering tools, then delegates to PolarisMcpClient")
        void delegates_tool_discovery_to_mcp_client() {
            Tool tool = Tool.builder("search_available_products", Map.of())
                    .description("Search products")
                    .build();
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(tool));

            List<Tool> tools = manager.discoverAllTools();

            assertThat(tools)
                    .extracting(Tool::name)
                    .containsExactly("search_available_products");
            verify(polarisMcpClient, times(1)).listAvailableTools();
        }

        @Test
        @DisplayName("Given DefaultToolManager with CustomNextSpanAspect, when discovering tools, then tags span with mcp.tool_count")
        void tags_span_with_mcp_tool_count_when_discovering_tools() {
            io.micrometer.tracing.Span span = mock(io.micrometer.tracing.Span.class);
            io.micrometer.tracing.Tracer tracer = mock(io.micrometer.tracing.Tracer.class);
            io.micrometer.tracing.Tracer.SpanInScope spanInScope = mock(io.micrometer.tracing.Tracer.SpanInScope.class);
            when(tracer.nextSpan()).thenReturn(span);
            when(span.name(anyString())).thenReturn(span);
            when(span.tag(anyString(), anyString())).thenReturn(span);
            when(span.start()).thenReturn(span);
            when(tracer.withSpan(span)).thenReturn(spanInScope);

            org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                    new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(manager);
            factory.setProxyTargetClass(true);
            factory.addAspect(new vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect(tracer));
            DefaultToolManager proxy = factory.getProxy();

            Tool tool1 = Tool.builder("search_available_products", Map.of()).build();
            Tool tool2 = Tool.builder("get_product_by_sku", Map.of()).build();
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(tool1, tool2));

            List<Tool> tools = proxy.discoverAllTools();

            assertThat(tools).hasSize(2);
            verify(span).name("mcp.polaris.discovery");
            verify(span).tag("mcp.tool_count", "2");
            verify(span).start();
            verify(span).end();
        }

        @Test
        @DisplayName("Given a tool call, when handled, then executes it over MCP and returns a success ToolResult")
        void executes_remote_tool_call_and_returns_success() {
            CallToolResult toolResult = new CallToolResult(
                    List.of(TextContent.builder("Product found: Charger").build()),
                    false,
                    null,
                    Map.of()
            );
            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenReturn(toolResult);
            List<ToolCall> toolCalls = List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-123"));

            List<ToolResult> results = manager.handleToolCalls(toolCalls, CONTEXT);

            assertThat(results).hasSize(1);
            ToolResult result = results.getFirst();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.toolCall()).isEqualTo(toolCalls.getFirst());
            assertThat(result.result()).isEqualTo("Product found: Charger");
            assertThat(result.errorDescription()).isNull();
        }
    }

    // =========================================================================
    // 2. Failures & edge cases
    // =========================================================================
    @Nested
    @DisplayName("2. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given the MCP call fails or throws, when handled, then returns error ToolResults in call order")
        void maps_mcp_failures_to_error_results() {
            when(polarisMcpClient.callTool(eq("search_available_products"), any()))
                    .thenReturn(new CallToolResult(List.of(TextContent.builder("Bad page").build()), true, null, Map.of()));
            when(polarisMcpClient.callTool(eq("get_product_by_sku"), any())).thenThrow(new IllegalStateException("down"));

            List<ToolResult> results = manager.handleToolCalls(List.of(
                    new ToolCall("search_available_products", Map.of("page", -1)),
                    new ToolCall("get_product_by_sku", Map.of("sku", "X"))), CONTEXT);

            assertThat(results).extracting(r -> r.toolCall().name())
                    .containsExactly("search_available_products", "get_product_by_sku");
            assertThat(results).allMatch(ToolResult::isError);
            assertThat(results.get(0).result()).isEqualTo("Bad page");
            assertThat(results.get(1).result()).contains("down");
        }

        @Test
        @DisplayName("Given Polaris Core refuses with a 401 or 403 problem, when handled, then DENIED results; other problems stay errors")
        void maps_core_refusals_to_denied_results() {
            when(polarisMcpClient.callTool(eq("get_order_status"), any())).thenReturn(problem(401, "HTTP 401 Unauthorized"));
            when(polarisMcpClient.callTool(eq("get_order_details"), any())).thenReturn(problem(403, "Forbidden"));
            when(polarisMcpClient.callTool(eq("list_customer_orders"), any())).thenReturn(problem(404, "Not found"));

            List<ToolResult> results = manager.handleToolCalls(List.of(
                    new ToolCall("get_order_status", Map.of()),
                    new ToolCall("get_order_details", Map.of()),
                    new ToolCall("list_customer_orders", Map.of())), CONTEXT);

            assertThat(results).extracting(ToolResult::status)
                    .containsExactly(ToolResult.Status.DENIED, ToolResult.Status.DENIED, ToolResult.Status.ERROR);
            assertThat(results.get(0).result()).isEqualTo("HTTP 401 Unauthorized");
        }

        @Test
        @DisplayName("Given multiple remote tool calls, when handled, executes them concurrently and returns all tool results")
        void executes_remote_tool_calls_concurrently_in_parallel() {
            java.util.concurrent.CountDownLatch latch1 = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch latch2 = new java.util.concurrent.CountDownLatch(1);

            when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenAnswer(inv -> {
                latch1.countDown();
                boolean unblocked = latch2.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new IllegalStateException("Timeout waiting for search_promotions; calls did not execute concurrently");
                }
                return new CallToolResult(List.of(TextContent.builder("Charger").build()), false, null, Map.of());
            });

            when(polarisMcpClient.callTool(eq("search_promotions"), any())).thenAnswer(inv -> {
                latch2.countDown();
                boolean unblocked = latch1.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!unblocked) {
                    throw new IllegalStateException("Timeout waiting for search_available_products; calls did not execute concurrently");
                }
                return new CallToolResult(List.of(TextContent.builder("10% off").build()), false, null, Map.of());
            });

            List<ToolCall> toolCalls = List.of(
                    new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1"),
                    new ToolCall("search_promotions", Map.of("category", "all"), "sig-2")
            );

            List<ToolResult> results = manager.handleToolCalls(toolCalls, CONTEXT);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).toolCall().name()).isEqualTo("search_available_products");
            assertThat(results.get(0).result()).isEqualTo("Charger");
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).toolCall().name()).isEqualTo("search_promotions");
            assertThat(results.get(1).result()).isEqualTo("10% off");
            assertThat(results.get(1).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("Given active security context, when executing concurrent tool calls, propagates security context to tasks")
        void propagates_security_context_to_concurrent_tasks() {
            org.springframework.security.core.context.SecurityContext testContext =
                    org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            testContext.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("user-test", "pass", "SCOPE_catalog.read"));
            org.springframework.security.core.context.SecurityContextHolder.setContext(testContext);

            try {
                when(polarisMcpClient.callTool(eq("search_available_products"), any())).thenAnswer(inv -> {
                    org.springframework.security.core.Authentication currentAuth =
                            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    if (currentAuth == null || !"user-test".equals(currentAuth.getName())) {
                        throw new IllegalStateException("SecurityContext not propagated to worker thread");
                    }
                    return new CallToolResult(List.of(TextContent.builder("Auth OK").build()), false, null, Map.of());
                });

                List<ToolResult> results = manager.handleToolCalls(
                        List.of(new ToolCall("search_available_products", Map.of("query", "charger"), "sig-1")),
                        CONTEXT
                );

                assertThat(results).hasSize(1);
                assertThat(results.getFirst().result()).isEqualTo("Auth OK");
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("Given null or empty tool calls, when handled, then returns empty results")
        void returns_empty_result_for_null_or_empty_tool_calls() {
            assertThat(manager.handleToolCalls(null, null)).isEmpty();
            assertThat(manager.handleToolCalls(List.of(), null)).isEmpty();
        }

        @Test
        @DisplayName("Given DefaultToolManager with managed executor, when destroyed, then shuts down executor")
        void shuts_down_managed_executor_on_destroy() {
            DefaultToolManager managed = new DefaultToolManager(polarisMcpClient);
            managed.destroy();
            // calling destroy again is safe and idempotent
            managed.destroy();
        }

        @Test
        @DisplayName("Given DefaultToolManager with custom unmanaged executor, when destroyed, does not shut down custom executor")
        void does_not_shut_down_custom_executor_on_destroy() {
            ExecutorService customExecutor = Executors.newSingleThreadExecutor();
            try {
                DefaultToolManager unmanaged = new DefaultToolManager(polarisMcpClient, customExecutor, null);
                unmanaged.destroy();
                assertThat(customExecutor.isShutdown()).isFalse();
            } finally {
                customExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("Given custom ObjectProviders, initializes with provided beans")
        @SuppressWarnings("unchecked")
        void initializes_with_provided_beans_from_object_providers() {
            ObjectProvider<Executor> executorProvider = mock(ObjectProvider.class);
            ObjectProvider<LocalTool> localToolsProvider = mock(ObjectProvider.class);
            ExecutorService customExecutor = Executors.newSingleThreadExecutor();
            when(executorProvider.getIfAvailable()).thenReturn(customExecutor);
            when(localToolsProvider.orderedStream()).thenReturn(java.util.stream.Stream.empty());

            DefaultToolManager provided = new DefaultToolManager(polarisMcpClient, executorProvider, localToolsProvider);

            try {
                provided.destroy();
                assertThat(customExecutor.isShutdown()).isFalse();
            } finally {
                customExecutor.shutdown();
            }
        }

        @Test
        @DisplayName("Given null ObjectProviders in constructor, falls back to defaults safely")
        void falls_back_to_defaults_when_providers_null() {
            DefaultToolManager fallback = new DefaultToolManager(polarisMcpClient, (ObjectProvider<Executor>) null, null);
            fallback.destroy();
        }
    }

    // =========================================================================
    // 3. Local tools (stage_order_draft / discard_order_draft)
    // =========================================================================
    @Nested
    @DisplayName("3. Local tools")
    class LocalTools {

        private final List<ToolCall> executed = new ArrayList<>();

        private LocalTool localTool(String name) {
            Tool definition = Tool.builder(name, Map.of()).description("local " + name).build();
            return new LocalTool() {
                @Override
                public Tool definition() {
                    return definition;
                }

                @Override
                public ToolResult execute(ToolCall toolCall, ToolExecutionContext context) {
                    executed.add(toolCall);
                    return ToolResult.success(toolCall, "staged for " + context.userId());
                }
            };
        }

        private DefaultToolManager withDraftTools() {
            return new DefaultToolManager(polarisMcpClient, null,
                    List.of(localTool("stage_order_draft"), localTool("discard_order_draft")));
        }

        @Test
        @DisplayName("Given local tools, when discovering, then they are listed next to the remote tools and replace same-named remote tools")
        void discovery_includes_local_tools() {
            when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(
                    Tool.builder("search_available_products", Map.of()).build(),
                    Tool.builder("stage_order_draft", Map.of()).description("remote impostor").build()));

            List<Tool> tools = withDraftTools().discoverAllTools();

            assertThat(tools).extracting(Tool::name)
                    .containsExactly("search_available_products", "stage_order_draft", "discard_order_draft");
            assertThat(tools).filteredOn(t -> t.name().equals("stage_order_draft"))
                    .extracting(Tool::description).containsExactly("local stage_order_draft");
        }

        @Test
        @DisplayName("Given two local tools with the same name, when constructed, then fails fast")
        void rejects_duplicate_local_tool_names() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DefaultToolManager(polarisMcpClient, null,
                            List.of(localTool("stage_order_draft"), localTool("stage_order_draft"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stage_order_draft");
        }

        @Test
        @DisplayName("Given stage_order_draft is called, then dispatched locally, never over MCP")
        void dispatches_local_tool_locally() {
            List<ToolResult> results = withDraftTools().handleToolCalls(
                    List.of(new ToolCall("stage_order_draft", Map.of("items", List.of()))), CONTEXT);

            assertThat(results).singleElement().satisfies(r -> {
                assertThat(r.isSuccess()).isTrue();
                assertThat(r.result()).isEqualTo("staged for user-1");
            });
            assertThat(executed).extracting(ToolCall::name).containsExactly("stage_order_draft");
            verify(polarisMcpClient, never()).callTool(anyString(), any());
        }

        @Test
        @DisplayName("Given stage then discard in one batch, when handled, then the mutating local tools run one after another in call order")
        void mutating_local_tools_run_sequentially_in_call_order() {
            List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
            LocalTool slowStage = new LocalTool() {
                @Override
                public Tool definition() {
                    return Tool.builder("stage_order_draft", Map.of()).build();
                }

                @Override
                public ToolResult execute(ToolCall toolCall, ToolExecutionContext context) {
                    events.add("start " + toolCall.args().get("n"));
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    events.add("end " + toolCall.args().get("n"));
                    return ToolResult.success(toolCall, "staged");
                }
            };
            LocalTool discard = new LocalTool() {
                @Override
                public Tool definition() {
                    return Tool.builder("discard_order_draft", Map.of()).build();
                }

                @Override
                public ToolResult execute(ToolCall toolCall, ToolExecutionContext context) {
                    events.add("start discard");
                    events.add("end discard");
                    return ToolResult.success(toolCall, "discarded");
                }
            };
            DefaultToolManager sequential = new DefaultToolManager(polarisMcpClient, null, List.of(slowStage, discard));

            List<ToolResult> results = sequential.handleToolCalls(List.of(
                    new ToolCall("stage_order_draft", Map.of("n", 1)),
                    new ToolCall("stage_order_draft", Map.of("n", 2)),
                    new ToolCall("discard_order_draft", Map.of())), CONTEXT);

            assertThat(results).extracting(ToolResult::result).containsExactly("staged", "staged", "discarded");
            assertThat(events).containsExactly("start 1", "end 1", "start 2", "end 2", "start discard", "end discard");
        }
    }

    private static CallToolResult problem(int status, String detail) {
        Map<String, Object> problem = Map.of("type", "about:blank", "title", "Refused", "status", status, "detail", detail);
        return new CallToolResult(List.of(TextContent.builder(detail).build()), true, problem, Map.of());
    }
}
