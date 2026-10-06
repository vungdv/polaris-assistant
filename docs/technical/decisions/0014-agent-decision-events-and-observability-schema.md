# ADR-0014: Structured Agent Decision Events and Observability Schema

* **Status:** Superseded (by OpenTelemetry GenAI Semantic Conventions in ADR-0016; custom AgentDecisionRecorder decommissioned in alignment with Principle 1)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering, AI Agent Platform Lead
* **Date:** 2026-09-16 (Updated: 2026-09-20)
* **Technical Story:** Instrumenting `apps/polaris-assistant` with a standardized, structured Decision Event schema (`DecisionEvent`), correlated SLF4J JSON logging, and Micrometer Tracing span tags on the active `agent.turn` span via `AgentDecisionRecorder` to govern AI agent decision explainability, policy compliance, and alternative evaluation.
* **Product Reference:** [PRD-005](../../business/prds/PRD-005-agent-turn-observability-and-lifecycle-events.md)
* **Work Order Reference:** [WO-014](../work-orders/WO-014-agent-decision-events-recording.md)

---

## 1. Context and Problem Statement

Polaris Assistant operates as an autonomous microservice (`apps/polaris-assistant`) adhering to [ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md). Inbound conversational requests (`POST /api/v1/assistant/chat`) are handled by `AssistantChatService.sendMessage()`, where an autonomous ReAct loop runs up to `MAX_TOOL_ITERATIONS` (5), querying Google Gemini for inference and dispatching tools via the Model Context Protocol (`PolicyToolManager`).

Prior telemetry milestones established:
- [ADR-0011](0011-gemini-model-call-distributed-tracing.md): Child spans for Gemini model inference.
- [ADR-0012](0012-mcp-cross-service-distributed-tracing.md): Distributed tracing across MCP tool discovery and execution.
- [ADR-0013](0013-agent-turn-span-and-lifecycle-events.md): Enclosing distributed tracing span (`agent.turn`) with timestamped lifecycle milestone span events (`agent.request.received`, `tools.discovered`, `agent.iteration.started`, `model.request`, `model.response`, `agent.tool.call`, `agent.tool.result`, `agent.response.generated`, `agent.completed`).

### The Architectural Gap:
While ADR-0013 captures *when* execution transitions occur across time, it provides zero structured visibility into *why* the agent made a specific operational choice:
1. **Lack of Alternative Evaluation Tracking:** When the agent chooses a tool (e.g., `search_products`), operators cannot see which alternative tools were in the MCP registry, why they were considered, or why they were rejected.
2. **Missing Decision Confidence & Policy Constraints:** Telemetry lacks visibility into model confidence scores and active governance policy rules (e.g. `MAX_TOOL_ITERATIONS=5`, budget constraints, safety boundaries).
3. **Unstructured Decision Logs:** Troubleshooting conversational misroutes currently requires ad-hoc textual log scraping rather than machine-parsable JSON payloads correlated with distributed tracing IDs.
4. **Trace Tagging Deficit:** In Grafana Tempo, engineers cannot query or filter traces by chosen decision action (`decision.action`), intent (`decision.intent`), outcome status (`decision.outcome.status`), or latency.

How should Polaris architect agent decision observability to provide complete decision auditability, alternative evaluation, and policy governance while maintaining non-blocking performance and backwards compatibility?

---

## 2. Decision Drivers

* **Observability by Default ([AGENTS.md Principle 3.1](../../../AGENTS.md)):** High-cardinality, structured decision telemetry correlated with W3C `trace_id` and `span_id`.
* **Agent Explainability & AI Governance:** Record evaluated alternatives, rejection rationales, confidence scores, and policy constraints for every autonomous choice.
* **Correlated Multi-Modal Telemetry:** Dual-sink emission—structured JSON logs for Grafana Loki indexing and semantic span tags for Grafana Tempo filtering.
* **Single Responsibility & Anti-Monolith Modularity ([AGENTS.md Principle 3.3](../../../AGENTS.md)):** Encapsulate all decision event formatting, trace correlation, and logging inside a dedicated `AgentDecisionRecorder` component rather than polluting `AssistantChatService` orchestration logic.
* **Fail-Safe & Non-Blocking Resilience:** Telemetry serialization or null tracers must never degrade or abort user chat interactions.
* **Interface Stability ([Evaluation Criteria B1-B7](../../../.agents/agents/arch-agent.md)):** 100% backwards-compatible with existing `AssistantChatService` constructors and zero breaking changes to external REST APIs.

---

## 3. Considered Options

### Option 1: Dedicated `AgentDecisionRecorder` with Structured Log Events and Active Span Tags (Selected)
Introduce an immutable record hierarchy (`DecisionEvent`, `EvaluatedAlternative`, `Outcome`) in package `vn.danang.polaris.assistant.observability`, managed by an injected Spring component `AgentDecisionRecorder`.
- Extracts active `trace_id` and `span_id` from Micrometer `Tracer`.
- Emits structured JSON log lines tagged with execution state: `Agent decision [STARTING]`, `Agent decision [COMPLETED]`, `Agent decision [FAILED]`, `Agent decision [ERROR]`.
- Attaches standardized `decision.*` tags to the active `agent.turn` span.

* **Pros:**
  - Complete correlation between Loki logs and Tempo traces.
  - Zero database bloat: Leverages existing logging and tracing infrastructure.
  - Clean separation of concerns: `AssistantChatService` delegates decision recording to `AgentDecisionRecorder`.
  - Seamless fallback when `Tracer` is null (e.g., in unit tests).
* **Cons:** Requires adding the `observability` package and injecting the recorder into `AssistantChatService`.

### Option 2: Database Persistence of Decision Records (PostgreSQL Table)
Create a `decision_events` table and insert a row for every agent decision.

* **Pros:** Relational SQL querying of past decisions.
* **Cons:** Introduces write amplification, synchronous DB latency, and database table bloat on every conversational turn; violates Polaris Assistant's decoupled, stateless microservice architecture ([ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md)).

### Option 3: Distributed Child Spans for Every Alternative
Create an individual tracing span for each evaluated alternative tool.

* **Pros:** Visible in trace flamegraphs.
* **Cons:** Triggers severe span explosion (dozens of micro-spans per turn) and overwhelms OTLP collectors and Tempo storage.

---

## 4. Decision Outcome & Detailed Technical Specification

**Chosen Option:** Option 1: Dedicated `AgentDecisionRecorder` with Structured Log Events and Active Span Tags.

### 4.1. Domain Schema Specification

In package `vn.danang.polaris.assistant.observability`:

```java
public record DecisionEvent(
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("span_id") String spanId,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("intent") String intent,
        @JsonProperty("evaluated_alternatives") List<EvaluatedAlternative> evaluatedAlternatives,
        @JsonProperty("selected_action") String selectedAction,
        @JsonProperty("confidence") Double confidence,
        @JsonProperty("policy_constraint") String policyConstraint,
        @JsonProperty("outcome") @JsonInclude(JsonInclude.Include.NON_NULL) Outcome outcome
) {
    public DecisionEvent withOutcome(Outcome outcome) {
        return new DecisionEvent(
                this.traceId,
                this.spanId,
                this.agentId,
                this.sessionId,
                this.timestamp,
                this.intent,
                this.evaluatedAlternatives,
                this.selectedAction,
                this.confidence,
                this.policyConstraint,
                outcome
        );
    }
}

public record EvaluatedAlternative(
        @JsonProperty("action") String action,
        @JsonProperty("reason_considered") String reasonConsidered,
        @JsonProperty("selected") boolean selected,
        @JsonProperty("reason_rejected") String reasonRejected
) {}

public record Outcome(
        @JsonProperty("status") String status,
        @JsonProperty("detail") String detail,
        @JsonProperty("latency_ms") long latencyMs
) {}
```

### 4.2. Log Line Emission Format

Logs are emitted using SLF4J at INFO level with snake_case serialized JSON:
* `Agent decision [STARTING]: {"trace_id":"...","span_id":"...","agent_id":"polaris-assistant","session_id":"...","timestamp":"...","intent":"...","selected_action":"...","confidence":1.0,"policy_constraint":"MAX_TOOL_ITERATIONS=5","evaluated_alternatives":[...]}`
* `Agent decision [COMPLETED]: {...,"outcome":{"status":"SUCCESS","detail":"...","latency_ms":12}}`
* `Agent decision [FAILED]: {...,"outcome":{"status":"FAILURE","detail":"<exception message>","latency_ms":15}}`
* `Agent decision [ERROR]: {...,"outcome":{"status":"FAILURE","detail":"<tool error detail>","latency_ms":20}}`

### 4.3. Active Span Tagging Contract

When an active span (`agent.turn`) is present, `AgentDecisionRecorder` records the following semantic attributes upon decision completion or failure:
- `decision.action`: The selected action (e.g., `"search_products"` or `"reply_to_user"`).
- `decision.intent`: The user intent / query triggering the decision.
- `decision.confidence`: Model confidence score string (e.g., `"1.0"`).
- `decision.policy`: Applied policy constraint (e.g., `"MAX_TOOL_ITERATIONS=5"`).
- `decision.outcome.status`: `"SUCCESS"` or `"FAILURE"`.
- `decision.outcome.detail`: Outcome summary message.
- `decision.outcome.latency_ms`: Duration in milliseconds as a string.

### 4.4. `AgentDecisionRecorder` Component Specification

`AgentDecisionRecorder` provides primary recording methods:
1. `recordToolExecution(...)`: Handles STARTING, tool dispatch, error/failure classification, COMPLETED/ERROR/FAILED logging, and span tag attribution.
2. `recordDirectResponseDecision(...)`: Handles final answer synthesis when no further tools are called, marking candidate tools as rejected and `reply_to_user` as selected.
3. `buildEvaluatedAlternatives(...)`: Helper constructing the list of `EvaluatedAlternative` entries from available MCP tools.

---

## 5. Boundary & Interface Stability Analysis

| Criterion | Level | Justification |
|---|---|---|
| **B1: Boundary Enumeration** | Polaris Assistant Context | Enclosed within `apps/polaris-assistant`. Zero coupling to core commerce database schemas. |
| **B2: Stable vs Internal** | Explicitly Delineated | Stable Contract: `decision.*` span tags and `Agent decision [*]` JSON log format. Internal: `AgentDecisionRecorder`, Java records, and private helper methods. |
| **B3: Traceability** | Established | Changes are governed by ADR-0014 and tracked via WO-014. |
| **B4: Migration & Compatibility** | 100% Additive | Non-breaking. Constructors in `AssistantChatService` maintain overloaded binary compatibility. Zero changes to REST payloads. |
| **B5: Gate Enforcement** | TV Gate Defined | Sign-off enforces unit tests passing with Mockito Tracer and full monorepo reactor pass. |
| **B6: Downstream Impact** | Additive Observability | Enables Grafana Loki LogQL extraction and Tempo TraceQL filtering by `decision.action`. Zero client disruption. |
| **B7: Reversibility** | Immediate / Low Risk | Feature is stateless and fail-safe. If logging or tracing fails, core chat execution continues unaffected. |

---

## 6. Verification Plan

1. **Unit Testing (`apps/polaris-assistant`):**
   - `DecisionEventTest`: Validate snake_case JSON serialization, non-null outcome handling, and span attribute recording.
   - `AgentDecisionRecorderTest`:
     * `recordDirectResponseDecision_recordsSuccessEvent`
     * `recordToolExecution_success_returnsResult`
     * `recordToolExecution_withException_recordsFailureAndRethrows`
     * `recordToolExecution_withErrorResult_recordsFailureOutcome`
     * `buildEvaluatedAlternatives_forDirectResponse_marksToolsRejected`
     * `buildEvaluatedAlternatives_marksSelectedAndRejectsAlternatives`
   - `AssistantChatServiceTest`: Validate end-to-end integration and backwards compatibility of existing tests without Tracer.
2. **Reactor Verification:**
   - Execute `mvn clean test -pl apps/polaris-assistant`
   - Execute full monorepo build: `mvn clean test`
