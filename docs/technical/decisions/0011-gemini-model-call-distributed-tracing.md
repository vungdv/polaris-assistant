# ADR-0011: Distributed Tracing & W3C Context Propagation for Gemini AI Model Invocations

* **Status:** Accepted
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-09-15
* **Technical Story:** Instrumenting `apps/polaris-assistant` foundation model calls (`GeminiAiModelClient`) with Micrometer Tracing child spans, OpenTelemetry GenAI semantic conventions, token usage metadata capture, and outbound W3C `traceparent` context propagation.

---

## 1. Context and Problem Statement

`polaris-assistant` operates as an autonomous microservice communicating with Polaris Core via Model Context Protocol ([ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md)). When handling conversational requests (`POST /api/v1/assistant/chat`), `AssistantChatService` executes an autonomous ReAct loop alternating between foundation model inference (`GeminiAiModelClient`) and tool execution (`PolicyToolManager`).

While tool invocations across MCP are traced via `PolicyToolManager` (`mcp.tool_call <tool_name>`), model inference calls to Google Gemini (`POST /v1beta/models/{model}:generateContent`) currently have zero tracing instrumentation:
1. **Latency Blind Spot:** Model inference accounts for 70–90% of total turn latency (800ms–4000ms), but appears as untracked dead time in Grafana Tempo traces.
2. **Missing Token & Cost Observability:** Gemini API returns `usageMetadata` (prompt tokens, candidates tokens, total tokens), but these metrics are discarded rather than tagged on traces.
3. **Protocol Non-Conformance:** [`AGENTS.md`](../../../AGENTS.md) Principle 3 mandates standard W3C `traceparent` propagation across all external HTTP entrypoints and egress calls. `GeminiAiModelClient` currently emits requests without trace context.
4. **Error Isolation:** Outbound HTTP errors (429 Rate Limits, 400 Bad Requests, 503 Overloaded) or network exceptions are logged in application logs but not recorded as error events on distributed spans.

How should Polaris architect distributed tracing for Gemini model calls to ensure full end-to-end trace visibility in Tempo without introducing latency, blocking calls, or breaking changes to existing contracts?

---

## 2. Decision Drivers

* **Observability by Default ([AGENTS.md Principle 3.1](../../../AGENTS.md)):** Unbroken distributed tracing across Web Gateway -> Assistant App -> Gemini API -> Polaris Core MCP -> PostgreSQL.
* **OpenTelemetry GenAI Semantic Conventions:** Alignment with industry standard semantic attributes (`gen_ai.system`, `gen_ai.request.model`, `gen_ai.operation.name`, `gen_ai.usage.*`).
* **Framework Homogeneity:** Standardize on Spring Boot 4.x / Micrometer Tracing (`io.micrometer.tracing.Tracer`), matching [`PolicyToolManager`](../../../apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/ExternalMcpHub.java) and [`TraceFilter`](../../../libs/polaris-common/src/main/java/vn/danang/polaris/config/TraceFilter.java).
* **Fail-Safe & Non-Blocking Resilience:** Telemetry failures (missing tracer, tracer initialization error) must never disrupt model inference or user chat availability.
* **Interface & Binary Stability ([Evaluation Criteria B1-B4]):** Zero breaking changes to `AssistantModelClient` contract or existing unit tests.

---

## 3. Considered Options

### Option 1: In-Process Micrometer Tracer Instrumentation in `GeminiAiModelClient` (Selected)
Inject `ObjectProvider<Tracer>` into `GeminiAiModelClient`. Wrap the outbound HTTP call in a child span named `gemini.generate_content {model}`, inject W3C `traceparent` into the `HttpRequest`, and tag latency, token counts, and error statuses.

* **Pros:** Direct alignment with `PolicyToolManager` pattern; zero additional dependencies; full access to request/response lifecycle for token parsing; fail-safe via `tracer == null` guard.
* **Cons:** Requires updating `GeminiAiModelClient` constructor with overloaded fallback.

### Option 2: Java `HttpClient` Interceptor / Decorator
Wrap the standard `HttpClient` with an HTTP tracing decorator that intercepts all outbound requests.

* **Pros:** Generic HTTP-level tracing.
* **Cons:** Loses domain-specific GenAI context (tools count, prompt tokens, candidate tokens, model parameters); complex reflection needed for `java.net.http.HttpClient` interceptors in Java 21.

### Option 3: Spring AOP (`@Observed` / `@NewSpan`)
Annotate `GeminiAiModelClient.generateResponse` with Micrometer's `@Observed`.

* **Pros:** Declarative code.
* **Cons:** Does not automatically inject `traceparent` into `HttpRequest.Builder`; cannot easily extract response body JSON fields (`usageMetadata`) without clumsy aspect parsing.

---

## 4. Decision Outcome & Detailed Specification

**Chosen Option:** Option 1: In-Process Micrometer Tracer Instrumentation.

### Architectural Specification:

1. **Tracer Injection & Constructor Compatibility:**
   ```java
   @Component
   public class GeminiAiModelClient implements AssistantModelClient {
       private final AssistantAiProperties aiModelConfig;
       private final HttpClient httpClient;
       private final ObjectMapper objectMapper;
       @Nullable
       private final Tracer tracer;

       @Autowired
       public GeminiAiModelClient(AssistantAiProperties aiModelConfig, ObjectProvider<Tracer> tracerProvider) {
           this(aiModelConfig, HttpClient.newBuilder()
                   .connectTimeout(Duration.ofSeconds(10))
                   .build(), new ObjectMapper().findAndRegisterModules(),
                   tracerProvider != null ? tracerProvider.getIfAvailable() : null);
       }

       // Backwards-compatible constructors for testing & legacy calls
       public GeminiAiModelClient(AssistantAiProperties aiModelConfig) {
           this(aiModelConfig, (Tracer) null);
       }
       public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper) {
           this(aiModelConfig, httpClient, objectMapper, null);
       }
       public GeminiAiModelClient(AssistantAiProperties aiModelConfig, HttpClient httpClient, ObjectMapper objectMapper, @Nullable Tracer tracer) { ... }
   ```

2. **Span Lifecycle & Naming:**
   - Span name: `gemini.generate_content <model>` (e.g. `gemini.generate_content gemini-3.6-flash`).
   - If model is null or blank, fallback to `gemini.generate_content unknown`.
   - Span is created exclusively when an actual model request is dispatched (skips unconfigured/placeholder fallback runs).

3. **Semantic Attributes (OpenTelemetry GenAI Aligned):**
   - `gen_ai.system`: `"gemini"`
   - `gen_ai.request.model`: e.g. `"gemini-3.6-flash"`
   - `gen_ai.operation.name`: `"generateContent"`
   - `gen_ai.client`: `"GeminiAiModelClient"`
   - `peer.service`: `"generativelanguage.googleapis.com"`
   - `gemini.tools.count`: Count of function declarations passed to model (if > 0)
   - `gemini.tool_calls.count`: Count of function calls returned in response (if > 0)
   - `gen_ai.usage.prompt_tokens`: Extracted from `usageMetadata.promptTokenCount`
   - `gen_ai.usage.completion_tokens`: Extracted from `usageMetadata.candidatesTokenCount`
   - `gen_ai.usage.total_tokens`: Extracted from `usageMetadata.totalTokenCount`

4. **W3C Traceparent Header Injection:**
   Extract active trace context from span:
   ```java
   if (span != null && span.context() != null) {
       String traceId = span.context().traceId();
       String spanId = span.context().spanId();
       if (traceId != null && spanId != null) {
           builder.header("traceparent", "00-" + traceId + "-" + spanId + "-01");
       }
   }
   ```

5. **Error & Exception Handling:**
   - If HTTP status != 200: Tag `http.status_code` and tag `error` = `"true"`.
   - On `IOException`, `InterruptedException`, `IllegalArgumentException`, or `RuntimeException`:
     Call `span.error(e)`, tag `error` = `"true"`, tag `error.type` = `e.getClass().getSimpleName()`.
   - All spans guarantee termination in `finally { span.end(); }`.

---

## 5. Boundary & Interface Stability Analysis

| Dimension | Classification | Details |
|---|---|---|
| **Public Interface** | Stable | `AssistantModelClient` contract is untouched (`chat`, `generateResponse`). |
| **Constructor Contract** | Additive / Backwards Compatible | New constructor accepts `Tracer` / `ObjectProvider<Tracer>`; existing 1-arg and 3-arg constructors preserved. |
| **REST Endpoints** | Stable | `POST /api/v1/assistant/chat` signature and payload unchanged. |
| **Telemetry Egress** | Additive | New spans emitted via standard OTLP HTTP exporter to `otel-collector:4318`. |
| **Downstream Impact** | Zero Breakage | No external services broken; Tempo traces receive additional child spans. |
| **Reversibility** | Immediate / Low Risk | Feature can be deactivated by passing `null` tracer or setting `management.tracing.enabled=false`. |
