# ADR-0016: GenAI Observability, OTel Semantic Conventions, and MCP Tool Audit Standards

* **Status:** Accepted; §4.2 model span contract amended by [ADR-0020](0020-grafana-ai-observability-genai-telemetry.md) (2026-10-02)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering, AI Agent Platform Lead, SRE Lead, SecOps & AI Governance Lead
* **Date:** 2026-09-17
* **Technical Story:** Aligning Polaris Assistant foundation model reasoning telemetry with OpenTelemetry GenAI Semantic Conventions (v1.27+), establishing an audit trail on MCP tool execution spans (`mcp.tool_call`), enforcing automated PII/PCI redaction via `ArgumentSanitizer`, implementing sampled structured thought signature logging, and enriching lifecycle span event payloads.
* **Product Reference:** [PRD-006](../../business/prds/PRD-006-genai-observability-and-tool-audit.md)
* **Work Order References:** [WO-016](../work-orders/WO-016-model-spans-genai-semantic-conventions.md), [WO-017](../work-orders/WO-017-tool-execution-span-enrichment-and-audit.md), [WO-018](../work-orders/WO-018-react-orchestrator-wiring-and-log-sampling.md)

---

## 1. Context and Problem Statement

Polaris Assistant (`apps/polaris-assistant`) operates as an autonomous conversational microservice ([ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md)). Under [ADR-0013](0013-agent-turn-span-and-lifecycle-events.md), [ADR-0014](0014-agent-decision-events-and-observability-schema.md), and [ADR-0015](0015-intent-management-and-policy-engine-architecture.md), conversational turns execute an autonomous ReAct loop with intent resolution, dynamic tool set narrowing, defensive tool validation, and OAuth2 scope enforcement.

However, existing telemetry across model reasoning and tool execution leaves critical gaps:
1. **Model Span Semantic Drift:** Model reasoning spans (`gemini.generate_content`) use vendor-specific and legacy attributes (`gen_ai.operation.name="generateContent"`, `gen_ai.usage.prompt_tokens`) instead of the standardized **OpenTelemetry GenAI Semantic Conventions (v1.27+)** (`gen_ai.operation.name="chat"`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.usage.total_tokens`, `gen_ai.response.finish_reason = "tool_calls" | "stop"`). This prevents out-of-the-box ingestion by modern APMs and Grafana Tempo GenAI monitoring dashboards.
2. **Missing Reasoning Context:** The model span executes inside an iterative loop, yet it has no tags recording `agent.iteration`, `agent.intent_id`, `agent.intent_confidence`, or `agent.tools_offered_count`. When debugging loop convergence or prompt degradation, operators cannot determine which loop iteration or intent gate produced the model call.
3. **Tool Execution Span Auditability Void:** The `mcp.tool_call` span only captures HTTP client transport latency. It fails to answer the critical governance question: *"Why was this tool permitted to run?"* It lacks the tool identity (`gen_ai.tool.name`), iteration number (`agent.iteration`), defensive registry validation outcome (`agent.tool.validation_result`), policy authorization outcome (`agent.policy.decision`, `agent.policy.reason`, `agent.policy.required_scope`), and execution footprint (`agent.tool.result_size_bytes`).
4. **Data Privacy & Security Leakage Risks:** Tool arguments (`toolCall.arguments()`) can contain customer names, order IDs, shipping addresses, or payment details. If attached unredacted to distributed traces, this violates GDPR and PCI-DSS compliance because trace backends have broad internal access and indefinite retention. Furthermore, raw prompts, completions, and raw Gemini `thoughtSignature` blobs must never be added as span attributes due to span tag size limits and intellectual property leakage.
5. **Reasoning Auditability Dilemma:** Completely discarding thought signatures prevents engineers from diagnosing reasoning divergence or tool hallucination. A dedicated, access-controlled structured log stream with sampling (e.g. 1% of turns) allows post-hoc debugging without trace tag bloat.
6. **Bare Lifecycle Span Events:** On the `agent.turn` span, `agent.tool.call` and `agent.tool.result` are bare markers without payloads, forcing manual cross-referencing to identify which tool executed in a flame graph.

How should Polaris Assistant architect model reasoning and tool execution observability to achieve standard OTel compliance, complete auditability, and zero data leakage while preserving non-blocking performance and backwards compatibility?

---

## 2. Decision Drivers

* **Simplicity via Standardization ([AGENTS.md Principle 1](../../../AGENTS.md)):** Align directly with OpenTelemetry GenAI Semantic Conventions v1.27+ (`gen_ai.*`), W3C Trace Context, and RFC 7807 problem details.
* **Context Containment & Minimal Bounded Change ([AGENTS.md Principle 2](../../../AGENTS.md)):** Restrict all changes strictly to `apps/polaris-assistant` without modifying Polaris Core, core database schemas, or external REST API contracts.
* **Observability by Default & Anti-Monolith Modularity ([AGENTS.md Principle 3](../../../AGENTS.md)):** Provide comprehensive tracing and correlated structured logging while separating concerns across modular components (`ArgumentSanitizer`, `ModelRequestContext`, `PolicyToolManager`).
* **Zero PII/PCI Trace Leakage:** Enforce automated redaction of sensitive keys and truncation of payloads before attaching them as trace tags.
* **Fail-Safe Telemetry:** Telemetry operations must never cause conversational turn failures or degrade user experience.
* **100% Backwards Compatibility:** Preserve all existing constructors, interfaces, and public methods across `apps/polaris-assistant`.

---

## 3. Considered Options

### Option 1: Ad-hoc Span Tagging and Manual String Logging in Service Loop
Inject inline span tags and ad-hoc log statements directly inside `AssistantChatService.executeTurn()`.
* **Cons:** Violates Single Responsibility ([AGENTS.md Principle 3.3](../../../AGENTS.md)); bloats the orchestration service; error-prone with inconsistent tag naming and high risk of leaking PII.

### Option 2: Synchronous Relational Audit Database Table
Create a dedicated PostgreSQL database table (`ai_tool_audit_logs`) and persist every tool call, argument, and decision synchronously.
* **Cons:** Introduces synchronous database I/O latency on every conversational turn; bloats the relational database; violates Polaris Assistant's decoupled, stateless microservice architecture ([ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md)).

### Option 3: Standardized OTel GenAI Model Spans, Enriched Tool Spans via `PolicyToolManager` with `ArgumentSanitizer`, Sampled Thought Logging, and Enriched Span Events (Selected)
- Update `GeminiAiModelClient` to record standard OTel GenAI v1.27+ attributes and accept an optional `ModelRequestContext`.
- Implement `ArgumentSanitizer` to recursively mask sensitive PII/PCI keys and produce safe argument summaries.
- Enhance `ExternalMcpHub.handleToolCalls` to attach governance audit tags (`gen_ai.tool.name`, `agent.iteration`, `agent.tool.validation_result`, `agent.policy.decision`, `agent.policy.reason`, `agent.policy.required_scope`, `agent.tool.result_size_bytes`, `mcp.tool.args_summary`) directly to the active span.
- Emit opaque thought signatures to a structured SLF4J JSON logger with 1% sampling or debug level.
- Promote lifecycle span events on `agent.turn` to `agent.tool.call: <tool_name>` and `agent.tool.result: <tool_name>`.

---

## 4. Decision Outcome & Technical Specification

**Chosen Option:** Option 3.

### 4.1. Distributed Span Hierarchy

```
agent.turn (root AssistantChatService span)
  │
  ├── gemini.generate_content <model> (iteration 1 reasoning)
  │     [attributes: gen_ai.system="gemini", gen_ai.request.model="gemini-2.5-flash",
  │                  gen_ai.operation.name="chat", gen_ai.usage.input_tokens=...,
  │                  gen_ai.usage.output_tokens=..., gen_ai.usage.total_tokens=...,
  │                  gen_ai.response.finish_reason="tool_calls",
  │                  agent.iteration=1, agent.intent_id="catalog.product.search",
  │                  agent.intent_confidence=0.95, agent.tools_offered_count=2]
  │     [events: model.request, model.response]
  │
  ├── mcp.tool_call search_available_products (iteration 1 tool execution)
  │     [attributes: gen_ai.tool.name="search_available_products",
  │                  agent.iteration=1, agent.tool.validation_result="VALID",
  │                  agent.policy.decision="ALLOW", agent.policy.reason="Scope granted",
  │                  agent.policy.required_scope="catalog.read",
  │                  agent.tool.result_size_bytes=1420,
  │                  mcp.tool.args_summary="{\"query\":\"charger\"}",
  │                  mcp.provider="polaris-core", mcp.operation="tools/call"]
  │
  └── gemini.generate_content <model> (iteration 2 reasoning)
        [attributes: gen_ai.operation.name="chat",
                     gen_ai.response.finish_reason="stop",
                     agent.iteration=2, agent.intent_id="catalog.product.search",
                     agent.intent_confidence=0.95, agent.tools_offered_count=2]
```

### 4.2. Exact Span Attribute Contracts

#### A. Model Reasoning Span (`gemini.generate_content <model>`)

| Span Attribute | Type | Example Value | Description |
|---|---|---|---|
| `gen_ai.system` | String | `"gemini"` | Foundation model vendor |
| `gen_ai.request.model` | String | `"gemini-2.5-flash"` | Upstream model identifier |
| `gen_ai.operation.name` | String | `"chat"` | Standard OTel operation name (updated from `"generateContent"`) |
| `gen_ai.usage.input_tokens` | String/Int | `"184"` | Prompt tokens consumed (`promptTokenCount`) |
| `gen_ai.usage.output_tokens` | String/Int | `"42"` | Completion tokens generated (`candidatesTokenCount`) |
| `gen_ai.usage.total_tokens` | String/Int | `"226"` | Total tokens consumed (`totalTokenCount`) |
| `gen_ai.response.finish_reason` | String | `"tool_calls"` or `"stop"` | Reason inference finished: tool invocation vs text completion |
| `agent.iteration` | String/Int | `"1"` | 1-based ReAct loop iteration count |
| `agent.intent_id` | String | `"catalog.product.search"` | Resolved taxonomy intent ID |
| `agent.intent_confidence` | String | `"0.95"` | Intent classifier confidence score |
| `agent.tools_offered_count` | String/Int | `"2"` | Number of tools offered in this reasoning turn |

> [!CAUTION]
> **Strict Omission Rule:** Under no circumstances shall raw user prompt text, system prompt text, full completion text, or raw Gemini thought signatures be attached as span attributes or span event tags.

#### B. Tool Execution Span (`mcp.tool_call <tool_name>`)

| Span Attribute | Type | Example Value | Description |
|---|---|---|---|
| `gen_ai.tool.name` | String | `"search_available_products"` | Standard OTel tool name |
| `agent.iteration` | String/Int | `"1"` | ReAct loop iteration count |
| `agent.tool.validation_result` | String | `"VALID"` or `"TOOL_MISMATCH"` | Result from `IntentToolRegistry.isValid(...)` |
| `agent.policy.decision` | String | `"ALLOW"` or `"DENY"` | Result from `PolicyEngine.authorize(...)` |
| `agent.policy.reason` | String | `"Scope granted"` | Policy authorization rationale |
| `agent.policy.required_scope` | String | `"catalog.read"` | Scope required to invoke tool |
| `agent.tool.result_size_bytes` | String/Int | `"1420"` | Total byte length of tool result payload |
| `mcp.tool.args_summary` | String | `"{\"query\":\"charger\"}"` | Sanitized, PII-redacted argument JSON (max 256 chars) |
| `error` | String | `"true"` | Attached if `CallToolResult.isError()` or exception thrown |

#### C. Enriched Span Events on `agent.turn`

| Span Event Name | Description |
|---|---|
| `agent.tool.call: <tool_name>` | Timestamped event indicating start of specific tool call (e.g. `agent.tool.call: search_available_products`) |
| `agent.tool.result: <tool_name>` | Timestamped event indicating completion of specific tool call (e.g. `agent.tool.result: search_available_products`) |

### 4.3. `ArgumentSanitizer` Specification

Package: `vn.danang.polaris.assistant.observability.ArgumentSanitizer`

1. **Redacted Key Patterns (Case-Insensitive):**
   Matches any key matching or containing:
   - `password`, `token`, `secret`, `authorization`, `auth`
   - `card`, `cardnumber`, `cvv`, `pan`, `account`
   - `ssn`, `tax_id`
   - `email`, `phone`, `mobile`, `address`, `street`, `postal`, `zip`
   - `customer_id`, `user_id` (when configured or sensitive)
2. **Masking Format:** Value replaced with `"[REDACTED]"`.
3. **Recursive Traversal:** Safely recurses through nested maps and collections.
4. **Summary Truncation:** Serializes the sanitized map to JSON; if exceeding `maxLength` (default 256 chars), truncates cleanly with `...`.
5. **Null Safety:** Returns `"{}"` on null or empty arguments with zero exceptions.

### 4.4. `ModelRequestContext` Specification

Package: `vn.danang.polaris.assistant.model.ModelRequestContext`

```java
public record ModelRequestContext(
        int iteration,
        String intentId,
        double intentConfidence,
        int toolsOfferedCount
) {
    public static ModelRequestContext empty() {
        return new ModelRequestContext(1, "unknown", 1.0, 0);
    }
}
```

Integrated into `AssistantModelClient`:
```java
default ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context) {
    return generateResponse(messages, tools);
}
```

### 4.5. Thought Signature Sampling Specification

1. **Storage:** Stored in `AssistantMessage.thoughtSignature` for ReAct multi-turn loop and context replay.
2. **Trace Prohibition:** Prohibited from trace span attributes.
3. **Structured Log Stream:**
   - Logged via dedicated SLF4J logger at DEBUG level or sampled INFO level.
   - Sampling rule: `Math.abs(sessionId.hashCode()) % 100 == 0` (1% deterministic sample) or when logging level is DEBUG.
   - Structured JSON payload:
     ```json
     {
       "trace_id": "00-12345...",
       "span_id": "67890...",
       "session_id": "sess-456",
       "iteration": 1,
       "model": "gemini-2.5-flash",
       "thought_signature": "<opaque_base64_blob>"
     }
     ```

---

## 5. Boundary & Interface Stability Analysis (Dimension 2: B1 - B7)

| Criterion | Level | Justification |
|---|---|---|
| **B1: Boundary Enumeration** | Polaris Assistant Context | Strictly contained within `apps/polaris-assistant` across subpackages `model`, `observability`, `mcp`, and `service`. Core commerce contracts and schemas remain untouched. |
| **B2: Stable vs Internal** | Explicitly Delineated | **Stable Contract:** OTel span attribute keys (`gen_ai.*`, `agent.*`), span event naming syntax (`agent.tool.call: <tool>`), and structured log keys. **Internal Implementation:** `ArgumentSanitizer`, `ModelRequestContext`, `PolicyToolManager` helper methods. |
| **B3: Traceability** | Established | Governed by ADR-0016; decomposed into slice work orders WO-016, WO-017, and WO-018; traceable to PRD-006. |
| **B4: Migration & Compatibility** | 100% Additive | Non-breaking. Overloaded constructors and default interface methods ensure existing clients, tests, and callers compile and run without modification. |
| **B5: Gate Enforcement** | TV Gate Defined | Sign-off checklist explicitly enforces: (1) unit test coverage, (2) verification that no raw prompts/thought signatures exist in span tags, and (3) live trace inspection via `./gcx.sh traces`. |
| **B6: Downstream Impact** | Zero Disruption | Fully backwards compatible for HTTP clients. Standardizes attributes for Grafana Tempo, Loki, and external APMs. |
| **B7: Reversibility & Blast Radius** | Immediate / Low Risk | Telemetry decorators fail open; any exception in tracing, sanitization, or logging is caught and logged, guaranteeing that user chat interactions are never disrupted. |

---

## 6. Technical Verification & Test Plan

1. **Unit Verification (`apps/polaris-assistant`):**
   - `GeminiAiModelClientTest`: Validate OTel GenAI tags (`gen_ai.operation.name="chat"`, token counts, finish reason `"tool_calls"` vs `"stop"`, context tags, null tracer resilience, exclusion of raw prompts and thought signatures).
   - `ArgumentSanitizerTest`: Validate PII/PCI masking, nested maps, summary truncation, and edge cases.
   - `ExternalToolManagerTest`: Validate `gen_ai.tool.name` on `mcp.tool_call` span, intent tool validation, and policy enforcement.
   - `AssistantChatServiceTest`: Validate end-to-end integration, enriched span events (`agent.tool.call: <tool>`), context propagation, and thought signature log sampling.
2. **Reactor Verification:**
   - Execute `mvn clean test -pl apps/polaris-assistant`
   - Execute monorepo reactor pass: `mvn clean test`
3. **Telemetry & Trace Verification (via `polaris-dev` skill):**
   - Inspect trace hierarchy and span tags using `./gcx.sh traces query` and `./gcx.sh traces get <trace-id>`.
