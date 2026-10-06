# Client AI Model Stability

- **Scope:** `polaris-assistant`, its outbound calls to Gemini (`GeminiAiModelClient`) and TypeSafe (`TypeSafeIntentClassifier`), and what the chat API and readiness probe do when those calls fail
- **Reference:** Michael T. Nygard, *Release It!*, 2nd ed.: stability antipatterns and stability patterns
- **Status:** Approved (2026-10-06): §5 decisions accepted as recommended

## 1. Problem statement

Polaris Assistant depends on two AI models:

- **TypeSafe** (Jev, `POST /v1/systemone`): classifies the intent of each turn, which decides which tools the turn may use
- **Gemini** (`generateContent`): generates the reply and the tool calls, up to 5 times per turn

If either model is down or can't be reached, Polaris Assistant can't work properly. Worse, today it **hides** the failure: a Gemini outage comes back as `200 OK` with an error sentence as the reply.

Following the approach in `messaging-stability.md`: list the known failures, classify each one as a *Release It!* antipattern, pick a standard stability pattern for each, and accept the remaining risk explicitly.

## 2. Call flow per chat turn

| Step | Call | Timeout today | On failure today |
|:--|:--|:--|:--|
| 1 | TypeSafe classify | 10 s connect, 15 s request | Falls back to `general.conversation`, which has read-only tools. The turn goes on ✅ |
| 2..6 | Gemini `generateContent`, up to `MAX_TOOL_ITERATIONS = 5` | 10 s connect, 30 s request, per call | Returns `ModelResponse("Unable to get response from AI Model (…)")`, which is **sent as a 200 reply and persisted to history** |
| probe | `GeminiHealthIndicator` (GET model metadata), `TypeSafeHealthIndicator` (HEAD base URL) | 2 s | Readiness DOWN → **the pod is removed from load balancing** |

**Worst-case turn:** 15 s + 5 × 30 s = **165 s**, against a chat latency SLO of 30 s (`assistant-slo.rules.yml`).

## 3. Failure modes

| # | Failure | Antipattern | Today | Gap |
|:-:|:--|:--|:--|:--|
| R1 | Gemini unreachable (DNS, connection refused, TLS) | Integration point | `200 OK` with *"Failed to communicate with AI Model: \<exception message\>"* | ❌ **200 with an error payload** (violates AGENTS.md P1.1). ❌ The exception text is shown to the user. ❌ The error sentence is persisted and sent back to Gemini as a model turn. ❌ The availability SLO doesn't see it |
| R2 | Gemini slow or hanging | Slow responses, blocked threads | Waits 30 s per call, up to 5 calls | ❌ The turn can take far longer than the 30 s SLO. The client or gateway has already given up while the server keeps working |
| R3 | Gemini 429 (quota or rate limit) | Self-denial, dogpile | Same as R1. `Retry-After` is ignored | ❌ Every user keeps hitting an exhausted quota. There's no backoff |
| R4 | Gemini transient 5xx or reset | Integration point | Same as R1. No retry | ❌ One short glitch fails the turn |
| R5 | Gemini fails mid-loop after a tool already ran (e.g. a draft was staged) | Integration point | Error sentence as the reply. Cards are still returned. Tool turns are persisted | ⚠️ The draft exists but the reply says "unable to get response". The R1 fix must not lose the executed tool turns |
| R6 | TypeSafe down or slow | Integration point, slow responses | Falls back to `general.conversation` after up to **15 s** | ✅ Degrades correctly. ❌ Waits for the full timeout on every turn instead of failing fast |
| R7 | TypeSafe 200 with a bad body | Integration point | `missing_answer` / `blank_choice` fallback | ✅ |
| R8 | Either model down → readiness DOWN on **every** pod | Cascading failure | `readiness.include: …,gemini,typeSafe,…` | ❌ The draft `confirm`/`cancel` endpoints don't use a model, but they go down too. ❌ A TypeSafe outage removes the service even though a fallback exists. ❌ The ingress returns its own 503 without Problem Details |
| R9 | Gemini API key `null` | Misconfiguration | `IllegalArgumentException` on each request; the shared `GlobalExceptionHandler` maps it to **400 Bad Request** and echoes the config message (*"Live AI Model key is not configured…"*) | ❌ Wrong status (blames the client) and leaks config details. ⚠️ Better to fail at startup (out of scope here, noted) |

**Root cause of R1–R5:** the model client turns *every* failure into normal reply text, so the service, the API and the SLO all treat it as a success. **Root cause of R2, R6 and R8:** nothing remembers that a dependency is failing, so every request pays the full timeout again, and readiness treats a degraded dependency as a dead pod.

## 4. Proposed changes

Use **Resilience4j** (`resilience4j-spring-boot3` + `resilience4j-micrometer`), the standard Spring library for these named patterns (AGENTS.md P1.2), instead of hand-written retry or breaker code.

| ID | Change | Pattern | Fixes |
|:--|:--|:--|:--|
| A1 | **Fail loudly.** Gemini failures throw a typed `ModelUnavailableException` (carrying the cause and any `Retry-After`) instead of returning reply text. A `@RestControllerAdvice` maps it to **`503` + `application/problem+json` + `Retry-After`**. The error text is never persisted. Tool turns that already ran are persisted before the error is raised (R5) | Fail fast, standard errors (RFC 9110 / 7807) | R1, R3, R4, R5 |
| A2 | **Time budget per turn.** A turn has a deadline (e.g. 25 s, below the 30 s SLO). Each model call gets `min(per-call timeout, remaining budget)`. Lower the TypeSafe timeout (e.g. 3 s, since classification is a small call). When the budget runs out, raise A1's 503 | Timeouts | R2, R6 |
| A3 | **Circuit breaker per provider.** `gemini` breaker open → 503 straight away, with `Retry-After` = time until the breaker half-opens. `typeSafe` breaker open → go straight to the existing `general.conversation` fallback with reason `circuit_open`. Only transport errors, 429, 5xx and timeouts count as failures. A 4xx caused by our own request does not. Change the HTTP mapping for those Gemini 4xx (400/401/403, i.e. anything but 429) from A1's 503 to **500** Problem Details without `Retry-After`, since they are our defect, not an outage | Circuit breaker | R2, R3, R6 |
| A4 | **Bounded retry inside the breaker.** Gemini only (TypeSafe has a fallback). Retry on connection errors, 5xx and 429, at most 2 attempts, with exponential backoff and jitter. Respect `Retry-After`, and never retry past A2's deadline | Retry with backoff | R4 |
| A5 | **Decouple readiness.** Remove `gemini` and `typeSafe` from the readiness group. Keep them on `/actuator/health` as informational contributors, together with each breaker's state. Readiness then reflects only what this pod can do (process, DB, MCP) | Let it degrade, don't take the pod out | R8 |
| A6 | **Transparency.** Export breaker state, call and retry metrics (`resilience4j_*`). Add an alert on a breaker staying open for more than N minutes. Add a panel to the provisioned Grafana dashboard (as code). Write a runbook entry "Gemini or TypeSafe outage: what users see, what to check" | Steady state, transparency | all |

**Tests (AGENTS.md P3.2):** WireMock fault injection, using the existing `GeminiAiModelClientWireMockTest` style:

- connection reset, a 503 then a 200 (one retry succeeds), a 429 with `Retry-After`, and a delayed response beyond the budget
- the breaker opens after N failures, and the next call fails in under 50 ms without an HTTP request
- `/chat` returns a 503 Problem Details with `Retry-After`, and the history holds no error text
- the TypeSafe breaker is open and the turn completes as `general.conversation` without waiting
- readiness stays UP while Gemini is DOWN, and draft confirm/cancel still work
- the trace span records the exception and the breaker state

## 5. Decisions to confirm

1. **What users see while Gemini is down.** Recommended: a 503 Problem Details with `Retry-After`, and the SPA shows a friendly "assistant temporarily unavailable" message. The alternative, a canned `200` reply, would repeat today's antipattern.
2. **Fallback model.** Should a second model be added, e.g. a lighter Gemini model or another provider? Recommended: **not in this plan**. A second Gemini model shares the provider's outages and quota, and a second provider is a new integration (prompts, tool schema, thought signatures, cost). Revisit after A1–A6 are in place.
3. **R5 response.** When Gemini fails after a write tool succeeded, should the response be a 503 (draft persisted; the SPA can re-fetch the draft), or a `200` with the cards and a fixed *"I staged your draft but couldn't finish replying"* message? Recommended: 503, to keep one simple rule.
4. **Numbers to start with.** Turn deadline 25 s. TypeSafe timeout 3 s. Gemini breaker: 50 % failure rate over the last 20 calls, 30 s open. Retry: 2 attempts, starting at 500 ms.
5. **Bulkhead** (a cap on concurrent Gemini calls per pod). Virtual threads make waiting threads cheap, but the HTTP connections and the Gemini quota are still limited. Add it now, or defer until there's load data?
6. **Slicing.** A1 → A5 → A2 → A3+A4 → A6. A1 and A5 are small and fix the most harmful behaviour. A3 and A4 ship together because the retry must sit inside the breaker.

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | A1 | Fail loudly: 503 Problem Details | `done` | — | `client-ai-stability-a1-fail-loudly` | [#47](https://github.com/vungdv/hometask1/pull/47) | |
| 2 | A5 | Decouple readiness from model health | `done` | — | `client-ai-stability-a5-readiness` | [#48](https://github.com/vungdv/hometask1/pull/48) | |
| 3 | A2 | Time budget per turn | `done` | — | `client-ai-stability-a2-time-budget` | [#49](https://github.com/vungdv/hometask1/pull/49) | |
| 4 | A3+A4 | Circuit breakers and bounded retry | `approved` | — | `client-ai-stability-a3a4-breaker-retry` | [#50](https://github.com/vungdv/hometask1/pull/50) | |
| 5 | A6 | Metrics, alert, dashboard, runbook | `todo` | — | | | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| 2026-10-06 | §5 decisions accepted as recommended; plan status Draft → Approved | User confirmed the recommendations before execution | all |
| 2026-10-06 | §4 A3: Gemini 4xx other than 429 map to 500 (no `Retry-After`) instead of 503. §3 R9: corrected to the actual 400 + leaked config message | Proposed in the A1 review (PR #47), approved by the user | A3+A4 |
