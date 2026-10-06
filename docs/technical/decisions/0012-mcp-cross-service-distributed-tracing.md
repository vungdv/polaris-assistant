# ADR-0012: Cross-Service Distributed Tracing for Assistant-to-Polaris MCP Communications & MCP Server Spans

* **Status:** Accepted
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-09-15
* **Technical Story:** Establishing end-to-end distributed tracing across the Model Context Protocol (MCP) boundary between `apps/polaris-assistant` and `apps/polaris`, injecting W3C `traceparent` context in `HttpPolarisMcpClient`, and instrumenting Polaris Core MCP tool execution with Micrometer Tracing server spans.

---

## 1. Context and Problem Statement

Following [ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md) and [ADR-0010](0010-mcp-client-authentication-and-token-forwarding.md), Polaris Assistant operates as an independent microservice (`apps/polaris-assistant`) that interacts with Polaris Core (`apps/polaris`) exclusively via the Model Context Protocol (MCP) over HTTP (`POST /mcp` or `POST /mcp/message`). 

Furthermore, [ADR-0011](0011-gemini-model-call-distributed-tracing.md) established distributed tracing for Google Gemini foundation model calls (`GeminiAiModelClient`). However, distributed tracing across the MCP inter-service boundary remained broken:

1. **Context Severance Across HTTP Egress:** When `PolicyToolManager` in `apps/polaris-assistant` starts a client span (`mcp.tool_call <tool_name>`), `HttpPolarisMcpClient` dispatches the JSON-RPC request to `http://polaris:8080/mcp` without injecting the W3C `traceparent` header. As a result, Polaris Core cannot correlate incoming tool requests with the caller's trace and initiates a disjointed, orphaned trace.
2. **Untracked Tool Discovery (`tools/list`):** `ExternalMcpHub.discoverAllTools()` invokes `HttpPolarisMcpClient.listAvailableTools()` without creating a span, leaving tool schema discovery unmeasured.
3. **Missing Server-Side MCP Spans in Polaris Core:** Within `apps/polaris`, `ProductMcpTools` and `OrderMcpTools` receive an injected `Tracer` bean, but do not create spans around tool execution. In Grafana Tempo, the internal execution latency of `search_available_products`, `place_order`, etc., is invisible between the HTTP ingress span and database queries.
4. **Architectural Non-Conformance ([AGENTS.md](../../../AGENTS.md) Principle 3.1):** Distributed tracing mandates propagating standard W3C Trace Context across all service boundaries and emitting correlated spans with standard tags.

How should Polaris architect cross-service trace propagation and MCP server instrumentation to ensure unbroken, walkable trace trees from Assistant Ingress &rarr; Gemini LLM &rarr; Polaris MCP Client &rarr; Polaris Core MCP Server &rarr; PostgreSQL?

---

## 2. Decision Drivers

* **Observability by Default ([AGENTS.md: Principle 3.1](../../../AGENTS.md)):** Unbroken distributed tracing across Web Gateway &rarr; Assistant App &rarr; Gemini API &rarr; Polaris Core MCP Server &rarr; PostgreSQL.
* **Standard Protocol Alignment (W3C Trace Context / RFC 9110):** Propagate context strictly via standard W3C `traceparent` headers (`00-{traceId}-{spanId}-{flags}`) without proprietary or bespoke headers.
* **Framework Homogeneity:** Leverage existing Spring Boot 4.x / Micrometer Tracing (`io.micrometer.tracing.Tracer`, `Span`, `TraceContext`) used in `PolicyToolManager` and `GeminiAiModelClient`.
* **Fail-Safe & Non-Blocking Resilience:** Tracing failures (missing tracer, uninitialized context) must never crash tool discovery, tool invocation, or commerce operations.
* **Binary & Interface Stability ([Evaluation Criteria B1-B4](../../../.agents/agents/arch-agent.md)):** Maintain backwards-compatible constructors and contracts across `PolarisMcpClient`, `ProductMcpTools`, and `OrderMcpTools`.

---

## 3. Considered Options

### Option 1: Manual W3C Header Injection & In-Process Tool Execution Spans (Selected)
- **Assistant MCP Client (`apps/polaris-assistant`):** Inject `ObjectProvider<Tracer>` into `HttpPolarisMcpClient`. Before sending JSON-RPC HTTP requests in `buildJsonRpcRequest`, extract the active trace context from `tracer.currentSpan()` or `tracer.currentTraceContext()` and inject `traceparent: 00-{traceId}-{spanId}-{sampled}`. Instrument `discoverAllTools()` in `PolicyToolManager` with span `mcp.list_tools`.
- **Polaris Core MCP Server (`apps/polaris`):** Utilize the `Tracer` already injected into `ProductMcpTools` and `OrderMcpTools` to wrap each tool invocation in a server child span (`mcp.server.tool_call <tool_name>`). Tag standard attributes (`mcp.tool.name`, `mcp.server`, `mcp.category`, `error`), and ensure errors and exceptions are recorded on the span.

* **Pros:** Direct alignment with existing `GeminiAiModelClient` and `PolicyToolManager` implementations; zero additional dependencies; transparent trace hierarchy in Tempo; non-intrusive to existing MCP SDK transport.
* **Cons:** Requires updating tool methods in `ProductMcpTools` and `OrderMcpTools` to wrap logic in span execution templates.

### Option 2: Java HTTP Client Interceptor & Servlet Filter Auto-Instrumentation
Instrument `HttpClient` in `apps/polaris-assistant` via custom decorators and configure a custom Servlet Filter in `apps/polaris` around `statelessMcpServlet`.

* **Pros:** Centralized HTTP-level interceptor.
* **Cons:** Loses MCP domain semantic tags (`mcp.tool.name`, `order.number`, `product.sku`); complex reflection required for `java.net.http.HttpClient` customization; does not trace discrete tool methods if multiple JSON-RPC calls share a connection.

---

## 4. Decision Outcome & Technical Specification

**Chosen Option:** Option 1: Manual W3C Header Injection & In-Process Tool Execution Spans.

### Detailed Technical Specifications:

#### 1. Outbound W3C Traceparent Context Injection (`apps/polaris-assistant`)
In `HttpPolarisMcpClient`:
- Inject `ObjectProvider<Tracer> tracerProvider` in `@Autowired` constructor, storing `@Nullable private final Tracer tracer`.
- Preserve existing constructors for testing and backwards compatibility.
- Implement `injectTraceParent(HttpRequest.Builder builder)`:
  ```java
  private void injectTraceParent(HttpRequest.Builder builder) {
      if (this.tracer == null) {
          return;
      }
      Span currentSpan = this.tracer.currentSpan();
      TraceContext context = (currentSpan != null) ? currentSpan.context()
              : (this.tracer.currentTraceContext() != null ? this.tracer.currentTraceContext().context() : null);
      if (context != null && context.traceId() != null && context.spanId() != null) {
          String sampled = (context.sampled() != null && !context.sampled()) ? "00" : "01";
          builder.header("traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-" + sampled);
      }
  }
  ```
- Invoke `injectTraceParent(builder)` in `buildJsonRpcRequest(String jsonBody)` so both `tools/list` and `tools/call` HTTP requests carry the W3C trace context.

#### 2. Tool Discovery Span in `PolicyToolManager` (`apps/polaris-assistant`)
In `ExternalMcpHub.discoverAllTools()`:
- If `tracer != null`, start span named `mcp.list_tools`.
- Tag `mcp.provider`: `"polaris-core"`, `mcp.operation`: `"tools/list"`.
- On success, tag `mcp.tools.count`.
- On failure, tag `error: true` and record exception.

#### 3. In-Process MCP Server Spans in Polaris Core (`apps/polaris`)
In `ProductMcpTools` and `OrderMcpTools`:
- Leverage `@Nullable private final Tracer tracer` already present in constructors.
- Implement helper `executeWithSpan(String toolName, String category, Supplier<CallToolResult> execution)`:
  - Span name: `mcp.server.tool_call <toolName>` (e.g. `mcp.server.tool_call search_available_products`).
  - Tags:
    - `mcp.tool.name`: `<toolName>`
    - `mcp.server`: `"polaris-mcp"`
    - `mcp.category`: `"catalog"` or `"order"`
  - Status & Error Tagging: If `result.isError() == true`, tag `error: true`. If an unhandled exception is caught, call `span.error(e)` and tag `error: true`.
  - Always close scope and call `span.end()` in `finally`.

---

## 5. Boundary & Interface Stability Analysis

| Dimension | Classification | Details |
|---|---|---|
| **Public Interface** | Stable | `PolarisMcpClient` contract untouched (`listAvailableTools`, `callTool`). |
| **Constructor Contracts** | Backwards Compatible | Overloaded constructors preserve existing 1-arg, 2-arg, 3-arg signatures. |
| **Network Protocol** | Strictly Standard (RFC 9110 / W3C) | Standard HTTP header `traceparent` added to outbound requests; no breaking wire changes. |
| **Downstream Impact** | Purely Additive | Grafana Tempo displays child server spans underneath client tool dispatch spans; zero breaking changes. |
| **Reversibility** | Immediate / Zero Risk | Tracing is non-blocking and automatically deactivates when `tracer == null`. |

---

## 6. Verification Plan

1. **Unit Verification (`apps/polaris-assistant`):**
   - `HttpPolarisMcpClientTest`: Verify `traceparent` header is added to `HttpRequest` when tracer has active span; verify graceful handling when tracer is null.
   - `ExternalToolManagerTest`: Verify `discoverAllTools()` creates span `mcp.list_tools` with appropriate tags.
2. **Unit & Integration Verification (`apps/polaris`):**
   - `McpServerTest`: Verify `ProductMcpTools` and `OrderMcpTools` create spans `mcp.server.tool_call <name>` with tags `mcp.tool.name`, `mcp.server`, `mcp.category`, and tag `error="true"` on failure.
3. **Reactor Verification:**
   - Run `mvn clean test` across the monorepo reactor (`polaris-parent`).
