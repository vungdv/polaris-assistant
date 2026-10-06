# ADR-0013: AI Assistant Turn Enclosing Span and Structured Lifecycle Events

* **Status:** Accepted
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-09-16
* **Technical Story:** Instrumenting `AssistantChatService` in `apps/polaris-assistant` with an enclosing Micrometer Tracing span (`agent.turn`), active scope propagation (`withSpan`), and structured OpenTelemetry/Micrometer span events mapping the full ReAct agent execution lifecycle.
* **Product Reference:** [PRD-005](../../business/prds/PRD-005-agent-turn-observability-and-lifecycle-events.md)
* **Work Order Reference:** [WO-013](../work-orders/WO-013-agent-turn-observability-events.md)

---

## 1. Context and Problem Statement

Polaris Assistant operates as an autonomous microservice (`apps/polaris-assistant`) adhering to [ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md). Inbound conversational requests (`POST /api/v1/assistant/chat`) are handled by `AssistantChatController`, which delegates execution to `AssistantChatService.sendMessage()`. Inside this service, an autonomous ReAct loop runs up to `MAX_TOOL_ITERATIONS` (5), querying Google Gemini for inference and executing tools via the Model Context Protocol (`PolicyToolManager`).

Prior telemetry milestones established:
- [ADR-0011](0011-gemini-model-call-distributed-tracing.md): Distributed tracing child spans (`gemini.generate_content <model>`) for model calls.
- [ADR-0012](0012-mcp-cross-service-distributed-tracing.md): Distributed tracing spans for tool discovery (`mcp.list_tools`) and cross-service tool calls (`mcp.tool_call <name>`).

### The Architectural Gap:
Currently, when a trace is viewed in Grafana Tempo, all child spans (`mcp.list_tools`, `gemini.generate_content`, `mcp.tool_call`) attach directly to the top-level HTTP servlet span (`POST /api/v1/assistant/chat`). 
1. **Missing Enclosing Turn Span:** There is no distinct span representing the agent turn execution. The in-process orchestration latency of `AssistantChatService` is conflated with HTTP ingress/egress framing.
2. **Missing Milestone Visibility:** The internal lifecycle steps of the ReAct loop (request receipt, tool discovery completion, iteration boundaries, model request/response transitions, tool call dispatch/result receipt, and final response generation) are completely invisible in tracing systems.
3. **Risk of Span Explosion:** Spawning individual child spans for every micro-milestone inside a loop creates severe trace storage bloat, CPU overhead, and noisy trace trees.

How should Polaris architect agent-turn observability to provide zero-blind-spot milestone tracking while preventing span explosion and maintaining strict backwards compatibility?

---

## 2. Decision Drivers

* **Observability by Default ([AGENTS.md Principle 3.1](../../../AGENTS.md)):** Unbroken, walkable trace trees with standard semantic tags and status attribution.
* **OpenTelemetry GenAI & Agent Semantic Alignment:** Support emerging OpenTelemetry GenAI agent conventions where a single enclosing span captures the agent invocation, and lifecycle checkpoints are recorded as timestamped span events.
* **Hierarchical Trace Clarity:** By placing `agent.turn` into the active trace scope (`tracer.withSpan(agentSpan)`), all existing child spans (`gemini.generate_content`, `mcp.tool_call`, `mcp.list_tools`) automatically nest beneath `agent.turn`.
* **Low Overhead & Anti-Span-Explosion:** Use lightweight Micrometer `span.event(name)` timestamped annotations rather than allocating independent child spans for every loop milestone.
* **Fail-Safe & Non-Blocking Resilience:** Telemetry failures or null tracers must never interrupt conversational commerce.
* **Binary & Interface Stability ([Evaluation Criteria B1-B4](../../../.agents/agents/arch-agent.md)):** Zero breaking changes to `AssistantChatService` public methods or constructors.

---

## 3. Considered Options

### Option 1: In-Process Micrometer Tracing Enclosing Span with Structured Span Events (Selected)
Inject `ObjectProvider<Tracer>` into `AssistantChatService`. Open an enclosing child span named `agent.turn`, activate it using `tracer.withSpan(span)`, and record timestamped events using `span.event(eventName)` at each milestone in the ReAct lifecycle.

* **Pros:**
  - Automatically nests existing model and MCP spans under `agent.turn` without touching `GeminiAiModelClient` or `PolicyToolManager`.
  - Zero span explosion: Exactly 1 enclosing span is created per turn; milestones appear as timestamped annotations in Grafana Tempo.
  - Full access to ReAct internal variables (iteration count, tool counts, session/user IDs).
  - Clean null-safe guard (`if (this.tracer != null)`).
* **Cons:** Requires updating `AssistantChatService` constructors and method body.

### Option 2: Independent Child Spans for Every ReAct Step
Create independent child spans (e.g. `agent.iteration.1`, `agent.tool.dispatch`) for each loop step.

* **Pros:** Detailed duration bars for every step in Tempo.
* **Cons:** Causes span explosion (10–20 spans per conversational turn); multiplies network and storage costs in OTLP and Tempo; creates deeply nested, unreadable trace flamegraphs.

### Option 3: Spring AOP / Annotation-Based Tracing (`@Observed` / `@NewSpan`)
Annotate `AssistantChatService.sendMessage` with `@Observed`.

* **Pros:** Declarative.
* **Cons:** Coarse-grained; cannot record milestone events inside the ReAct while-loop; cannot access loop iteration counters or tool call results; cannot tag `agent.session_id` or `agent.user_id` without reflection.

---

## 4. Decision Outcome & Detailed Technical Specification

**Chosen Option:** Option 1: In-Process Micrometer Tracing Enclosing Span with Structured Span Events.

### Architectural Specification:

#### 1. Constructor Injection & Backwards Compatibility
In `AssistantChatService.java`:
```java
@Autowired
public AssistantChatService(
        AssistantModelClient modelClient,
        ExternalMcpHub toolManager,
        ObjectMapper objectMapper,
        ObjectProvider<Tracer> tracerProvider) {
    this(modelClient, toolManager, objectMapper, tracerProvider != null ? tracerProvider.getIfAvailable() : null);
}

// Backwards-compatible constructors for testing & legacy calls
public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub toolManager, ObjectMapper objectMapper) {
    this(modelClient, toolManager, objectMapper, (Tracer) null);
}

public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub toolManager) {
    this(modelClient, toolManager, new ObjectMapper(), (Tracer) null);
}

public AssistantChatService(AssistantModelClient modelClient) {
    this(modelClient, null, new ObjectMapper(), (Tracer) null);
}

public AssistantChatService(
        AssistantModelClient modelClient,
        ExternalMcpHub toolManager,
        ObjectMapper objectMapper,
        @Nullable Tracer tracer) {
    this.modelClient = modelClient;
    this.toolManager = toolManager;
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    this.tracer = tracer;
}
```

#### 2. Enclosing Span Lifecycle (`agent.turn`)
In `AssistantChatService.sendMessage(ChatMessageRequest request, String userId)`:
- If `this.tracer == null`, execute existing logic cleanly.
- If `this.tracer != null`:
  - Create span: `Span span = this.tracer.customNextSpan().name("agent.turn");`
  - Tag initial attributes:
    - `agent.name`: `"assistant-chat"`
    - `agent.framework`: `"polaris-assistant"`
    - `agent.session_id`: `sessionId`
    - `agent.user_id`: `userId != null ? userId : "anonymous"`
  - Start span: `span.start();`
  - Activate scope: `try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) { ... }`
  - Tag final metrics before completion:
    - `agent.iterations.count`: `String.valueOf(iterations)`
    - `agent.tools.count`: `String.valueOf(availableTools.size())`
  - Catch `Exception ex`:
    - Call `span.error(ex);`
    - Tag `span.tag("error", "true");`
    - Re-throw `ex`.
  - Finally:
    - Call `span.end();`

#### 3. Structured Lifecycle Span Events
Events must be recorded at exact execution points using `recordEvent(span, eventName)`:

| Event Name | Exact Execution Location |
|---|---|
| `agent.request.received` | Immediately upon entering `sendMessage()`, before input validation and tool discovery. |
| `tools.discovered` | Immediately after `toolManager.discoverAllTools()` returns. |
| `agent.iteration.started` | At the very beginning of each ReAct while-loop iteration (`iterations++`). |
| `model.request` | Immediately before calling `modelClient.generateResponse(...)` (or fallback `chat()`). |
| `model.response` | Immediately after `modelClient.generateResponse` returns `ModelResponse`. |
| `agent.tool.call` | In the tool execution loop, immediately before calling `toolManager.executeTool(...)`. |
| `agent.tool.result` | In the tool execution loop, immediately after receiving `CallToolResult` and extracting text. |
| `agent.response.generated` | Once the while-loop exits, immediately after final reply text is determined and added to history. |
| `agent.completed` | Immediately before creating and returning `ChatMessageResponse`. |

#### 4. Trace Tree Hierarchy in Tempo
With `Tracer.SpanInScope` active around the turn, the resulting trace tree in Grafana Tempo becomes:
```
POST /api/v1/assistant/chat (HTTP Ingress Span)
  └── agent.turn (Enclosing Agent Span)
       ├── [event: agent.request.received]
       ├── mcp.list_tools (Child Span from ExternalMcpHub)
       ├── [event: tools.discovered]
       ├── [event: agent.iteration.started]
       ├── [event: model.request]
       ├── gemini.generate_content gemini-3.6-flash (Child Span from GeminiAiModelClient)
       ├── [event: model.response]
       ├── [event: agent.tool.call]
       ├── mcp.tool_call search_available_products (Child Span from ExternalMcpHub)
       │    └── mcp.server.tool_call search_available_products (Server Span in Polaris Core)
       ├── [event: agent.tool.result]
       ├── [event: agent.iteration.started]
       ├── [event: model.request]
       ├── gemini.generate_content gemini-3.6-flash (Child Span from GeminiAiModelClient)
       ├── [event: model.response]
       ├── [event: agent.response.generated]
       └── [event: agent.completed]
```

---

## 5. Boundary & Interface Stability Analysis

| Dimension | Classification | Details |
|---|---|---|
| **Public Service Interface** | Stable | `AssistantChatService.sendMessage(ChatMessageRequest, String)` signature is unchanged. |
| **Constructor Contract** | Additive / Backwards Compatible | New 4-arg constructor added; existing 1-arg, 2-arg, and 3-arg constructors preserved. |
| **REST Endpoints** | Stable | `POST /api/v1/assistant/chat` signature and payload unchanged. |
| **Telemetry Egress** | Additive | Standard OTLP span and event data sent to OpenTelemetry Collector; zero breaking changes. |
| **Downstream Impact** | Zero Breakage | Existing child spans become organized under `agent.turn` in Tempo; no external client disruption. |
| **Reversibility** | Immediate / Zero Risk | Tracing is fail-safe; deactivates automatically if `tracer == null` or `management.tracing.enabled=false`. |

---

## 6. Verification Plan

1. **Unit Verification (`apps/polaris-assistant`):**
   - Update `AssistantChatServiceTest.java`:
     - Test full lifecycle event sequence with Mockito `Tracer`, `Span`, and `SpanInScope`.
     - Test direct reply (zero tool calls) lifecycle event sequence.
     - Test multi-iteration tool call lifecycle event sequence.
     - Test max iterations reached event count and tag.
     - Test error handling: verify `span.error(e)` and `error="true"` tag when an exception occurs.
     - Test null tracer resilience: verify existing tests run without tracer and execute cleanly.
2. **Reactor Verification:**
   - Execute `mvn clean test` across the entire monorepo.
