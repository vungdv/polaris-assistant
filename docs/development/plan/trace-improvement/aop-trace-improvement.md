# Observability Improvement: Keep Telemetry Out of Business Code

- **Scope:** observability only (traces, logs, metrics), mainly `apps/polaris-assistant`; the shared pieces are noted in §6
- **Related:** AGENTS.md Principle 1 (standards), Principle 3.1 (observability), 3.3 (anti-god class); ADR-0011/0012/0013/0016/0020; `assistant-turn-behaviour.md` (B2/B3 own the turn-outcome metric)
- **Status:** Draft for review (2026-10-20): §5 decisions need an answer before O3

## 1. Problem statement

For a production-grade system there are many requirements beside business logic:
- Resilient, fault-tolerant
- Observability

If the system adds an AI-agent component there are more:
- Intent log and trace
- Token usage

Combined, they make even a single function too complex to understand or manage.

## 2. Approach

There are several approaches, such as aspect-oriented programming and behavioral patterns like strategy or decorator. They share one idea: decompose into single-responsibility (SRP) units.

Sometimes we need to log or tag the state of a processing function, which can violate SRP. So design the code to avoid that.

### Trace on input/output

For a given component or function we have `Output = f(Input)`. We shouldn't change `f`. We can:
- Use AOP to create a proxy/wrapper of `f` dynamically, then log, tag or measure there.
- If `f` depends on `f1`, `f2` and we want to observe them, apply the AOP on `f1`, `f2` directly. If we don't own them, wrap them and apply the AOP on the wrapper.

Two limits of AOP:
- A Spring proxy only sees calls that cross a bean boundary. A `private` method, or a call to `this.x()`, is never intercepted. This is why some tagging is still inline today: the data lives inside a private method of the same class (§3.1).
- Telemetry that depends on a value computed in the middle of `f` (not its input or output) can't be attached from outside. The fix is to expose that value in `f`'s result, not to reach into `f`.

## 3. Findings: what the code does today

### 3.1 Inline observability that violates SRP

| # | Where | What | Why it is inline |
|:--|:--|:--|:--|
| F1 | `AssistantChatService.tagResolvedIntent` | Tags `intent.id`, `intent.confidence`, `intent.meets_threshold` on the current span | The value comes from `intentResolutionFacade.resolve(...)`, a separate bean that already returns it |
| F2 | `AssistantChatService.tagIterationsCount` | Tags `agent.iterations.count` | `iterations` is the result of the private `executeConversationLoop`, so no proxy can see it |
| F3 | `AssistantChatService` constructors | Five constructors, two of which exist only to inject `Tracer` (used by F1/F2 alone) | Result of F1/F2 |
| F4 | `AssistantChatService.sendMessage` | Three `outcomeMetrics.turn(...)` calls inside `try`/`catch` | The outcome is a local of the loop. **Owned by `assistant-turn-behaviour.md` B2/B3**; this plan only makes sure the seam is compatible |
| F5 | `OrderDraftService`, `OrderDraftConfirmationService` | 11 `outcomeMetrics.draftClosed(...)` / `draftStaged()` calls inside the business flow, each with its own "after commit" care | Domain facts recorded where they happen |
| F6 | `DefaultToolManager.recordExecution` | Wraps every tool call in `genAiTelemetry.startToolExecution(...)` by hand | The only GenAI recording not behind an annotation (model calls use `@GenAiGeneration`) |
| F7 | `CircuitBreakerObserver.tagState` | Tags `circuit_breaker.<name>.state` on the active span from inside the resilience decorators | Reasonable; only a candidate if the aspect gains a "current span" mode |

### 3.2 Bespoke code where a standard exists (Principle 1)

| # | Where | What | Standard |
|:--|:--|:--|:--|
| F8 | `HttpPolarisMcpClient.injectTraceParent`, `HttpCurrentCustomerClient` (same code) | Builds the `traceparent` header by string concatenation; hard-codes `sampled` flag; no `tracestate`/baggage | Micrometer `Propagator.inject(...)` (W3C), or OTel `java-http-client` instrumentation, which also gives a CLIENT span |
| F9 | The same two clients | The outbound HTTP call has no CLIENT span; only the caller's span id is forwarded, so call latency is invisible and the MCP server span hangs directly under `execute_tool` | OTel HTTP semconv (`http.request.method`, `server.address`, `http.response.status_code`) |
| F10 | `CustomNextSpan` / `SpanTag` | Reimplements Micrometer's `@NewSpan` + `@SpanTag` | Justified today only because of **result tags**, which the Micrometer annotations don't support. Keep, but record this as the reason (see D1) |
| F11 | Logging | `logback-spring.xml` includes Boot's text `base.xml` + the OTel appender. Console output is not JSON | Boot structured logging (`logging.structured.format.console=ecs`); Boot 4.1 puts `traceId`/`spanId` from MDC into the document. AGENTS.md P3.1 asks for machine-readable logs |

### 3.3 Defects and drift found while reading

| # | Finding | Evidence | Risk |
|:--|:--|:--|:--|
| D-1 | **Docs claim lifecycle events that don't exist.** README ("structured lifecycle milestone events `agent.request.received`, … `agent.completed`") and ADR-0013 / WO-013 / WO-018 describe `span.event(...)` calls. No `.event(` exists in `src/main`; only a stale mock in `AssistantChatServiceTest:940` | `grep` over `apps`, `libs` | Breaks "every capability claimed in documentation must map to a visible component" (P1.3) |
| D-2 | Span tag typo: `mcp.itent_id`, `mcp.itent_confidence`. They are also named `mcp.*` though `IntentToolExecutor` is not MCP-specific | `IntentToolExecutor:50-51` | Queries on the correct name find nothing |
| D-3 | Tool-call arguments are logged at INFO: `"Model requested tool call: '{}' with arguments: {}"`. Provider error bodies are logged too (`Gemini API error ... body`, `TypeSafe API error ... body`, `Current customer lookup rejected ... body`) | `IntentToolExecutor:115`, `GeminiAiModelClient:392`, `TypeSafeIntentClassifier:180` | Arguments can carry addresses and order contents; ADR-0016 promises PII/PCI redaction, and no redaction code exists |
| D-4 | `CustomNextSpanAspect` swallows post-execution tag failures at `DEBUG`, and re-parses every SpEL expression on every call | `CustomNextSpanAspect` | A wrong expression silently drops a tag forever; per-call parsing cost on the hot path |
| D-5 | Inconsistent log fields: `sessionId: {}`, `sessionId={}`, `session_id`; `userId` logged in clear at INFO | across `service/`, `tools/` | Logs can't be joined on one field; no per-turn correlation |
| D-6 | Two aspects with their own span handling (`CustomNextSpanAspect` @Order 10, `GenAiGenerationAspect` @Order 20) and no test that fixes the resulting span tree | `observability/` | A refactor of tags can't be proven safe |
| D-7 | Tool-batch outcome (`ToolResultsSummary`: success/denied/error counts) exists only as span tags and a log line, not as a metric | `IntentToolExecutor` | No alert or SLO on tool failure rate |

### 3.4 What is already good (keep)

- OTLP pipeline for traces, logs and metrics; `TraceIdFilter` echoes `X-Trace-Id`; sampling is configured.
- Annotation-driven tags already exist on `agent.turn`, `mcp.polaris.discovery`, the intent executor and the order-draft services.
- `@GenAiGeneration` keeps model-call telemetry out of the three provider clients.
- `AssistantOutcomeMetrics` has bounded tag values and registers series at zero.
- Metric SLO buckets and Prometheus rules are provisioned as code; `opentelemetry-sdk-testing` is already a test dependency.

## 4. Proposed changes

Design rules for every slice:
- **Pin before moving.** Slice O1 adds a telemetry contract test. Every later slice must pass it without edits unless the slice says it renames something.
- **Tag/attribute names are an interface.** Renames are additive first (emit both for one release), then the old name is dropped. No dashboard or rule in `docker/telemetry` uses these names today (checked), so risk is low, but external queries may.
- **Observation code is never inside a transaction callback or a loop body** unless it must be (the draft metrics, see O5).

| ID | Change | Fixes |
|:--|:--|:--|
| **O1** | **Telemetry contract test.** One integration test per entrypoint using `InMemorySpanExporter` (`opentelemetry-sdk-testing`) and a `SimpleMeterRegistry`. For a chat turn with a tool call, assert the span tree (`agent.turn` → `mcp.polaris.discovery`, intent classification generation, `generateText`, `execute_tool`), the required attributes by name, and the `polaris.assistant.turns` increment. Also assert the error path: a failing turn marks `agent.turn` as error. Replace the stale `mockSpan.event(...)` stub. No production change. | D-6, safety net for all slices |
| **O2** | **Fix defects and drift.** (a) Rename `mcp.itent_*` → `intent.*`-style names, additive then drop (D-2). (b) Decide D-1: **implement** the events as `span.event(...)` in the loop (needs F2's `ConversationLoop` seam, so do it in O3), or **delete** the claims from README/ADR-0013/WO-013/WO-018 and mark the ADR "Amended". Until O3, mark the README line as "planned". (c) `CustomNextSpanAspect`: parse each SpEL once per method (cache), log failed tags at `WARN` with the span name and tag key, never the value (D-4). (d) Stop logging tool arguments and provider bodies at INFO; log tool name and argument keys, and a truncated body at `DEBUG` only (D-3). | D-1, D-2, D-3, D-4 |
| **O3** | **Take F1–F3 out of `AssistantChatService`.** (a) Add `@TagCurrentSpan` (aspect, no new span, tags the active span from `#result` / arguments; same semantics as Micrometer's `@ContinueSpan`, plus result tags). Put it on `IntentResolutionFacade.resolve` for `intent.id`, `intent.confidence`, `intent.meets_threshold`. (b) Extract the ReAct loop into its own bean `ConversationLoop` (`service/turn/`; the same extraction `assistant-turn-behaviour.md` B2 needs, so the two plans share one slice here, see §5 D3). Its result record carries `iterations`; `@TagCurrentSpan` on `ConversationLoop.run` writes `agent.iterations.count`. (c) Delete `tagResolvedIntent`, `tagIterationsCount`, `currentSpan()`, the `Tracer` field and the four now-redundant constructors. (d) If D-1 chose "implement": the loop emits the milestone events through a small `TurnEvents` port (one method per milestone), not by calling `Span` directly. | F1, F2, F3, D-1 |
| **O4** | **Standard propagation and a CLIENT span for outbound HTTP.** Replace both hand-rolled `injectTraceParent` copies with one `TraceContextPropagation` helper over Micrometer's `Propagator`, or (D2) wrap the JDK `HttpClient` with OTel's `opentelemetry-java-http-client` so the CLIENT span, semconv attributes and propagation come from the library. Applies to `HttpPolarisMcpClient`, `HttpCurrentCustomerClient`. Verify in Tempo that the Core MCP span is now a child of the new CLIENT span. | F8, F9 |
| **O5** | **Domain metrics out of the business flow (F5, F6, D-7).** (a) Draft metrics: services publish a Spring `ApplicationEvent` (`DraftStaged`, `DraftClosed(draft, outcome, at)`); `AssistantOutcomeMetrics` listens with `@TransactionalEventListener(AFTER_COMMIT)`. This replaces the hand-written "record after commit" logic, and a rolled-back or retried attempt still never counts. (b) `DefaultToolManager.recordExecution`: add `@GenAiToolExecution` next to `@GenAiGeneration`, applied to the two `execute*ToolCall` paths via a thin `ToolInvoker` bean (they are private today, so the AOP can't see them). (c) Tool-batch metric `polaris.assistant.tool.calls{tool, outcome}` recorded from `ToolResult`s by an aspect on `IntentToolExecutor.execute`, with `tool` bounded to registered tool names (unknown → `other`) and the series registered at zero. | F5, F6, D-7 |
| **O6** | **Structured, correlated, redacted logs.** (a) `logging.structured.format.console=ecs`; keep the OTel appender (OTLP logs carry trace context natively). Check the Loki/collector pipeline still parses the console output. (b) Put `session_id` into the log context for the whole turn through Micrometer baggage as a *local correlation field* (`management.tracing.baggage.correlation.fields`), not a remote one, so every log line in a turn carries it with no `sessionId` in the message. (c) Log-field convention in `Engineer-Guidelines.md` §5: `key=value` tail, snake_case keys, ids as fields, never raw user text or tool arguments. (d) Hash or drop `user_id` in logs (keep it on the span, as today). | D-3, D-5, F11 |
| **O7** | **Reuse across applications.** Move `observability/trace` (`CustomNextSpan`, `SpanTag`, `TagCurrentSpan`, the aspect) into a small shared module only when a second consumer exists. Today Core and the fulfilment emulator get spans from Boot auto-instrumentation and the outbox lib (`HandOffTracing`), and have no annotation need. **Do not do this speculatively** (P2.3). Revisit after O3 if Core wants `agent`-style business tags. | F10 |

### Target shape of the turn (after O3)

```
AssistantChatService.sendMessage        @CustomNextSpan("agent.turn")     <- only the static + request tags
  ├─ IntentResolutionFacade.resolve     @TagCurrentSpan(intent.*)         <- was tagResolvedIntent
  ├─ ConversationLoop.run               @TagCurrentSpan(iterations)       <- was tagIterationsCount
  │    ├─ AssistantModelClient          @GenAiGeneration                  (unchanged)
  │    └─ IntentToolExecutor.execute    @CustomNextSpan + tool metric aspect
  │         └─ ToolInvoker              @GenAiToolExecution               <- was recordExecution
  └─ SessionStore.append
```

`AssistantChatService` then holds no `Tracer`, no `Span` and no metric field except the turn outcome (until turn-behaviour B3 moves it).

## 5. Decisions to confirm

| # | Decision | Options | Recommendation |
|:--|:--|:--|:--|
| D1 | Keep `@CustomNextSpan` or move to Micrometer `@Observed` / `Observation`? | **A.** Keep and add `@TagCurrentSpan`. **B.** Move to `Observation` + `ObservationConvention` (one declaration gives a span *and* a timer, and is the Micrometer standard, P1). B needs a result-aware convention, which `@Observed` alone can't express, so it means a hand-written convention class per annotated method | **A now.** The custom annotation exists because of result tags; B costs more per method than it saves. Revisit when the tool metric (O5c) makes us write a timer next to nearly every span |
| D2 | Outbound HTTP: `Propagator` helper or OTel `java-http-client` library? | **A.** `Propagator` + a hand-made CLIENT span. **B.** OTel `opentelemetry-java-http-client` wrapper | **B** if the artifact is managed by Boot 4.1's OTel BOM (to verify, first task of O4); otherwise A. B is the standard instrumentation and removes our own HTTP semconv code |
| D3 | `ConversationLoop` extraction: here or in `assistant-turn-behaviour.md` B2? | **A.** One shared slice. **B.** Two separate extractions | **A.** Same class, same seam. Whichever plan runs first owns it; the other starts from it |
| D4 | D-1: implement lifecycle events or delete the claims? | **A.** Implement (9 events on `agent.turn`). **B.** Delete the claims | **B**, unless someone reads these events. Spans already carry tool and model detail via GenAI spans; ADR-0013 itself weighed span explosion. If kept, limit to the 4 events that show a decision (`intent.resolved`, `tools.discovered`, `iteration.started`, `completed`) |
| D5 | Is `session_id` allowed as a log correlation field? | Yes / hashed / no | **Yes, local only.** It is already a span attribute and a log field today; it is not PII. `user_id` is the one to hash (O6d) |

## 6. Out of scope

- The turn-outcome metric and the `path` tag (F4): `assistant-turn-behaviour.md` B2/B3.
- Dashboards, alert rules and SLOs: `client-ai-model-stability.md` A6.
- Kafka / outbox tracing in `libs/polaris-outbox` (`HandOffTracing`, `W3cTraceContext`): not reviewed for this plan. Add a follow-up plan if the contract test (O1) is extended across the outbox.
- Core (`apps/polaris`) service logs: they use a `key=value` style already (`event=customer.link.rejected customer_id=…`). O6c's convention should be applied there as a later, separate change (one bounded context per change, P2.2).

## 7. Tests (AGENTS.md P3.2)

- O1 is the floor: the telemetry contract test for the happy path, a tool path, a denied path and a failed path.
- Every refactor slice (O3, O4, O5) must keep O1 green with no edits, except the slice that renames a tag.
- Aspect unit tests: `@TagCurrentSpan` with a result tag, a null result, a bad expression (logs, never throws), no tracer bean.
- O5a: a rolled-back transaction records no draft metric.
- O5c: a tool name outside the registry is counted as `other`, never creating a new series.
- O6: a test that logs inside a turn and asserts the JSON line has `traceId`, `spanId` and `session_id`, and that no log line in the tool path contains the argument values.

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | O1 | Telemetry contract test | `todo` | — | | | No production change |
| 2 | O2 | Fix defects and drift | `todo` | — | | | D-1 choice needs D4 |
| 3 | O3 | Tags and loop out of `AssistantChatService` | `todo` | — | | | Needs D3; shares the extraction with turn-behaviour B2 |
| 4 | O4 | Standard propagation + CLIENT span | `todo` | — | | | First task: D2 availability check |
| 5 | O5 | Domain and tool metrics out of the flow | `todo` | — | | | |
| 6 | O6 | Structured, correlated, redacted logs | `todo` | — | | | Check Loki pipeline first |
| 7 | O7 | Shared annotation module | `todo` | — | | | Only if a second consumer appears; likely `dropped` |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

## Change Log

| Date | Change |
|:--|:--|
| 2026-10-20 | Added findings, slices O1–O7, decisions D1–D5 and the tracker to the initial problem statement and approach |
