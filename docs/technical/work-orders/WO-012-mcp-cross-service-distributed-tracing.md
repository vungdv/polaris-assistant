# Slice Work Order: WO-012
## Title: Cross-Service Distributed Tracing for Assistant-to-Polaris MCP & MCP Server Spans

- **Target Modules:** `apps/polaris-assistant`, `apps/polaris`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0012](../decisions/0012-mcp-cross-service-distributed-tracing.md)
- **Bounded Contexts:** Polaris Assistant Context, Polaris Core MCP Gateway Context
- **Status:** READY FOR DEV

---

## 1. Objective & Scope

Establish seamless, unbroken distributed tracing between `apps/polaris-assistant` and `apps/polaris` over the Model Context Protocol (MCP) boundary:
1. **Assistant MCP Egress Tracing:** Inject W3C `traceparent` headers in all outbound JSON-RPC calls from `HttpPolarisMcpClient`, and instrument `ExternalMcpHub.discoverAllTools()` with a dedicated trace span (`mcp.list_tools`).
2. **Polaris Core MCP Server Tracing:** Instrument tool execution methods in `ProductMcpTools` and `OrderMcpTools` with child spans (`mcp.server.tool_call <tool_name>`), capturing standard tags and error flags.

**Constraint Checklist:**
- [x] Preserve binary compatibility of existing constructors and public contracts.
- [x] All tracing logic must be non-blocking and fail-safe against null tracers.
- [x] Strict conformance to W3C Trace Context specification (`00-{traceId}-{spanId}-{flags}`).
- [x] Comprehensive automated unit & integration tests for all modified components.

---

## 2. Detailed Technical Tasks

### Task 1: Update `HttpPolarisMcpClient.java`
**File:** `tools`
1. Inject `ObjectProvider<Tracer> tracerProvider` in `@Autowired` constructor, storing `@Nullable private final Tracer tracer;`.
2. Add overloaded constructors maintaining backwards-compatibility for existing tests.
3. In `buildJsonRpcRequest`, implement and invoke `injectTraceParent(HttpRequest.Builder builder)`:
   - If `tracer != null`, extract active span or trace context (`traceId`, `spanId`, `sampled`).
   - Add header: `builder.header("traceparent", "00-" + traceId + "-" + spanId + "-" + sampled);`.

### Task 2: Update `ExternalMcpHub.java`
**File:** `tools`
1. Instrument `discoverAllTools()`:
   - When `tracer != null`, start span `mcp.list_tools`.
   - Tag `mcp.provider`: `"polaris-core"`, `mcp.operation`: `"tools/list"`.
   - Tag `mcp.tools.count` on success.
   - Tag `error: true` and record exception on error.
2. In `executeTool()`, ensure `mcp.operation: tools/call` tag is present alongside existing tags.

### Task 3: Update `ProductMcpTools.java`
**File:** `apps/polaris/src/main/java/vn/danang/polaris/mcp/ProductMcpTools.java`
1. Implement `executeWithSpan(String toolName, Supplier<CallToolResult> execution)`:
   - Start span `mcp.server.tool_call <toolName>`.
   - Tags: `mcp.tool.name`: `toolName`, `mcp.server`: `"polaris-mcp"`, `mcp.category`: `"catalog"`.
   - Tag `error: true` if `result.isError() == true`.
   - Catch `Exception`, tag `error: true`, record `span.error(e)`.
2. Wrap `searchAvailableProducts(Map<String, Object> arguments)` and `getProductBySku(Map<String, Object> arguments)`.

### Task 4: Update `OrderMcpTools.java`
**File:** `apps/polaris/src/main/java/vn/danang/polaris/mcp/OrderMcpTools.java`
1. Implement `executeWithSpan(String toolName, Supplier<CallToolResult> execution)`:
   - Start span `mcp.server.tool_call <toolName>`.
   - Tags: `mcp.tool.name`: `toolName`, `mcp.server`: `"polaris-mcp"`, `mcp.category`: `"order"`.
   - Tag `error: true` if `result.isError() == true`.
   - Catch `Exception`, tag `error: true`, record `span.error(e)`.
2. Wrap `getOrderStatus`, `getOrderDetails`, `placeOrder`, `listCustomerOrders`, and `cancelOrder`.

### Task 5: Add Unit & Integration Tests
1. `tools`:
   - Verify `traceparent` header is injected on outgoing request when tracer is active.
   - Verify graceful operation without tracer.
2. `tools`:
   - Verify `discoverAllTools()` creates span `mcp.list_tools` and tags counts.
3. `apps/polaris/src/test/java/vn/danang/polaris/mcp/McpServerTest.java`:
   - Verify `ProductMcpTools` and `OrderMcpTools` create spans and tags for tool executions.
   - Verify error tagging on failure.

---

## 3. Verification & Acceptance Criteria

Run reactor test suite:
```bash
mvn clean test
```
All tests across `polaris-common`, `polaris`, and `polaris-assistant` must pass.
