# PRD-005: AI Assistant Turn Observability & ReAct Lifecycle Span Events

- **Context/Domain:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Target Personas:** Site Reliability Engineers (SRE), AI Agent Platform Developers, Store Operations / Support Specialists
- **Status:** Approved (Ready for Implementation)
- **Document Owner:** Polaris Fleet Architect & Product Lead
- **Last Updated:** 2026-09-16
- **Work Order Reference:** [WO-013](../../technical/work-orders/WO-013-agent-turn-observability-events.md)
- **ADR Reference:** [ADR-0013](../../technical/decisions/0013-agent-turn-span-and-lifecycle-events.md)

---

## 1. Business Problem & Value Proposition

In Polaris conversational commerce, the AI Assistant translates shopper questions into catalog queries and checkout operations via an autonomous ReAct loop (`AssistantChatService`). Each conversational turn alternates between foundation model inference (Google Gemini) and tool execution (Model Context Protocol).

While individual child spans currently exist for Gemini inference (`gemini.generate_content`, [PRD-004](PRD-004-gemini-model-observability-and-distributed-tracing.md) / [ADR-0011](../../technical/decisions/0011-gemini-model-call-distributed-tracing.md)) and MCP tool execution (`mcp.tool_call`, [ADR-0012](../../technical/decisions/0012-mcp-cross-service-distributed-tracing.md)), they currently attach directly under the top-level HTTP ingress span (`POST /api/v1/assistant/chat`). 

This architecture leaves the internal ReAct decision loop an opaque black box:
1. **Missing Enclosing Turn Span:** Operators cannot measure the total in-process duration of the AI agent turn separate from HTTP serialization, authentication, and network transit.
2. **Missing Lifecycle Milestones:** Operators and developers cannot pinpoint when tool discovery completed, when specific loop iterations started, when prompt inference was submitted versus returned, or when the final response was synthesized.
3. **Loop Convergence & Abort Blind Spots:** When an agent turn hits maximum iterations or experiences a delay, telemetry cannot reveal which specific iteration or tool triggered the stall.
4. **Span Explosion vs. Event Precision:** Creating separate child spans for internal loop milestones would flood Grafana Tempo and OpenTelemetry collectors with dozens of short-lived micro-spans. Standardized **Span Events** on an enclosing span provide exact timestamped milestones with near-zero overhead.

### Value Delivered:
1. **Turn-Level Hierarchical Tracing:** An enclosing parent span (`agent.turn`) groups all underlying model and tool spans into an intuitive, walkable trace tree in Grafana Tempo.
2. **Deterministic Milestone Progression:** Timestamped span events (`agent.request.received`, `tools.discovered`, `agent.iteration.started`, `model.request`, `model.response`, `agent.tool.call`, `agent.tool.result`, `agent.response.generated`, `agent.completed`) reveal exact execution flow without span bloat.
3. **Rapid Fault Isolation:** Pinpoint failure causes in seconds (e.g. failing during tool discovery vs. iteration 2 model inference vs. tool execution).
4. **Agent Optimization Insights:** Real-time correlation of iteration counts, tool counts, session IDs, and user identities.

---

## 2. Personas & Use Cases

### Persona A: Site Reliability Engineer (SRE) / Observability Engineer
- **Goal:** Diagnose latency spikes and investigate failed turns on `/api/v1/assistant/chat`.
- **Use Case:** Inspects a trace in Tempo; immediately observes whether a 4-second turn was caused by 3 tool iterations, slow Gemini response time, or MCP latency, marked precisely by milestone events on `agent.turn`.

### Persona B: AI Agent Platform Engineer / Prompt Engineer
- **Goal:** Optimize agent prompt efficiency and ReAct loop convergence.
- **Use Case:** Filters traces in Tempo by `agent.iterations.count > 1` to inspect conversations requiring multiple tool iterations, tracking the timing between `model.request` and `model.response`.

### Persona C: Store Operator / Support Specialist
- **Goal:** Correlate customer chat session drop-offs with technical errors.
- **Use Case:** Searches traces by `agent.session_id` or `agent.user_id` to inspect the exact progression of events during an abandoned customer session.

---

## 3. Business Acceptance Criteria (Given / When / Then)

### Scenario 1: Standard Conversational Turn with Single Tool Invocation
- **Given** an authenticated user chat request requiring tool execution (e.g. "Find fast chargers").
- **When** `AssistantChatService.sendMessage` processes the request.
- **Then** an enclosing span named `agent.turn` is started and ended.
- **And** the span records the following sequence of span events:
  1. `agent.request.received`
  2. `tools.discovered`
  3. `agent.iteration.started`
  4. `model.request`
  5. `model.response`
  6. `agent.tool.call`
  7. `agent.tool.result`
  8. `agent.iteration.started`
  9. `model.request`
  10. `model.response`
  11. `agent.response.generated`
  12. `agent.completed`
- **And** downstream child spans (`gemini.generate_content` and `mcp.tool_call`) nest directly beneath `agent.turn`.

### Scenario 2: Direct Conversational Turn (Zero Tool Invocations)
- **Given** a conversational request that requires no tools (e.g. "Hello!").
- **When** `AssistantChatService.sendMessage` executes a single iteration where the model returns final text immediately.
- **Then** the span records:
  1. `agent.request.received`
  2. `tools.discovered`
  3. `agent.iteration.started`
  4. `model.request`
  5. `model.response`
  6. `agent.response.generated`
  7. `agent.completed`
- **And** zero `agent.tool.call` or `agent.tool.result` events are emitted.

### Scenario 3: Multi-Tool Parallel Call Within Single Iteration
- **Given** a model response returning two tool calls in a single turn.
- **When** the agent processes the tool calls.
- **Then** an `agent.tool.call` and `agent.tool.result` event pair is emitted for each executed tool call in sequence.

### Scenario 4: Max Iterations Reached Safeguard
- **Given** an agent loop that reaches `MAX_TOOL_ITERATIONS` (5).
- **When** the loop terminates after 5 iterations.
- **Then** `agent.iteration.started` is emitted exactly 5 times.
- **And** the turn completes cleanly with `agent.response.generated` and `agent.completed`.
- **And** the span is tagged with `agent.iterations.count="5"`.

### Scenario 5: Error and Exception Recording on Enclosing Span
- **Given** an unhandled exception occurs (e.g. blank message, model communication failure, or network abort).
- **When** the exception is thrown out of or caught within `sendMessage`.
- **Then** the `agent.turn` span is tagged with `error="true"`.
- **And** the exception is recorded on the span via `span.error(e)`.
- **And** the span terminates cleanly in `finally { span.end(); }`.

### Scenario 6: Graceful Operation When Tracer Is Absent
- **Given** `AssistantChatService` is initialized without a `Tracer` bean (e.g. in standalone unit tests).
- **When** `sendMessage` is invoked.
- **Then** chat processing proceeds normally with zero `NullPointerException` or performance degradation.

### Scenario 7: Contextual Session and User Attribution Tags
- **Given** a request with `sessionId="sess-456"` and authenticated `userId="user-789"`.
- **When** the `agent.turn` span is created.
- **Then** the span contains attributes: `agent.name="assistant-chat"`, `agent.framework="polaris-assistant"`, `agent.session_id="sess-456"`, `agent.user_id="user-789"`.
