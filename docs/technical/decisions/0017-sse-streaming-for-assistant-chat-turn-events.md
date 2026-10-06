# ADR-0017: Server-Sent Events Streaming for Assistant Chat Turn Milestones

* **Status:** Proposed
* **Deciders:** Polaris Architecture Team, Core Platform Engineering, AI Agent Platform Lead
* **Date:** 2026-09-26
* **Technical Story:** Exposing the existing `AssistantChatService` conversational turn lifecycle (intent classification, per-iteration model reasoning, tool call execution, final reply) as a Server-Sent Events (SSE) stream on a new, additive endpoint, so client UIs can render incremental progress instead of waiting for the single synchronous JSON response.
* **Product Reference:** N/A — no PRD or Work Order exists yet for this change; this ADR precedes and will be decomposed into a Slice Work Order once a transport option is accepted.
* **Work Order Reference:** N/A

---

## 1. Context and Problem Statement

Polaris Assistant (`apps/polaris-assistant`) exposes exactly one client-facing conversational endpoint today: `POST /api/v1/assistant/chat` (`AssistantChatController`), which synchronously invokes `AssistantChatService.sendMessage(ChatMessageRequest, String)` and returns a single `ChatMessageResponse` only after the entire ReAct loop (intent resolution → up to `MAX_TOOL_ITERATIONS = 5` iterations of model reasoning and tool execution → final reply) has completed.

This has a well-known UX cost for LLM-backed chat: a turn that runs several ReAct iterations or slow MCP tool calls can take multiple seconds with zero visible progress. Streaming interim milestones (what the assistant is doing right now — classifying intent, reasoning, calling a tool) is standard practice for conversational AI UIs, and this repository already anticipated it:

* **[ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md)** — the ingress routing table accepted at that time already states: `assistant.polaris.local: Routes to polaris-assistant:8081 (Assistant Chat API, SSE streaming)`. SSE on this exact application and port was named as a target in the original architecture decision, before it was implemented. This ADR is the implementation of that already-accepted routing intent, not a new destination.
* **[ADR-0011](0011-gemini-model-call-distributed-tracing.md) / [ADR-0012](0012-mcp-cross-service-distributed-tracing.md)** — established the child spans `gemini.generate_content <model>` (model reasoning) and `mcp.tool_call <name>` (tool execution) as the server-side units of work per ReAct step.
* **[ADR-0013](0013-agent-turn-span-and-lifecycle-events.md)** — established the enclosing `agent.turn` span and the lifecycle-milestone vocabulary (`model.request`, `model.response`, `agent.tool.call`, `agent.tool.result`, etc.). Note: the current codebase has moved on from ADR-0013's manual `tracer.customNextSpan()` / `withSpan` construction to a declarative `@CustomNextSpan` AOP annotation (`observability/trace/CustomNextSpan.java` + `CustomNextSpanAspect.java`, standard Spring proxy-based AOP), applied to `AssistantChatService.sendMessage(...)`. ADR-0013 remains valid as the *conceptual* origin of the milestone names; it is cited here for terminology, not as the current implementation mechanism.
* **[ADR-0014](0014-agent-decision-events-and-observability-schema.md)** *(Superseded by ADR-0016)* — introduced the `DecisionEvent` / `intent.resolve` / `agent.tool.call` vocabulary that the client-facing event names in this ADR mirror.
* **[ADR-0015](0015-intent-management-and-policy-engine-architecture.md)** *(Accepted, current)* — defines `IntentResolver`, `IntentResolutionFacade.resolve(...)`, and the `intent.resolve` decision event with `agent.intent_id` / `agent.intent_confidence` tags. The "intent classified" SSE milestone in this ADR is a direct client-facing projection of that same server-side event.
* **[ADR-0016](0016-genai-observability-and-mcp-audit-standards.md)** *(Accepted, current)* — defines OTel GenAI semantic convention attributes on the model-reasoning span (`gen_ai.*`, `agent.iteration`, `agent.tools_offered_count`) and audit attributes on the tool-execution span (`gen_ai.tool.name`, `agent.tool.validation_result`, `agent.policy.decision`, etc.), and states the **strict rule that raw prompts, completions, and raw thought-signature blobs must never be exposed verbatim in any externally-observable channel**. This ADR carries that rule forward explicitly to the new SSE channel (see §4.4).

There is currently no way for a client to observe *any* of this per-iteration progress; the only visibility is the final `ChatMessageResponse`, or an operator inspecting distributed traces in Grafana Tempo.

**How should Polaris Assistant expose the existing chat turn's intent/reasoning/tool-execution milestones over SSE, additively, without altering the existing synchronous contract, and without violating ADR-0016's PII/prompt-leakage constraints or AGENTS.md's change-scope and standardization principles?**

---

## 2. Decision Drivers

* **Simplicity via Standardization ([AGENTS.md Principle 1](../../../AGENTS.md)):** SSE (`text/event-stream`) is itself a standard, HTTP/1.1-native mechanism (living alongside RFC 9110 HTTP semantics) — no bespoke framing, no new protocol, no WebSocket upgrade handshake is needed for a strictly server-to-client, single-request progress stream.
* **Minimal, Bounded, Vertically-Complete Change Scope ([AGENTS.md Principle 2](../../../AGENTS.md)):** The change must stay a single, additive vertical slice inside `apps/polaris-assistant` — controller → service → the existing `IntentResolutionFacade` / `AssistantModelClient` / `ToolManager` collaborators — with no speculative new module, no new deployable application, and no removal or modification of the existing `/chat` endpoint or its tests.
* **Observability by Default & Anti-God-Class Modularity ([AGENTS.md Principle 3](../../../AGENTS.md)):** The streaming seam must be a small, single-responsibility addition (a dedicated `event` package + a functional listener interface), not new branching logic bolted onto `AssistantChatService`'s existing orchestration body.
* **Zero PII / Prompt / Thought-Signature Leakage ([ADR-0016](0016-genai-observability-and-mcp-audit-standards.md)):** The SSE payloads are an *externally-observable channel* exactly like a trace backend or a log stream in ADR-0016's sense. The `ModelReasoning` event's `text` field may only ever carry the model's final, user-facing reply text — the same string already returned in the synchronous endpoint's `ChatMessageResponse.reply()` field today — never a raw intermediate prompt, a raw completion fragment mid-reasoning, or the opaque `thoughtSignature` blob.
* **100% Backwards Compatibility:** The existing `POST /api/v1/assistant/chat` endpoint, its request/response contract, and all existing `AssistantChatServiceTest` cases calling the 2-arg `sendMessage(ChatMessageRequest, String)` must continue to compile and pass completely unmodified.

---

## 3. Considered Options

### Option 1: Spring MVC + `SseEmitter`

Stay entirely inside `apps/polaris-assistant`'s existing servlet `DispatcherServlet`. The controller creates an `org.springframework.web.servlet.mvc.method.annotation.SseEmitter`, submits the blocking `AssistantChatService.sendMessage(request, userId, listener)` call to a dedicated `ThreadPoolTaskExecutor` bean, and the `AssistantTurnListener` lambda calls `emitter.send(SseEmitter.event().name(...).data(event, MediaType.APPLICATION_JSON))` imperatively for each milestone, wrapped in try/catch.

* **Pros:**
  - Zero new dependencies — `SseEmitter` ships in `spring-webmvc`, already on the classpath via `spring-boot-starter-webmvc`.
  - Very well-trodden Spring MVC pattern; easy for any Spring developer to read and maintain.
  - No question about `reactor-core` classpath availability.
* **Cons:**
  - More imperative, boilerplate-heavy error handling: every `emitter.send(...)` call needs its own try/catch (an `IOException` on a dropped client connection must not crash the executor thread), and `emitter.onTimeout(...)` / `emitter.onError(...)` / `emitter.onCompletion(...)` callbacks must be wired manually.
  - Thread-pool sizing and back-pressure are entirely the developer's responsibility; no built-in demand signaling if the client is a slow consumer.

### Option 2: Spring MVC + Reactor Return Type (`Flux<ServerSentEvent<AssistantTurnEvent>>`) — Recommended

Still the *same* servlet application, same `DispatcherServlet`, same `SecurityConfig` (`HttpSecurity`/`SecurityFilterChain`), same JPA/Redis/OAuth2 stack — **no WebFlux starter, no second embedded server.** Spring MVC has natively adapted `Flux`/`Mono` controller return values to async servlet responses since Spring Framework 5 (`ReactiveTypeHandler` inside `spring-webmvc`); this is a documented async-return-type adapter *within* the existing servlet stack, and is a distinct thing from adopting the WebFlux *web stack* (Netty + `DispatcherHandler` + `ServerHttpSecurity`). The implementation bridges the imperative `AssistantTurnListener` callback into a `Sinks.Many<ServerSentEvent<AssistantTurnEvent>>`, subscribes/emits on `Schedulers.boundedElastic()` (since `sendMessage(...)` remains a blocking call), and completes the sink on success or error.

* **Pros:**
  - Cleaner, composable, back-pressure-aware emission code; no manual per-send try/catch, no manual `onTimeout`/`onError`/`onCompletion` wiring — Reactor's `Sinks.Many` + `Flux` machinery handles cancellation (client disconnect) and completion signaling.
  - Stays inside the existing servlet stack: `libs/polaris-common`'s `SecurityConfig` and `GlobalExceptionHandler` keep working unmodified.
  - Idiomatic, forward-compatible style if more reactive-shaped endpoints are added later.
* **Cons:**
  - Needs `reactor-core` on the resolved classpath. **Open verification item:** a direct scan of `apps/polaris-assistant/pom.xml` shows no explicit `reactor-core` or `spring-boot-starter-webflux` dependency declared; whether it is present *transitively* (e.g. via `micrometer-tracing`, `spring-boot-starter-opentelemetry`, or test-scoped dependencies) was not confirmed in this session because `mvn dependency:tree` could not be run offline. Whoever implements this option must verify with `mvn -pl apps/polaris-assistant dependency:tree | grep reactor-core` and add the dependency explicitly if it is absent.
  - Slightly less familiar to developers who have not used Reactor before, even though no reactive rewrite of the rest of the app is implied.

### Option 3: Extract Shared Libs + Stand Up a Second, Dedicated Spring WebFlux Application

Pull the `ai`, `intent`, `tools`, `policy`, and `observability` packages out of `apps/polaris-assistant` into a new shared Maven module, then create a brand-new Spring Boot application built on `spring-boot-starter-webflux` (Netty, `DispatcherHandler`, reactive `SecurityWebFilterChain`) that exposes only the streaming endpoint.

* **Cons (this option is not recommended, and each con below is load-bearing to that conclusion):**
  - **Contradicts already-accepted routing:** ADR-0008's ingress table already names `assistant.polaris.local → polaris-assistant:8081` as *the* SSE streaming target. A second app means either carving out a new subdomain/port and updating the Nginx virtual-host configuration, or awkwardly reverse-proxying the streaming path through the existing app anyway — reintroducing the same indirection this option was meant to avoid.
  - **No reusable security/error-handling foundation:** `libs/polaris-common`'s `SecurityConfig` (`HttpSecurity`/`SecurityFilterChain`, JWT resource-server) and `GlobalExceptionHandler` (`@RestControllerAdvice` → RFC 7807 `ProblemDetail`) are both servlet-stack-only APIs. A WebFlux app cannot reuse them as-is; it would need a parallel reactive reimplementation (`ServerHttpSecurity`, `WebExceptionHandler`) — a second security and error-handling surface to keep in sync indefinitely.
  - **No actual non-blocking benefit today:** nothing downstream is non-blocking. `GeminiAiModelClient` calls Gemini via plain blocking `java.net.http.HttpClient` (`httpClient.send(...)`); `PolicyToolManager`'s MCP calls are blocking; intent/session state is JPA-backed or an in-memory `ConcurrentHashMap`. A reactive Netty event-loop server here would just wrap the exact same blocking calls in `Schedulers.boundedElastic()` — which Option 2 already achieves inside the existing app, with far less new surface area.
  - **Violates AGENTS.md Principle 2 (Change Scope):** a second deployable service means a second port, a second CI/build reactor entry, a second `Dockerfile`, and a second container health-check lifecycle — for zero verified concurrency benefit. This is a strictly larger blast radius than the problem calls for.
  - **Conclusion:** not recommended *now*. Revisit only if/when the team has an independent roadmap reason to make the assistant's I/O genuinely non-blocking end-to-end (e.g. a reactive Gemini client, reactive MCP transport) and/or a concrete need to scale this endpoint independently of the rest of `polaris-assistant`.

---

## 4. Decision Outcome & Detailed Technical Specification

**Chosen Option:** Option 2 — Spring MVC + Reactor Return Type (`Flux<ServerSentEvent<AssistantTurnEvent>>`) **(Recommended)**.

This ADR's overall **Status is "Proposed," not "Accepted."** The repository owner has not yet given final sign-off between Option 1 and Option 2. Both share an identical public HTTP contract (same endpoint path, same request DTO, same three SSE event types, same `done`/`error` framing) — the choice between them is a low-stakes, easily reversible *implementation* detail, since swapping the controller's internal emission mechanism from `SseEmitter` to `Flux` (or vice versa) requires no client-visible change and no change to `AssistantChatService`. **Option 1 remains an acceptable, more conservative fallback** if the team prefers to avoid any new `reactor-core` classpath reliance before it is verified. Ruling out Option 3 for now is the substantive, higher-stakes part of this decision, and that conclusion does not depend on the Option 1 vs. Option 2 choice.

### 4.1. Shared Extension Point: `vn.danang.polaris.assistant.event`

A new package, consumed identically by whichever transport option is ultimately implemented:

```java
package vn.danang.polaris.assistant.event;

/** Sealed root of all client-facing assistant turn milestone events. */
public sealed interface AssistantTurnEvent
        permits IntentClassified, ModelReasoning, ToolCallExecuted {
}

/** Emitted once, immediately after IntentResolutionFacade.resolve(...) returns. */
public record IntentClassified(
        String intentId,
        double confidence,
        boolean meetsThreshold,
        int toolsOffered
) implements AssistantTurnEvent {}

/** Emitted once per ReAct iteration, immediately after AssistantModelClient.generateResponse(...) returns. */
public record ModelReasoning(
        int iteration,
        String text,
        boolean hasToolCalls,
        int toolCallCount
) implements AssistantTurnEvent {}

/** Emitted once per ToolResult, immediately after ToolManager.handleToolCalls(...) returns each result. */
public record ToolCallExecuted(
        int iteration,
        String toolName,
        Map<String, Object> args,
        ToolResult.Status status,
        String result
) implements AssistantTurnEvent {}
```

```java
package vn.danang.polaris.assistant.event;

@FunctionalInterface
public interface AssistantTurnListener {
    AssistantTurnListener NOOP = event -> { /* no-op */ };

    void onEvent(AssistantTurnEvent event);
}
```

### 4.2. `AssistantChatService` — Backwards-Compatible Overload

A new public overload is added; the existing 2-arg method becomes a thin delegator:

```java
public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
    return sendMessage(request, userId, AssistantTurnListener.NOOP);
}
```

**AOP self-invocation caveat:** `@CustomNextSpan` is Spring proxy-based AOP (confirmed by `AssistantChatServiceTest`, which wraps the service under test with `org.springframework.aop.aspectj.annotation.AspectJProxyFactory`). A call from one method to another method *on the same bean* (`this.sendMessage(...)`) bypasses the Spring proxy entirely and would silently skip the aspect, so `agent.turn` would only be created for whichever overload happens to be the *external* entry point. To avoid this trap, `@CustomNextSpan(name = "agent.turn", ...)` must be applied to **both** public overloads, not just one delegating to the other, with the real orchestration logic factored into a private, unannotated helper that both call:

```java
@CustomNextSpan(name = "agent.turn", tags = { /* same tags as today */ })
public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
    return doSendMessage(request, userId, AssistantTurnListener.NOOP);
}

@CustomNextSpan(name = "agent.turn", tags = { /* same tags as today */ })
public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId, AssistantTurnListener listener) {
    return doSendMessage(request, userId, listener != null ? listener : AssistantTurnListener.NOOP);
}

private ChatMessageResponse doSendMessage(ChatMessageRequest request, String userId, AssistantTurnListener listener) {
    // existing orchestration body, unchanged, plus listener.onEvent(...) calls at the
    // three milestones below.
}
```

Milestone emission points inside `doSendMessage(...)` / `executeConversationLoop(...)` / `executeToolBatch(...)`:

1. Immediately after `intentResolutionFacade.resolve(messageText, history)` returns → `listener.onEvent(new IntentClassified(resolvedIntent.intentId(), resolvedIntent.confidence(), resolvedIntent.meetsThreshold(), resolvedIntent.acceptedTools().size()))`.
2. Immediately after each `queryModel(...)` call (i.e. `AssistantModelClient.generateResponse(...)`) returns, inside the ReAct while-loop → `listener.onEvent(new ModelReasoning(iterations, modelResponse.text(), modelResponse.hasToolCalls(), modelResponse.toolCalls() == null ? 0 : modelResponse.toolCalls().size()))`.
3. Immediately after `intentResolutionFacade.executeToolCalls(...)` (i.e. `ToolManager.handleToolCalls(...)`) returns, once per `ToolResult` in the returned list → `listener.onEvent(new ToolCallExecuted(iteration, result.toolCall().name(), result.toolCall().args(), result.status(), result.result()))`.

This preserves the existing orchestration flow byte-for-byte; the listener calls are pure side-effecting additions with no branching impact on `finalReply`/`iterations` semantics. Because `AssistantTurnListener.NOOP` is a no-op, the existing 2-arg overload's behavior, and every existing `AssistantChatServiceTest` case that calls it, is unaffected.

### 4.3. New Controller Endpoint

```java
@PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@Operation(summary = "Stream chat turn milestones over Server-Sent Events")
public Flux<ServerSentEvent<Object>> chatStream(
        @Valid @NotNull @RequestBody ChatMessageRequest request,
        Principal principal) {
    String userId = (principal != null) ? principal.getName() : "anonymous";
    Sinks.Many<ServerSentEvent<Object>> sink = Sinks.many().unicast().onBackpressureBuffer();

    Schedulers.boundedElastic().schedule(() -> {
        try {
            AssistantTurnListener listener = event -> sink.tryEmitNext(toServerSentEvent(event));
            ChatMessageResponse response = chatService.sendMessage(request, userId, listener);
            sink.tryEmitNext(ServerSentEvent.builder(response).event("done").build());
            sink.tryEmitComplete();
        } catch (Exception ex) {
            sink.tryEmitNext(ServerSentEvent.builder(Map.of("message", ex.getMessage()))
                    .event("error").build());
            sink.tryEmitComplete();
        }
    });

    return sink.asFlux();
}

private ServerSentEvent<Object> toServerSentEvent(AssistantTurnEvent event) {
    String name = switch (event) {
        case IntentClassified ignored -> "intent";
        case ModelReasoning ignored -> "reasoning";
        case ToolCallExecuted ignored -> "tool_call";
    };
    return ServerSentEvent.builder((Object) event).event(name).build();
}
```

The existing `/chat` endpoint, its `AssistantChatController` method, and its OpenAPI documentation are untouched. `ChatMessageRequest` validation (`@Valid @NotNull`, blank-message rejection) is identically enforced on the new endpoint since it is the same request DTO and the same `@RestControllerAdvice`-based `GlobalExceptionHandler` handles pre-stream validation failures with RFC 7807 `ProblemDetail` (a validation failure occurs before the `Flux` is subscribed, so it still surfaces as a normal `400`/`ProblemDetail` JSON response rather than an SSE `error` event).

### 4.4. SSE Event Name Mapping

| SSE `event:` name | Java type | Emitted when |
|---|---|---|
| `intent` | `IntentClassified` | Once, right after `IntentResolutionFacade.resolve(...)` returns |
| `reasoning` | `ModelReasoning` | Once per ReAct iteration, right after `AssistantModelClient.generateResponse(...)` returns |
| `tool_call` | `ToolCallExecuted` | Once per `ToolResult`, right after `ToolManager.handleToolCalls(...)` returns |
| `done` | `ChatMessageResponse` | Once, after the loop completes successfully — same payload shape as the synchronous `/chat` response |
| `error` | `{"message": string}` | Once, if `doSendMessage(...)` throws — stream then closes |

> [!CAUTION]
> **Restating the ADR-0016 omission rule for this new channel:** the SSE stream is an externally-observable channel exactly like a trace backend. `ModelReasoning.text` carries only the model's final, user-facing reply text — the same string already surfaced via the synchronous endpoint's `ChatMessageResponse.reply()` field — never a raw system/user prompt, a raw intermediate completion fragment, or the opaque `ModelResponse.thoughtSignature()` blob. No field of any of the three event records may carry a thought signature. `ToolCallExecuted.args` reuses `ToolCall.args()` as already returned to the model-facing loop today; it is not additionally sanitized by this ADR because it was not sanitized for the existing synchronous path either — if a future audit requires redaction here, that is a separate ADR against `ArgumentSanitizer` (ADR-0016 §4.3), not a change introduced by this one.

### 4.5. Alternative Fallback (Option 1)

If the team prefers to defer any new `reactor-core` classpath dependency, the identical `event` package, `AssistantTurnListener`, and `AssistantChatService` overload design above is reused unchanged; only `AssistantChatController.chatStream(...)`'s internals differ — it would instead create an `SseEmitter`, submit the blocking `sendMessage(request, userId, listener)` call to a `ThreadPoolTaskExecutor` bean, and have the listener call `emitter.send(SseEmitter.event().name(name).data(event, MediaType.APPLICATION_JSON))` per milestone, with `emitter.completeWithError(...)` / `emitter.complete()` replacing `sink.tryEmitNext`/`tryEmitComplete`. The public HTTP contract (path, request DTO, SSE event names and payload shapes) is identical either way.

---

## 5. Boundary & Interface Stability Analysis

| Criterion | Level | Justification |
|---|---|---|
| **B1: Boundary Enumeration** | Polaris Assistant Context | Strictly contained within `apps/polaris-assistant`, new subpackage `event` plus additive changes to `service` and `web`. No change to `apps/polaris`, `libs/polaris-common`, or any MCP/Core contract. |
| **B2: Stable vs Internal** | Explicitly Delineated | **Stable Contract (new):** `POST /api/v1/assistant/chat/stream` request/response shape, SSE event names (`intent`/`reasoning`/`tool_call`/`done`/`error`), and the `AssistantTurnEvent` record shapes. **Stable Contract (unchanged):** `POST /api/v1/assistant/chat` request/response shape. **Internal:** `AssistantTurnListener`'s wiring inside `doSendMessage(...)`, the choice of `SseEmitter` vs. `Flux`/`Sinks.Many` bridging (Option 1 vs. Option 2 is an internal implementation detail per §4.5). |
| **B3: Traceability** | Established | Governed by this ADR (ADR-0017); no PRD/WO exists yet — a Slice Work Order should be authored once Option 1 vs. Option 2 is finally accepted, before implementation merges. |
| **B4: Migration & Compatibility** | 100% Additive | New endpoint, new controller method, new service overload; existing 2-arg `sendMessage(...)` becomes a delegator to the 3-arg overload with `AssistantTurnListener.NOOP`, preserving identical behavior. Zero changes to existing method signatures, existing tests, `libs/polaris-common`, or `apps/polaris`. |
| **B5: Gate Enforcement** | TV Gate Defined | Sign-off requires: (1) `mvn clean test -pl apps/polaris-assistant` passing with existing `AssistantChatServiceTest` cases unmodified, (2) new listener/event-emission unit tests passing, (3) confirmation that no SSE payload field carries a raw prompt, raw completion fragment, or thought signature (per §4.4), (4) full reactor `mvn clean test` passing. |
| **B6: Downstream Impact** | Zero Disruption | No existing consumer of `/chat` is affected. The new `/chat/stream` endpoint has no existing consumers yet — this is a net-new capability, not a migration. |
| **B7: Reversibility / Blast Radius** | Immediate / Low Risk | The new endpoint, controller method, and service overload can be deleted outright with zero impact on `/chat` or any existing test, since the 2-arg `sendMessage(...)` only *delegates to* the 3-arg overload rather than the reverse. Rolling back is a pure subtraction, not a data or schema migration. |

---

## 6. Verification Plan

1. **Unit Verification (`apps/polaris-assistant`):**
   - `AssistantChatServiceTest`: add cases asserting the 3-arg `sendMessage(request, userId, listener)` invokes the listener exactly once with an `IntentClassified` event, once per ReAct iteration with a `ModelReasoning` event, and once per `ToolResult` with a `ToolCallExecuted` event, in milestone order, for (a) a zero-tool-call direct reply, (b) a multi-iteration tool-call turn, and (c) a policy-denied turn. Assert the existing 2-arg `sendMessage(request, userId)` test cases remain unmodified and passing (delegation to `AssistantTurnListener.NOOP` is behaviorally transparent). Assert `@CustomNextSpan`/`agent.turn` still fires when invoking either overload directly on an `AspectJProxyFactory`-woven instance (verifying the self-invocation fix in §4.2).
   - New `AssistantTurnListenerTest` / event record tests: verify `AssistantTurnListener.NOOP` is a true no-op, and that `IntentClassified`/`ModelReasoning`/`ToolCallExecuted` are exhaustively covered by the sealed `AssistantTurnEvent` `permits` clause (compiler-enforced).
2. **Controller/Web Verification:**
   - `AssistantChatControllerTest` (or a new `AssistantChatStreamControllerTest`): `MockMvc` async test (`.andExpect(request().asyncStarted())` / `MockMvcWebTestClient` or `WebTestClient` bound to the servlet context) against `POST /api/v1/assistant/chat/stream`, asserting `Content-Type: text/event-stream`, the ordered sequence of `event:` names (`intent`, one or more `reasoning`/`tool_call` pairs, `done`), and that a thrown exception from the service produces an `error` event followed by stream completion rather than a raw 500.
   - Assert the existing `/chat` endpoint's `MockMvc` tests are untouched and pass unmodified.
3. **Reactor Verification:**
   - `mvn clean test -pl apps/polaris-assistant`
   - Full monorepo reactor pass: `mvn clean test`
4. **Manual Smoke Test:**
   ```bash
   curl -N -X POST https://assistant.polaris.local/api/v1/assistant/chat/stream \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Accept: text/event-stream" \
     -d '{"sessionId":"smoke-test-1","message":"What is the status of order ORD-1001?"}'
   ```
   Expect, in order: an `event: intent` frame, one or more `event: reasoning` / `event: tool_call` frame pairs, a final `event: done` frame carrying the same `ChatMessageResponse` shape as `/chat`, then the connection closes.

---

## 7. Review Findings & Recommended Revisions (2026-09-26)

Reviewed against `AGENTS.md`'s three principles and verified against the current codebase (`AssistantChatController`, `AssistantChatService`, `CustomNextSpanAspect`, `pom.xml`, `ModelResponse`/`ToolCall`/`ToolResult`/`ResolvedIntent`). Every field/method reference in §4 checks out against the real types, and the AOP self-invocation analysis in §4.2 is correct. Two findings are significant enough to resolve before this ADR moves to "Accepted"; the rest are minor.

### 7.1 Distributed tracing silently breaks on the streaming path (significant)

`CustomNextSpanAspect` builds spans via `io.micrometer.tracing.Tracer` / `activeTracer.withSpan(span)`, a **ThreadLocal-based** current-span mechanism. Both transport options move the blocking `sendMessage(...)` call onto a separate thread before invoking it — Option 2 via `Schedulers.boundedElastic().schedule(...)` (§4.3), Option 1 via a `ThreadPoolTaskExecutor` submission (§4.5) — and neither snapshots/re-attaches the current trace context on that new thread. Confirmed `apps/polaris-assistant/pom.xml` has no `reactor-core`, no WebFlux starter, and no `io.micrometer:context-propagation`, so nothing bridges the two automatically; adding `reactor-core` alone (per the Option 2 "Open verification item" in §3) does not fix this either, since Spring Boot only auto-wires ThreadLocal↔Reactor-Context bridging for WebFlux request processing, not for a bare `Scheduler.schedule(Runnable)`.

**Effect:** every `/chat/stream` turn's `agent.turn` span (and its `gemini.generate_content`/`mcp.tool_call` children) becomes a brand-new, disconnected trace, and MDC-correlated logs lose their request correlation — a direct regression against `AGENTS.md` Principle 3.1 ("Distributed Tracing: Propagate standard W3C Trace Context... across all external entrypoints"), and against this ADR's own motivating context (ADR-0011/0012/0013). This is identical for both options — it is not a factor in choosing between them — but it must be an explicit open item with a fix (e.g., capture `tracer.currentSpan()`/trace context on the request thread and manually re-attach it inside the scheduled block) before implementation, not discovered after.

### 7.2 Option 2's stated Reactor benefits do not hold for this workload (significant)

§3's case for Option 2 credits Reactor with "composable, back-pressure-aware emission" and cancellation handling "Sinks.Many + Flux machinery handles." Against the concrete code in §4.3:

- The sink (`Sinks.many().unicast().onBackpressureBuffer()`) buffers **unboundedly** by default — no actual backpressure is applied, because the producer is still one imperative blocking call, not a composed Reactor pipeline.
- Nothing wires `doOnCancel`/`doFinally` to interrupt the blocking work on client disconnect; a dropped client still lets the scheduled task run to completion, including live Gemini/MCP side effects.
- Given `MAX_TOOL_ITERATIONS = 5`, a turn emits on the order of 10-20 SSE frames total — a small, single-producer/single-consumer stream, which is precisely the case where Reactor's actual strengths (demand signaling, operator composition, multi-source merging) don't come into play.
- The remaining justification — "idiomatic, forward-compatible... if more reactive-shaped endpoints are added later" (line 62) — is a speculative-generality argument that `AGENTS.md` Principle 2 explicitly warns against ("never design for hypothetical future requirements").

**Recommended revision:** flip the recommendation — make **Option 1 (`SseEmitter`) the primary choice**, not the fallback. It requires no new dependency (`reactor-core` is not currently on this module's classpath), uses the `MockMvc`-based testing idiom already standardized in this codebase, and doesn't advertise benefits the design doesn't realize. Demote Option 2 to "acceptable if a second, genuinely reactive-shaped endpoint is already on the near-term roadmap to amortize the new dependency and learning cost against" — otherwise it isn't justified for this workload.

### 7.3 Minor gaps

- **CloudEvents alignment (§2, §4.4):** `AGENTS.md` §1.2 calls for aligning event messaging with CloudEvents. The ADR invokes Principle 1 to justify SSE-as-transport but defines a fully bespoke event envelope (`intent`/`reasoning`/`tool_call`/`done`/`error`) without discussing CloudEvents. No prior CloudEvents usage exists elsewhere in the repo, so this is likely a non-issue in practice, but the ADR should say so explicitly rather than citing Principle 1 selectively.
- **`error` event payload shape (§4.4, line 219):** `{"message": string}` departs from this codebase's otherwise consistent RFC 7807 `ProblemDetail` convention (confirmed in `GlobalExceptionHandler`), which `AGENTS.md` §1.1 mandates for API errors. There's a legitimate reason (HTTP headers/status are already committed once streaming starts), but the ADR should state that reasoning explicitly rather than leaving the deviation unremarked.
- **Prose/code type mismatch (§3 line 57 vs §4.3):** §3 types the sink as `Sinks.Many<ServerSentEvent<AssistantTurnEvent>>`; the actual §4.3 code correctly uses `Object` (required since `done`/`error` payloads aren't part of the sealed `AssistantTurnEvent` hierarchy). Cosmetic only — fix the prose reference.

### 7.4 Outcome

Status remains **Proposed**. Before this can move to Accepted: (1) flip §4's chosen option to Option 1 per §7.2, keeping Option 2 as the documented fallback instead of the reverse; (2) add an explicit trace-context-propagation fix to §4.2/§4.3 per §7.1, with a corresponding verification-plan item in §6; (3) add one sentence each addressing the CloudEvents and RFC 7807 deviations per §7.3.
