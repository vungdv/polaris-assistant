# ADR-0020: Grafana AI Observability Compatible GenAI Telemetry

* **Status:** Accepted
* **Deciders:** Polaris Architecture Team, AI Agent Platform Lead, SRE Lead
* **Date:** 2026-10-02
* **Technical Story:** Publish the assistant's model and tool telemetry in the shape [Grafana Cloud AI Observability](https://grafana.com/docs/grafana-cloud/observe-and-act/agent-observability/) consumes, and give the local Grafana the same analytics (requests, latency, errors, tokens, cost) from the same data.
* **Amends:** [ADR-0016](0016-genai-observability-and-mcp-audit-standards.md) §4.2 (model span contract). Lifecycle events ([ADR-0013](0013-agent-turn-span-and-lifecycle-events.md)), the `agent.turn` span and the MCP audit spans are unchanged.

---

## 1. Context and Problem Statement

Grafana Cloud AI Observability is built on OpenTelemetry and fed by two independent pipelines from its open-source SDK ("Sigil", Maven `com.grafana.agento11y:agento11y-core`):

| Pipeline | Carries | Powers |
| --- | --- | --- |
| OTel spans + metrics over OTLP | `gen_ai.client.operation.duration`, `gen_ai.client.token.usage`, `gen_ai.client.time_to_first_token`, `gen_ai.client.tool_calls_per_operation`; `generateText <model>` and `execute_tool <tool>` spans | Analytics (requests, latency, errors, tokens, cost) |
| Generation export (HTTP/gRPC to the AI Observability API) | Structured generation records | Conversations view |

Cost is derived server-side from `gen_ai.client.token.usage` plus the model; analytics group by `gen_ai.agent.name`.

Polaris emitted none of the metrics. Spring Boot builds its `OpenTelemetrySdk` without a `MeterProvider` (Boot metrics go through Micrometer), so any metric recorded through the OTel API was silently dropped. The model span (`gemini.generate_content`, created by `@CustomNextSpan`) used pre-1.0 semconv names (`gen_ai.system`, singular `finish_reason`, string token counts, non-standard `gen_ai.usage.total_tokens`). Adding the SDK on top of the aspect would have produced two nested spans per model call with conflicting attributes.

**How should the assistant emit GenAI telemetry so Grafana Cloud AI Observability and the local stack read the same data, without duplicate spans?**

---

## 2. Decision Drivers

* **Standards first ([AGENTS.md Principle 1](../../../AGENTS.md)):** OTel GenAI semantic conventions; the vendor's own SDK instead of a hand-maintained copy of its attribute set.
* **One owner per span:** every model call and tool execution is exactly one span.
* **No content leakage (ADR-0016 §4.3):** prompts, completions, tool arguments/results and thought signatures never reach spans.
* **Bounded metric cardinality:** conversation, user and request identifiers never become metric labels.
* **Same data locally and in Cloud:** one collector pipeline fans out to both.

---

## 3. Considered Options

1. **Hand-written `gen_ai.*` spans and metrics via the OTel API.** No new dependency, but the attribute set, operation names and error classification must be kept in sync with Grafana by hand, and there is no path to the Conversations view.
2. **SDK for metrics only (no-op tracer), aspect keeps the span.** Grafana's generation records would carry no trace/span id, breaking conversation-to-trace links; semconv attributes still hand-maintained.
3. **The SDK owns the GenAI spans; the aspect keeps business/orchestration spans (selected).** Model calls are recorded by a dedicated `@GenAiGeneration` aspect from the provider client's inputs and outputs, so the clients contain no telemetry code (§4.2).

---

## 4. Decision Outcome

### 4.1 Span ownership

| Span | Owner | Notes |
| --- | --- | --- |
| `agent.turn` | `@CustomNextSpan` | adds `gen_ai.operation.name=invoke_agent`, `gen_ai.agent.name`, `gen_ai.conversation.id` next to the existing `agent.*` keys (additive) |
| `generateText gemini-3.6-flash` | `@GenAiGeneration` on `GeminiAiModelClient.generateResponse` (agento11y SDK) | replaces `gemini.generate_content` |
| `generateText jev-latest` | `@GenAiGeneration` on `TypeSafeIntentClassifier.classify` (agento11y SDK) | agent `polaris-intent-classifier`; replaces `typesafe.intent.classify` |
| `mcp.polaris.execute` (tool batch) | `@CustomNextSpan` | unchanged |
| `execute_tool <tool>` | agento11y SDK (`DefaultToolManager`) | one per dispatched call, child of the batch span |

The SDK never makes its span current while the call runs. Consequences: Gemini receives no `traceparent` (it ignored it anyway, and trace ids no longer leave for a third party). The MCP HTTP call made by a tool sits beside its `execute_tool` span under the batch span instead of under it. Tool calls run on worker threads, so `DefaultToolManager` wraps its executor with the caller's OTel `Context`.

### 4.2 Recording model calls: annotation + result contract

Provider clients only call their provider. What happened on the wire is part of the method's result: `ModelResponse` and `IntentClassification` implement `ModelCallResult`, whose `modelCall()` returns a `ModelCall`. A `ModelCall` holds the request model, response model and id, token usage, finish reason and failure. It is `null` when the provider was not reached: local fallback, missing key or taxonomy, or invalid configuration. A non-2xx answer becomes `ModelProviderException(status)`; transport errors are carried as-is. `IntentClassification.modelCall` is `@JsonIgnore`.

`@GenAiGeneration(provider, agent, conversationId, tags)` on the client method declares the rest. `GenAiGenerationAspect` evaluates the SpEL over the arguments (`#context`) and the result (`#result`) and records one generation through the SDK. It uses the measured start time and records after the method returns; nothing is lost, because the SDK span is never current during the call anyway. A result without a `ModelCall` records nothing. A thrown exception propagates unrecorded: clients turn provider failures into `ModelCall.failure`, so a throw means no provider call result exists. Calls that reach the 3-argument `generateResponse` through `this` (the 2-argument overload, `chat()`) are covered by the annotation on the outermost proxied method; `chat()` returns `String`, so the service only uses the annotated overloads.

### 4.3 Model span contract (replaces ADR-0016 §4.2 for model spans)

`gen_ai.operation.name=generateText`, `gen_ai.provider.name` (`gemini`, `typesafe`), `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.response.id`, `gen_ai.response.finish_reasons` (`["tool_calls"]` or `["stop"]`), `gen_ai.usage.input_tokens` / `output_tokens` / `reasoning_tokens` / `cache_read_input_tokens` (integers), `gen_ai.token.semantics=inclusive` (Gemini's prompt count includes cached tokens), `gen_ai.agent.name`, `gen_ai.conversation.id` (chat session). Failures set `error.type=provider_call_error` and `error.category` (`rate_limit`, `auth_error`, `timeout`, `server_error`, ...) from the HTTP status carried by `ModelProviderException`; the user still gets the same graceful fallback reply.

ReAct context (`iteration`, `intent_id`, `intent_confidence`, `tools_offered`) is passed as per-generation tags. The SDK puts those on the generation record only, never on spans or metric labels. In traces the iteration stays visible through the `agent.turn` lifecycle events (ADR-0013).

### 4.4 Configuration (12-factor)

* `GenAiTelemetryConfig` registers an `SdkMeterProvider` (OTLP to `management.otlp.metrics.export.url`, 15 s interval) on Boot's `OpenTelemetrySdk`, and one `Agento11yClient` given the app's tracer and meter.
* Content capture defaults to `metadata_only` (`AGENTO11Y_CONTENT_CAPTURE_MODE`).
* Generation export defaults to `none` (`AGENTO11Y_PROTOCOL`). Setting `http` or `grpc` plus `AGENTO11Y_ENDPOINT` and `AGENTO11Y_AUTH_*` (see `.env.template`) turns on the Grafana Cloud Conversations view without a code change.

### 4.5 Collector

* `transform/metric_allowlist` keeps the `gen_ai.*` key family. All of its keys are bounded by configuration (providers, models, agents, tools, token types).
* The credential guard now drops keys that *end* in `token` (`token$`) instead of keys that contain it. The old pattern deleted `gen_ai.token.type`, which merged input and output tokens into one series and broke cost.

### 4.6 Local Grafana

* **Dashboard:** `docker/telemetry/grafana/provisioning/dashboards/json/ai-observability.json` (Polaris / AI Observability, read-only, provisioned) mirrors the Cloud Analytics page from the same metrics, plus tool panels and a Tempo table of recent generations.
* **Cost:** local cost uses the price table in `docker/telemetry/prometheus/rules/genai-token-prices.yml` (`polaris:gen_ai_token_price_usd`); Cloud computes cost server-side.
* **Not shown:** time to first token, because the assistant calls Gemini without streaming and the metric is never emitted. The Cloud "AI analysis" banner is a Grafana Assistant feature with no local equivalent.

---

## 5. Consequences

* **Positive:** Cloud AI Observability analytics work from the existing OTLP fan-out; the local dashboard and Cloud read identical series; one span per model call/tool execution; error rate now counts provider failures that were previously returned as normal replies.
* **Negative:** new pre-1.0 dependency (`agento11y-core` 0.7.x, pulls grpc-netty and protobuf at runtime); span names in ADR-0011/0016 and PRD-004/006 examples (`gemini.generate_content`, `typesafe.intent.classify`) are historical; the local price table must be updated when list prices change (next: 2027-01-01).

---

## 6. Verification

* `GeminiAiModelClientModelCallTest`, `TypeSafeIntentClassifierModelCallTest`: the `ModelCall` each client reports, with no telemetry involved.
* `GenAiGenerationAspectTest`: the aspect's own contract (arguments/result SpEL, no record without a model call, exceptions propagate unrecorded, broken tag expressions tolerated).
* `GeminiAiModelClientTelemetryTest`, `TypeSafeIntentClassifierTelemetryTest` (clients behind the aspect proxy), `DefaultToolManagerTelemetryTest`: real OTel SDK with in-memory span and metric exporters. They check span names and attributes, token metrics by type, error classification, no content in spans, no `traceparent` to Google, and `execute_tool` parented to the caller's span across the thread hop.
* `GenAiTelemetryConfigTest`: Boot's `OpenTelemetrySdk` carries the registered `SdkMeterProvider`; defaults are `metadata_only` and no generation export.
* End-to-end on the compose stack: chat turns produce `gen_ai_client_operation_duration_seconds_*`, `gen_ai_client_token_usage_*` (with `gen_ai_token_type`) in Prometheus and the span tree above in Tempo; every dashboard query evaluates against Prometheus.
