# Slice Work Order: WO-016
## Title: Model Reasoning Spans GenAI Semantic Conventions and Context Enrichment

- **Target Modules:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0016](../decisions/0016-genai-observability-and-mcp-audit-standards.md)
- **Product Reference:** [PRD-006](../../business/prds/PRD-006-genai-observability-and-tool-audit.md)
- **Bounded Contexts:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Status:** COMPLETED

---

## 1. Objective & Scope

Implement OpenTelemetry GenAI Semantic Conventions (v1.27+) and reasoning context enrichment on foundation model distributed tracing spans (`gemini.generate_content`) in `apps/polaris-assistant`:

1. Introduce `ModelRequestContext` domain record in `vn.danang.polaris.assistant.model` capturing `iteration`, `intentId`, `intentConfidence`, and `toolsOfferedCount`.
2. Add a backwards-compatible default method overload to `AssistantModelClient`.
3. Update `GeminiAiModelClient`:
   - Standardize span operation tag: `gen_ai.operation.name = "chat"`.
   - Standardize token usage tags: `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, and `gen_ai.usage.total_tokens` (mapped from Gemini `usageMetadata`). Maintain existing `prompt_tokens` and `completion_tokens` for backwards compatibility.
   - Record finish reason: `gen_ai.response.finish_reason = "tool_calls"` when tool calls are present, or `"stop"` when textual completion is returned.
   - Attach reasoning context tags when context is provided: `agent.iteration`, `agent.intent_id`, `agent.intent_confidence`, `agent.tools_offered_count`.
   - Strictly guarantee that raw user prompts, system instructions, completion texts, and raw `thoughtSignature` blobs are NEVER attached as span tags.
4. Deliver comprehensive unit tests in `GeminiAiModelClientTest` achieving 100% pass rate.

**Constraint Checklist:**
- [x] Strict alignment with OpenTelemetry GenAI Semantic Conventions v1.27+.
- [x] Zero raw prompt, completion, or thought signature text stored in span attributes.
- [x] Maintain 100% binary and source backwards compatibility for `AssistantModelClient` and `GeminiAiModelClient`.
- [x] Fail-safe against null `Tracer`, null `ModelRequestContext`, or missing metadata.
- [x] Unit test coverage must achieve 100% pass rate across `polaris-assistant` and monorepo reactor.

---

## 2. Detailed Technical Tasks

### Task 1: Create Domain Record `ModelRequestContext`
**File:** `llm`

```java
package vn.danang.polaris.assistant.ai;

/**
 * Immutable context describing the agent turn and intent state surrounding a model inference call.
 *
 * @param iteration the 1-based ReAct loop iteration
 * @param intentId the resolved intent identifier
 * @param intentConfidence the confidence score of intent resolution
 * @param toolsOfferedCount the number of tools filtered and offered to the model
 */
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

### Task 2: Extend Interface `AssistantModelClient`
**File:** `llm`
Add the default method:
```java
    /**
     * Send conversation messages and available MCP tools to the AI Model with reasoning context.
     *
     * @param messages conversation messages in this session
     * @param tools available tools discovered via MCP
     * @param context reasoning context including iteration and resolved intent
     * @return ModelResponse containing either text or tool calls
     */
    default ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context) {
        return generateResponse(messages, tools);
    }
```

### Task 3: Update `GeminiAiModelClient` Span Tagging
**File:** `llm`

1. Override `generateResponse(List<AssistantMessage> messages, List<Tool> tools, ModelRequestContext context)`:
   - Implement the full execution logic delegating to `generateResponseInternal(messages, tools, context)`.
   - Keep existing `generateResponse(List<AssistantMessage> messages, List<Tool> tools)` delegating with `ModelRequestContext.empty()`.
2. When creating span `gemini.generate_content %s`:
   - Set `gen_ai.operation.name` to `"chat"` (replace `"generateContent"`).
   - If `context != null`:
     * Tag `agent.iteration`: `String.valueOf(context.iteration())`
     * Tag `agent.intent_id`: `context.intentId() != null ? context.intentId() : "unknown"`
     * Tag `agent.intent_confidence`: `String.valueOf(context.intentConfidence())`
     * Tag `agent.tools_offered_count`: `String.valueOf(context.toolsOfferedCount())`
3. In `parseModelResponse(...)`:
   - Parse `usageMetadata`:
     * If `promptTokenCount` is present:
       - `span.tag("gen_ai.usage.input_tokens", usage.path("promptTokenCount").asText());`
       - `span.tag("gen_ai.usage.prompt_tokens", usage.path("promptTokenCount").asText());` (legacy compatibility)
     * If `candidatesTokenCount` is present:
       - `span.tag("gen_ai.usage.output_tokens", usage.path("candidatesTokenCount").asText());`
       - `span.tag("gen_ai.usage.completion_tokens", usage.path("candidatesTokenCount").asText());` (legacy compatibility)
     * If `totalTokenCount` is present:
       - `span.tag("gen_ai.usage.total_tokens", usage.path("totalTokenCount").asText());`
   - Finish reason tag:
     * If `toolCalls != null && !toolCalls.isEmpty()`:
       - `span.tag("gen_ai.response.finish_reason", "tool_calls");`
     * Else:
       - `span.tag("gen_ai.response.finish_reason", "stop");`
4. Safety Audit:
   - Ensure neither `messages`, `system_instruction`, candidate text, nor `partSignature` / `thoughtSignature` are attached as tags to `span`.

### Task 4: Unit Test Suite
**File:** `llm`

Implement unit tests verifying:
1. `generateResponse_withOtelGenAiSemanticConventions`:
   - Mock HTTP 200 response with `usageMetadata` (`promptTokenCount: 150`, `candidatesTokenCount: 50`, `totalTokenCount: 200`) and a text response.
   - Verify span tags: `gen_ai.operation.name = "chat"`, `gen_ai.usage.input_tokens = "150"`, `gen_ai.usage.output_tokens = "50"`, `gen_ai.usage.total_tokens = "200"`, `gen_ai.response.finish_reason = "stop"`.
2. `generateResponse_withToolCalls_setsFinishReasonToolCalls`:
   - Mock HTTP 200 response returning a `functionCall`.
   - Verify `gen_ai.response.finish_reason = "tool_calls"`.
3. `generateResponse_withRequestContext_attachesContextTags`:
   - Pass `new ModelRequestContext(2, "catalog.product.search", 0.95, 3)`.
   - Verify span tags: `agent.iteration = "2"`, `agent.intent_id = "catalog.product.search"`, `agent.intent_confidence = "0.95"`, `agent.tools_offered_count = "3"`.
4. `generateResponse_strictExclusion_noRawPromptOrThoughtSignatureInSpanTags`:
   - Verify that no span tags named `prompt`, `raw_prompt`, `text`, `completion`, `thought`, or `thoughtSignature` exist on the recorded span.
5. `generateResponse_withNullTracer_operatesGracefully`:
   - Verify normal execution without exceptions when initialized without a `Tracer`.

---

## 3. Verification & Acceptance Criteria

```bash
mvn clean test -pl apps/polaris-assistant -Dtest=GeminiAiModelClientTest
mvn clean test -pl apps/polaris-assistant
```
All tests must pass with 0 failures and 0 errors.
