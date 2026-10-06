# Slice Work Order: WO-018
## Title: ReAct Orchestrator Wiring, Span Event Payloads, and Sampled Thought Signature Logging

- **Target Modules:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0016](../decisions/0016-genai-observability-and-mcp-audit-standards.md)
- **Product Reference:** [PRD-006](../../business/prds/PRD-006-genai-observability-and-tool-audit.md)
- **Bounded Contexts:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Status:** COMPLETED

---

## 1. Objective & Scope

Wire foundation model context propagation, enriched tool execution auditing, lifecycle span event payloads, and sampled thought signature logging into `AssistantChatService`:

1. Pass `ModelRequestContext(iterations, intentId, confidence, filteredTools.size())` to `AssistantModelClient.generateResponse` on every ReAct loop iteration.
2. Enrich lifecycle span events on `agent.turn` from bare event markers to payload-attributed events:
   - `agent.tool.call: <tool_name>`
   - `agent.tool.result: <tool_name>`
3. Wire tool execution through `ExternalMcpHub.handleToolCalls` passing iteration, validation status, policy decision, required scope, and tool arguments directly.
4. Implement correlated structured logging for Gemini `thoughtSignature` blobs with 1% sampling (or debug level) to enable reasoning evaluation without trace tag bloat.
5. Guarantee zero data leakage (no raw prompts or thought signatures in span tags).
6. Verify end-to-end telemetry and reactor test pass (`mvn clean test`, `./gcx.sh traces`).

**Constraint Checklist:**
- [x] All existing `AssistantChatService` constructors and public API contracts remain backwards-compatible.
- [x] Enriched span event syntax: `agent.tool.call: <tool_name>` and `agent.tool.result: <tool_name>`.
- [x] Thought signatures strictly excluded from span attributes; logged only to access-controlled structured logs with sampling.
- [x] Fail-safe behavior against null `Tracer` beans or unconfigured telemetry.
- [x] 100% unit test and monorepo reactor pass.

---

## 2. Detailed Technical Tasks

### Task 1: Wire `ModelRequestContext` in `AssistantChatService`
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`

Inside `executeTurn` ReAct while-loop:
```java
ModelRequestContext context = new ModelRequestContext(
        iterations,
        intentId,
        confidence,
        filteredTools.size()
);
recordEvent(span, "model.request");
ModelResponse modelResponse = modelClient.generateResponse(new ArrayList<>(history), filteredTools, context);
recordEvent(span, "model.response");
```

### Task 2: Enrich Lifecycle Span Event Payloads
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`

Replace bare tool event calls:
```java
// Prior:
// recordEvent(span, "agent.tool.call");
// recordEvent(span, "agent.tool.result");

// Target:
recordEvent(span, "agent.tool.call: " + toolCall.name());
CallToolResult toolResult = decisionRecorder.recordToolExecution(
        sessionId,
        intentId,
        confidence,
        iterations,
        toolCall.name(),
        isValidTool,
        policyDecision.allowed() ? "ALLOW" : "DENY",
        policyDecision.reason(),
        requiredScope,
        toolCall.arguments(),
        filteredTools,
        span,
        () -> toolManager.executeTool(toolCall.name(), toolCall.arguments())
);
String resultText = extractToolResultText(toolResult);
recordEvent(span, "agent.tool.result: " + toolCall.name());
```

### Task 3: Implement Thought Signature Log Sampling
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`

1. Add helper method `logThoughtSignatureSampling`:
```java
private void logThoughtSignatureSampling(String sessionId, int iteration, String thoughtSignature, @Nullable Span span) {
    if (thoughtSignature == null || thoughtSignature.isBlank()) {
        return;
    }
    // 1% deterministic sampling based on sessionId hash, or always when DEBUG enabled
    boolean isSampled = log.isDebugEnabled() || (Math.abs(sessionId.hashCode()) % 100 == 0);
    if (!isSampled) {
        return;
    }

    String traceId = "00000000000000000000000000000000";
    String spanId = "0000000000000000";
    if (span != null && span.context() != null) {
        if (span.context().traceId() != null) traceId = span.context().traceId();
        if (span.context().spanId() != null) spanId = span.context().spanId();
    }

    try {
        Map<String, Object> logPayload = Map.of(
                "event", "thought_signature_sampled",
                "trace_id", traceId,
                "span_id", spanId,
                "session_id", sessionId,
                "iteration", iteration,
                "thought_signature", thoughtSignature
        );
        log.info("Agent reasoning [THOUGHT]: {}", objectMapper.writeValueAsString(logPayload));
    } catch (Exception e) {
        log.debug("Failed to serialize thought signature sample log: {}", e.getMessage());
    }
}
```
2. Invoke `logThoughtSignatureSampling(sessionId, iterations, toolCall.thoughtSignature(), span)` inside the tool call iteration, and for the final response `logThoughtSignatureSampling(sessionId, iterations, finalThoughtSignature, span)`.

### Task 4: Unit Test Suite
**File:** `apps/polaris-assistant/src/test/java/vn/danang/polaris/assistant/service/AssistantChatServiceTest.java`

1. Add `reactLoop_passesModelRequestContextToClient`:
   - Verify `modelClient.generateResponse` receives `ModelRequestContext` with accurate iteration and intent details.
2. Add `reactLoop_emitsEnrichedSpanEventsWithToolNames`:
   - Mock `Tracer` and `Span`.
   - Verify that `span.event("agent.tool.call: search_available_products")` and `span.event("agent.tool.result: search_available_products")` are invoked.
3. Add `reactLoop_invokesEnrichedRecordToolExecution`:
   - Verify `decisionRecorder.recordToolExecution` is invoked with iteration, validation result, policy decision, required scope, and argument map.
4. Add `reactLoop_logsSampledThoughtSignatureWithoutAttachingToSpan`:
   - Verify that thought signature is logged and that `span.tag(...)` is never called with `thoughtSignature`.

---

## 3. Verification & Acceptance Criteria

1. **Unit Test Pass:**
   ```bash
   mvn clean test -pl apps/polaris-assistant
   ```
2. **Monorepo Reactor Pass:**
   ```bash
   mvn clean test
   ```
3. **Trace Tree Inspection (via `polaris-dev` skill):**
   ```bash
   ./gcx.sh traces query '{resource.service.name = "polaris-assistant"}'
   ```
   Verify:
   - Enclosing span `agent.turn` contains events `agent.tool.call: <tool_name>` and `agent.tool.result: <tool_name>`.
   - Child span `gemini.generate_content <model>` contains `gen_ai.operation.name = "chat"`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.response.finish_reason`, and `agent.iteration`.
   - Child span `mcp.tool_call <tool_name>` contains `gen_ai.tool.name`, `agent.policy.decision`, `agent.tool.validation_result`, `agent.tool.result_size_bytes`, and `mcp.tool.args_summary`.
   - Zero raw prompts or thought signatures in span tags.
