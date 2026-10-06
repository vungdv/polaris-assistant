# PRD-004: Distributed Tracing & Observability for Gemini AI Model Invocations

- **Context/Domain:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Target Personas:** SRE / DevOps Engineers, Assistant Platform Developers, Store Operators
- **Status:** Approved (Ready for Implementation)
- **Document Owner:** Polaris Fleet Architect & Product Lead
- **Last Updated:** 2026-09-15

---

## 1. Business Problem & Value Proposition

In Polaris conversational commerce, the AI Assistant translates shopper questions into catalog searches and order operations. Each conversational turn depends heavily on foundation model inference (Google Gemini).

Currently, operators and developers cannot observe Gemini model calls in Grafana Tempo traces. When a request experiences high latency (e.g. 5 seconds) or fails with an error (e.g. rate limit 429), operators cannot differentiate whether the delay occurred in Keycloak authentication, MCP database queries, network transit, or Gemini model inference.

Delivering **Distributed Tracing for Gemini Model Calls** provides:
1. **Zero-Blind-Spot Request Walkability:** Complete trace visualization in Tempo showing gateway -> chat controller -> Gemini inference -> MCP tool dispatch -> DB query.
2. **Token Consumption & Cost Attribution:** Real-time visibility into prompt, candidate, and total token counts per conversational turn directly on trace spans.
3. **Instant Incident Diagnosis:** Immediate identification of model rate limiting (429), quota exhaustion, bad payloads (400), or upstream network drops with tagged error details.
4. **W3C Protocol Conformance:** Compliance with enterprise distributed tracing specifications across all outbound HTTP hops.

---

## 2. Personas & Use Cases

### Persona A: Site Reliability Engineer (SRE) / Platform Operator
- **Goal:** Troubleshoot p95 and p99 latency spikes on `/api/v1/assistant/chat`.
- **Use Case:** Inspects a trace in Tempo; immediately observes whether latency is dominated by Gemini inference (`gemini.generate_content`) or MCP tool execution (`mcp.tool_call search_available_products`).

### Persona B: Assistant Platform Developer
- **Goal:** Optimize context window usage and model prompt engineering.
- **Use Case:** Inspects `gen_ai.usage.prompt_tokens` and `gen_ai.usage.completion_tokens` on spans to measure the token cost of system instructions and tool declarations.

---

## 3. Business Acceptance Criteria (Given / When / Then)

### Scenario 1: Standard AI Chat Turn Generates Trace Span & Propagates W3C Header
- **Given** an authenticated user chat request and a configured Gemini API key.
- **When** `GeminiAiModelClient.generateResponse` executes an outbound call to Gemini.
- **Then** a child distributed trace span named `gemini.generate_content {model}` is started and ended.
- **And** the span contains tags: `gen_ai.system="gemini"`, `gen_ai.request.model="{model}"`, `gen_ai.operation.name="generateContent"`, and `peer.service="generativelanguage.googleapis.com"`.
- **And** the outgoing HTTP request contains the W3C header `traceparent: 00-{traceId}-{spanId}-01`.

### Scenario 2: Gemini API Error Records Error Tags and HTTP Status
- **Given** the Gemini API returns an HTTP 400 or HTTP 429 response.
- **When** `GeminiAiModelClient` parses the response.
- **Then** the trace span is tagged with `error="true"` and `http.status_code="400"` (or `"429"`).
- **And** the span ends cleanly without crashing the conversation fallback message.

### Scenario 3: Network Exception Records Error on Span
- **Given** an `IOException` (e.g. connection timeout or DNS resolution failure) occurs during HTTP dispatch.
- **When** the exception is caught in `GeminiAiModelClient`.
- **Then** the span records the exception via `span.error(e)`.
- **And** the span is tagged with `error="true"` and `error.type="IOException"`.

### Scenario 4: Token Usage Metadata Captured on Span
- **Given** the Gemini API returns a response with `usageMetadata` containing `promptTokenCount: 28`, `candidatesTokenCount: 45`, `totalTokenCount: 73`.
- **When** `GeminiAiModelClient` processes the response payload.
- **Then** the span is decorated with `gen_ai.usage.prompt_tokens="28"`, `gen_ai.usage.completion_tokens="45"`, and `gen_ai.usage.total_tokens="73"`.

### Scenario 5: Tool Call and Tool Declaration Count Captured
- **Given** `generateResponse` is called with 2 tool definitions and Gemini returns a `functionCall`.
- **When** the span is recorded.
- **Then** the span contains `gemini.tools.count="2"` and `gemini.tool_calls.count="1"`.

### Scenario 6: Unconfigured API Key Bypasses External Span
- **Given** no API key is configured or the key is an unresolved placeholder (`${GEMINI_API_KEY}`).
- **When** `GeminiAiModelClient` returns the local fallback message.
- **Then** zero external HTTP spans are emitted to avoid phantom external call records.

### Scenario 7: Fallback When Tracer is Unavailable
- **Given** `GeminiAiModelClient` is instantiated without a `Tracer` (e.g. unit tests or tracer bean disabled).
- **When** `generateResponse` or `chat` is invoked.
- **Then** the model call proceeds normally without NullPointerException or degradation.
