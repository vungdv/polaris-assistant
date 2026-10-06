# Product Requirements Document: PRD-006
## GenAI Observability, OTel Semantic Conventions, and MCP Tool Audit Standards

- **Context/Domain:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Target Personas:** Site Reliability Engineers (SRE), AI Agent Platform Developers, AI Safety & SecOps Compliance Officers
- **Status:** Approved (Ready for Implementation)
- **Document Owner:** Polaris Fleet Architect & Product Lead
- **Last Updated:** 2026-09-17
- **Work Order References:** [WO-016](../../technical/work-orders/WO-016-model-spans-genai-semantic-conventions.md), [WO-017](../../technical/work-orders/WO-017-tool-execution-span-enrichment-and-audit.md), [WO-018](../../technical/work-orders/WO-018-react-orchestrator-wiring-and-log-sampling.md)
- **ADR Reference:** [ADR-0016](../../technical/decisions/0016-genai-observability-and-mcp-audit-standards.md)

---

## 1. Business Problem & Engineering Rationale (WHAT & WHY)

In Polaris conversational commerce, the AI Assistant (`apps/polaris-assistant`) executes an autonomous ReAct loop (`AssistantChatService`), alternating between foundation model reasoning (Google Gemini) and tool execution over the Model Context Protocol (`PolicyToolManager`). 

Prior architecture milestones established:
- **PRD-004 / ADR-0011:** Gemini model distributed tracing spans (`gemini.generate_content`).
- **ADR-0012:** Distributed trace context propagation across external MCP tool calls (`mcp.tool_call`).
- **PRD-005 / ADR-0013:** Turn-level enclosing span (`agent.turn`) with lifecycle milestone span events.
- **ADR-0014:** *(Superseded)* Structured agent decision schema (`DecisionEvent`).
- **ADR-0015:** Intent taxonomy, registry tool filtering, and OAuth2 scope policy enforcement.

### The Architectural & Governance Gap:

Despite these advances, production telemetry currently suffers from critical blind spots, standard incompatibilities, and security exposures:

1. **Non-Standard GenAI Semantic Conventions:** 
   The model reasoning span (`gemini.generate_content`) currently emits legacy and vendor-specific tags (`gen_ai.operation.name="generateContent"`, `gemini.tools.count`, `gen_ai.usage.prompt_tokens`). Standard observability tooling (Grafana Tempo GenAI dashboards, Jaeger, Honeycomb, Datadog) requires **OpenTelemetry GenAI Semantic Conventions (v1.27+)**:
   - `gen_ai.operation.name = "chat"`
   - `gen_ai.usage.input_tokens`
   - `gen_ai.usage.output_tokens`
   - `gen_ai.usage.total_tokens`
   - `gen_ai.response.finish_reason = "tool_calls" | "stop"`
   Without these standardized metrics, operators cannot monitor token consumption, cost attribution, or finish rationale across conversational turns without bespoke scrapers.

2. **Decoupled Model Reasoning Context:**
   Model calls occur inside a multi-iteration ReAct loop, yet the reasoning span carries no context regarding *which* loop iteration is executing or *why* the model was invoked with a specific subset of tools. When debugging loop convergence or prompt degradation, operators cannot correlate the model call with `agent.iteration`, `agent.intent_id`, `agent.intent_confidence`, or `agent.tools_offered_count`.

3. **Tool Execution Span Auditability Deficit:**
   The `mcp.tool_call` span currently only wraps the external HTTP hop with basic transport attributes (`mcp.provider="polaris-core"`, `mcp.operation="tools/call"`). It fails to record *why* the tool was allowed to run:
   - Tool identity (`gen_ai.tool.name`)
   - ReAct loop iteration (`agent.iteration`)
   - Intent tool registry defensive validation result (`agent.tool.validation_result`)
   - Policy engine authorization outcome (`agent.policy.decision`, `agent.policy.reason`, `agent.policy.required_scope`)
   - Execution payload footprint (`agent.tool.result_size_bytes`)
   - Failure status (`error = "true"`)

4. **Severe Data Leakage & Security Blast Radius in Traces:**
   Traces have wide engineering visibility and long retention periods. Unsanitized tool arguments (`toolCall.arguments()`) can leak sensitive PII/PCI data (customer IDs, shipping addresses, phone numbers, payment details). Furthermore, raw prompts, completions, and Gemini internal reasoning blobs (`thoughtSignature`) must **never** be injected into span attributes due to span tag size limits, prompt leakage, and security policy.

5. **Reasoning Auditability vs. Trace Bloat:**
   While thought signatures must be excluded from span tags, engineers still need reasoning context to diagnose hallucination or tool selection errors. A dedicated, access-controlled structured log stream with sampling (e.g., 1% of turns or debug level) enables post-hoc inspection without trace tag bloat.

6. **Bare Lifecycle Span Events:**
   Current span events (`agent.tool.call` and `agent.tool.result`) are bare markers without payloads, forcing engineers to manually cross-reference log lines to determine which tool executed in a flame graph. Enriching span event payloads to `agent.tool.call: <tool_name>` and `agent.tool.result: <tool_name>` provides immediate flamegraph clarity.

### Value Delivered:
1. **Industry-Standard GenAI Telemetry:** Out-of-the-box visibility into token usage, cost, and latency on Grafana Tempo GenAI dashboards.
2. **End-to-End Governance & Audit Trail:** Complete observability into why each tool was offered, validated, authorized, and executed.
3. **Guaranteed Data Privacy & Compliance:** Zero raw prompts or thought signatures in traces, with automated PII/PCI argument sanitization (`ArgumentSanitizer`).
4. **Walkable Flamegraphs:** Immediate identification of executed tools in trace visualizations via enriched span event payloads.

---

## 2. Target Personas & Use Cases

### Persona A: Site Reliability Engineer (SRE) / Observability Engineer
- **Goal:** Monitor GenAI cost, token velocity, error rates, and latency breakdowns.
- **Use Case:** Uses Grafana Tempo and Prometheus dashboards filtering by `gen_ai.usage.total_tokens` and `gen_ai.response.finish_reason` to detect abnormal token spikes, runaway loops, or slow upstream model calls.

### Persona B: AI Safety, Compliance & SecOps Auditor
- **Goal:** Verify that autonomous actions conform to security boundaries and privacy regulations (GDPR/PCI-DSS).
- **Use Case:** Audits `mcp.tool_call` spans to ensure every mutating action had `agent.policy.decision="ALLOW"` under valid `agent.policy.required_scope`, and confirms that no raw credit card details or unredacted PII exist in trace tags.

### Persona C: AI Agent Platform Engineer / Prompt Developer
- **Goal:** Diagnose agent reasoning stalls, loop divergences, and tool calling failures.
- **Use Case:** Inspects a trace with `agent.iteration=3`, checks why `agent.tool.validation_result="TOOL_MISMATCH"` occurred, and cross-references the correlated sampled log stream to inspect the Gemini thought signature.

---

## 3. Business Acceptance Criteria (Given / When / Then)

### Scenario 1: Model Reasoning Span Conforms to OTel GenAI Semantic Conventions (v1.27+)
- **Given** an authenticated chat request requiring model inference.
- **When** `GeminiAiModelClient` dispatches a `generateContent` request to the upstream Gemini API.
- **Then** the child span `gemini.generate_content <model>` records:
  * `gen_ai.system = "gemini"`
  * `gen_ai.request.model = <model_name>` (e.g., `"gemini-2.5-flash"`)
  * `gen_ai.operation.name = "chat"`
  * `gen_ai.usage.input_tokens = <promptTokenCount>`
  * `gen_ai.usage.output_tokens = <candidatesTokenCount>`
  * `gen_ai.usage.total_tokens = <totalTokenCount>`
- **And** when the model returns one or more function calls:
  * `gen_ai.response.finish_reason = "tool_calls"`
- **And** when the model returns a final textual answer without tool calls:
  * `gen_ai.response.finish_reason = "stop"`

### Scenario 2: Model Reasoning Context Attribution
- **Given** an ongoing ReAct loop execution at iteration `N` (e.g. 1) with resolved intent `catalog.product.search` (confidence 0.95) and 2 filtered tools offered.
- **When** `AssistantChatService` invokes `modelClient.generateResponse(...)` with `ModelRequestContext`.
- **Then** the model reasoning span is enriched with:
  * `agent.iteration = "1"`
  * `agent.intent_id = "catalog.product.search"`
  * `agent.intent_confidence = "0.95"`
  * `agent.tools_offered_count = "2"`

### Scenario 3: Strict Exclusion of Raw Prompts, Completions, and Thought Signatures from Traces
- **Given** any model inference request or response containing user prompt text, system prompts, generated text, or `thoughtSignature`.
- **When** the model reasoning span or enclosing agent turn span is recorded.
- **Then** under no circumstances shall raw user prompt text, system prompt text, full completion text, or raw thought signatures be added as span attributes or span event tags.

### Scenario 4: Enriched Tool Execution Spans (`mcp.tool_call`)
- **Given** a proposed tool execution for `search_available_products` at iteration 1.
- **When** `ExternalMcpHub.handleToolCalls` executes the tool via `ExternalMcpHub.executeTool`.
- **Then** the `mcp.tool_call search_available_products` span records:
  * `gen_ai.tool.name = "search_available_products"`
  * `agent.iteration = "1"`
  * `agent.tool.validation_result = "VALID"`
  * `agent.policy.decision = "ALLOW"`
  * `agent.policy.reason = "Scope granted"`
  * `agent.policy.required_scope = "catalog.read"`
  * `agent.tool.result_size_bytes = "<byte_length>"`
- **And** if the tool execution returns an error result (`CallToolResult.isError() == true`) or throws an exception:
  * `error = "true"`

### Scenario 5: PII/PCI Redaction of Tool Arguments via `ArgumentSanitizer`
- **Given** a tool call with arguments containing sensitive fields (e.g., `{"customer_id": "cust-123", "cardNumber": "4111222233334444", "cvv": "123", "address": "123 Main St", "query": "wireless charger"}`).
- **When** `ArgumentSanitizer.sanitizeToSummary(arguments, 256)` processes the payload for span tagging.
- **Then** sensitive keys (`cardNumber`, `cvv`, `address`, etc.) are masked as `"[REDACTED]"`.
- **And** the sanitized argument summary is attached as `mcp.tool.args_summary` with length strictly bounded to at most 256 characters.

### Scenario 6: Correlated Structured Log Stream with Sampling for Thought Signatures
- **Given** a model response containing an opaque Gemini `thoughtSignature`.
- **When** the agent processes the model response.
- **Then** the thought signature is logged to a structured SLF4J log stream at DEBUG level or sampled INFO level (1% deterministic sample based on session hash).
- **And** the log entry includes `trace_id`, `span_id`, `session_id`, `iteration`, and the `thought_signature`.

### Scenario 7: Enriched Span Event Payloads
- **Given** an agent turn executing one or more tools.
- **When** the ReAct loop transitions into and out of tool execution.
- **Then** the enclosing `agent.turn` span emits:
  * `agent.tool.call: <tool_name>` (e.g. `agent.tool.call: search_available_products`)
  * `agent.tool.result: <tool_name>` (e.g. `agent.tool.result: search_available_products`)

### Scenario 8: Non-Interfering Graceful Degradation
- **Given** an environment where the OpenTelemetry `Tracer` bean is absent (e.g. lightweight unit tests) or where argument serialization fails.
- **When** chat processing, model calls, or tool executions occur.
- **Then** conversational processing executes without throwing `NullPointerException`, failing open and preserving full conversational functionality.
