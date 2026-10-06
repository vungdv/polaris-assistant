# Slice Work Order: WO-011
## Title: Distributed Tracing & W3C Context Propagation for Gemini AI Model Calls

- **Target Module:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0011](../decisions/0011-gemini-model-call-distributed-tracing.md)
- **Product Requirement:** [PRD-004](../../business/prds/PRD-004-gemini-model-observability-and-distributed-tracing.md)
- **Bounded Context:** Polaris Assistant Context
- **Status:** READY FOR DEV

---

## 1. Objective & Scope

Implement distributed tracing for all Google Gemini model calls in `apps/polaris-assistant` using Spring Boot / Micrometer Tracing. Correlate model inference within the active request trace, capture OpenTelemetry GenAI semantic tags, record token usage metadata, propagate W3C `traceparent` headers, and ensure fail-safe operation.

**Constraint Checklist:**
- [x] Modify ONLY `apps/polaris-assistant`. Do not edit `apps/polaris` or `libs/polaris-common`.
- [x] Do NOT break `AssistantModelClient` interface.
- [x] Maintain backwards-compatible constructors in `GeminiAiModelClient` so existing tests compile and pass.
- [x] All tracing logic must be resilient and non-blocking (null-safe against missing tracer).

---

## 2. Detailed Technical Tasks

### Task 1: Update `GeminiAiModelClient.java`
**File:** `llm`

1. **Inject Tracer**:
   - Add field `@Nullable private final Tracer tracer;` (import `io.micrometer.tracing.Tracer`, `io.micrometer.tracing.Span`, `jakarta.annotation.Nullable`).
   - Add `@Autowired` constructor accepting `AssistantAiProperties aiModelConfig, ObjectProvider<Tracer> tracerProvider`.
   - Preserve existing constructors `(aiModelConfig)`, `(aiModelConfig, httpClient, objectMapper)`, chaining to the full 4-argument constructor:
     `public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper, @Nullable Tracer tracer)`
2. **Span Creation in `generateResponse`**:
   - Guard against unconfigured API keys (return local fallback immediately before starting network span).
   - If `this.tracer == null`: invoke HTTP request execution directly.
   - If `this.tracer != null`:
     - Resolve model: `String model = Optional.ofNullable(aiModelConfig.getModel()).filter(s -> !s.isBlank()).orElse("unknown");`
     - Span name: `gemini.generate_content %s`.formatted(model);
     - Create span: `Span span = this.tracer.customNextSpan().name(spanName);`
     - Start span: `span.start();`
     - Tag baseline attributes:
       - `gen_ai.system`: `"gemini"`
       - `gen_ai.request.model`: `model`
       - `gen_ai.operation.name`: `"generateContent"`
       - `gen_ai.client`: `"GeminiAiModelClient"`
       - `peer.service`: `"generativelanguage.googleapis.com"`
       - If `tools != null && !tools.isEmpty()`: `gemini.tools.count`: `String.valueOf(tools.size())`
3. **W3C `traceparent` Injection**:
   - In `buildRequest`, if `span != null && span.context() != null`:
     - Extract `traceId = span.context().traceId()` and `spanId = span.context().spanId()`.
     - Inject header: `builder.header("traceparent", "00-" + traceId + "-" + spanId + "-01");`
4. **Error & Status Recording**:
   - If `response.statusCode() != 200`:
     - `span.tag("http.status_code", String.valueOf(response.statusCode()));`
     - `span.tag("error", "true");`
   - In catch blocks (`InterruptedException`, `IOException`, `IllegalArgumentException`, `RuntimeException`):
     - `span.error(e);`
     - `span.tag("error", "true");`
     - `span.tag("error.type", e.getClass().getSimpleName());`
5. **Token Usage Extraction in `parseModelResponse`**:
   - If response JSON contains `usageMetadata`:
     - If `usageMetadata.has("promptTokenCount")`: tag `gen_ai.usage.prompt_tokens`
     - If `usageMetadata.has("candidatesTokenCount")`: tag `gen_ai.usage.completion_tokens`
     - If `usageMetadata.has("totalTokenCount")`: tag `gen_ai.usage.total_tokens`
   - If `toolCalls` parsed:
     - tag `gemini.tool_calls.count`: `String.valueOf(toolCalls.size())`
6. **Span Lifecycle Closure**:
   - Manage scope with `try (Tracer.SpanInScope ws = this.tracer.withSpan(span))`
   - Guarantee `span.end()` in `finally`.

---

### Task 2: Update Unit Tests in `GeminiAiModelClientTest.java`
**File:** `llm`

1. Verify all existing tests run and pass without modification.
2. Add new unit tests exercising:
   - `generateResponse_withTracer_createsSpanWithTagsAndInjectsTraceparent`: Mock `Tracer`, `Span`, and `TraceContext`. Assert span name, tags, and capture `HttpRequest` to assert `traceparent` header is present.
   - `generateResponse_withTracer_recordsHttpErrorOnSpan`: Mock HTTP 400 response. Verify `span.tag("error", "true")` and `span.tag("http.status_code", "400")`.
   - `generateResponse_withTracer_recordsExceptionOnSpanWhenNetworkFails`: Mock `IOException`. Verify `span.error(any())` and `span.tag("error", "true")`.
   - `generateResponse_withTracer_capturesUsageMetadata`: Mock response with `usageMetadata`. Verify tags `gen_ai.usage.prompt_tokens`, `candidatesTokenCount`, and `totalTokenCount`.
   - `generateResponse_withoutTracer_worksGracefully`: Instantiate without tracer; verify normal execution.

---

## 3. Verification Commands & Gate Checklist

Run local verification before preparing Completion Report:
```bash
mvn clean test -pl apps/polaris-assistant
```

**Completion Report Checklist for `domain-dev-agent`**:
- [ ] Automated tests pass (`mvn clean test -pl apps/polaris-assistant`).
- [ ] No regression on existing 8 tests in `GeminiAiModelClientTest`.
- [ ] New unit tests verifying span creation, tags, W3C header, token counts, and exception handling.
- [ ] No direct dependency introduced on Polaris Core database or entities.
- [ ] Backwards-compatible constructors intact.
