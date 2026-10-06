# Assistant Turn Behaviour

- **Scope:** `polaris-assistant`, how one chat turn (`POST /api/v1/assistant/chat`) ends: the happy path and every edge case (degraded, refused, failed)
- **Related:** `client-ai-model-stability.md` (A1–A6), PRD-003 (scenarios, FR-15), ADR-0015 (intents and policy)
- **Status:** Approved (2026-10-06): §5 decisions accepted as recommended; B5 stays gated on §5.1

## 1. Problem statement

Most turns should take the **happy path**: the intent is classified, the model answers (after zero or more tools), and the shopper gets a `200` with a reply and cards. The rest are edge cases: low confidence, TypeSafe or Gemini outage, tool failure, policy denial, iteration limit, time budget.

As the behaviour grows, three things get harder:

1. **Nobody can list the paths.** They are spread across `AssistantChatService` (the `while` loop and two `catch` blocks), `TypeSafeIntentClassifier` (fallback reason strings), `DefaultToolManager` / `HttpPolarisMcpClient` (errors turned into empty lists or error text), `ModelUnavailableExceptionHandler` and the shared `GlobalExceptionHandler`.
2. **Nobody can measure the split.** `polaris.assistant.turns{outcome}` has 5 values: `FAILED` covers five different failures, `FALLBACK` mixes "low confidence" with "TypeSafe down", and `ANSWERED` hides a failed tool and a failed tool discovery. Whether the happy path really is about 80 % is a guess today.
3. **Nothing makes a new edge case complete.** Adding one means editing the loop, the metrics, the persistence rule and the HTTP mapping by hand, and the compiler can't tell you one was missed.

The approach: **(1)** write down every path as a catalogue with one test each, **(2)** make the turn's result an explicit sealed type so that every path is handled in one place, **(3)** measure each path, **(4)** fix the paths that hide failures, **(5)** only then decide on a real degraded mode for a model outage.

## 2. Turn paths today

| # | Path | Class | Trigger | Response | Persisted | `outcome` today | Gap |
|:-:|:--|:--|:--|:--|:--|:--|:--|
| T1 | Direct answer | happy | Intent ≥ threshold; model returns text | `200` reply | user + reply | `answered` | ✅ |
| T2 | Answer after read tools | happy | Search, lookup, order status | `200` reply + `PRODUCT_LIST` card | + tool turns | `answered` | ✅ |
| T3 | Draft staged | happy | Write intent; `stage_order_draft` succeeds | `200` reply + `ORDER_DRAFT` card | + tool turns, draft row | `answered` | ✅ |
| T4 | Low confidence | degraded | Confidence < intent threshold | `200` as `general.conversation` (no tools) | as T1 | `fallback` | ✅ |
| T5 | Classifier unavailable | degraded | TypeSafe timeout, I/O, non-2xx, bad body, no key | Same as T4; reason only on the span | as T1 | `fallback` | ❌ Same metric as T4, so a TypeSafe outage looks like users typing vague messages |
| T6 | Tool outside the intent | degraded | Model proposes a tool the intent doesn't allow | Corrective tool turn; the loop goes on | + corrective turn | `answered` | ⚠️ Not visible in metrics |
| T7 | Tool fails | degraded | MCP 5xx, transport error, exception in a local tool | Error text goes back to the model, which replies | + error tool turn | `answered` | ⚠️ Partial failure not visible in metrics |
| T8 | Tool discovery fails | degraded | MCP `tools/list` fails or times out | **Tool list is silently empty.** The model answers a catalogue question with no tools | as T1 | `answered` | ❌ **Hidden failure** (same antipattern as stability R1): `200`, probably made-up product info |
| T9 | Iteration limit | degraded | Model still calls tools after 5 iterations | `200` *"I have completed processing your request."* | + tool turns | `iteration_limit` | ❌ The reply says the request is done when it isn't |
| T10 | Tool refused by Polaris Core | refused | MCP 401/403 | `200` *"Action denied: …"*, loop stops | + tool turns | `policy_denied` | ✅ Conversational reply (see §5.2) |
| T11 | Session of another user | refused | `loadHistory` owner check | `403` Problem Details | nothing | not counted | ✅ (refused before the turn starts) |
| T12 | Model unavailable, no tool ran | failed | Transport, 429, 5xx (A1) | `503` Problem Details + `Retry-After` | nothing | `failed` | ⚠️ `failed` mixes T12–T17 |
| T13 | Model unavailable after tools ran | failed | As T12, mid-loop (stability R5) | `503` | user + tool turns, no reply | `failed` | ⚠️ as T12 |
| T14 | Turn deadline used up | failed | `polaris.ai.turn-deadline` (A2) | `503` | as T12/T13 | `failed` | ⚠️ as T12 |
| T15 | Model rejects our request | failed | Gemini 4xx other than 429 (A3, in progress) | `500` Problem Details | as T12/T13 | `failed` | ⚠️ as T12 |
| T16 | Breaker open | failed | `gemini` breaker open (A3, in progress) | `503` straight away | nothing | `failed` | ⚠️ as T12 |
| T17 | Unexpected error | failed | Persistence, `ToolManager` contract violation, … | Shared `GlobalExceptionHandler` | depends | `failed` | ⚠️ as T12 |

T1–T3 are the happy path. T4–T9 still serve the turn but in a reduced way. T10–T11 are correct refusals. T12–T17 fail the turn.

## 3. Design choices

- **The catalogue is executable (BDD with Cucumber-JVM).** Every path in §2 is a Gherkin scenario in a `.feature` file, run by `cucumber-junit-platform-engine` + `cucumber-spring` as an ordinary `@SpringBootTest` under Surefire, with no separate test runner. The feature files **are** the catalogue, and the markdown doc only indexes them (class, why, open gaps), so the Given/When/Then is never written twice. Tags link behaviour, business and code: `@T8` (path ID), `@happy` / `@degraded` / `@refused` / `@failed` (class), `@PRD-003-S13` (PRD scenario), `@gap` (current behaviour that a later slice changes). `cucumber.plugin=html:…,json:…` produces a report per build listing every behaviour and whether it passes (AGENTS.md P1.3, *Traceable Integration Points*).
- **What the scenarios assert.** Gemini is not deterministic, so scenarios stub it **at the HTTP boundary** (WireMock, scripted `generateContent` replies) together with TypeSafe and the Polaris Core MCP (`FakeOrderManagementMcp`). They test the orchestration (what Polaris does with each model or tool outcome), not the quality of the model's wording. `Then` steps check only observable contracts: HTTP status, Problem Details `type`, `Retry-After`, card types, what was persisted, and the `outcome`/`path` metric. They never check reply prose, except fixed texts that Polaris itself writes (T9, T10).
- **BDD only for the major behaviour.** One scenario per turn path (T1–T17) and per PRD-003 scenario that the backend can observe. Unit-level rules (widget merging, fallback reason codes, `Retry-After` rounding) stay plain JUnit, because a Gherkin layer adds nothing there. A small shared step vocabulary (about 15 steps: *"Gemini replies with text …"*, *"Gemini is unreachable"*, *"Gemini calls tool X"*, *"MCP tools/list fails"*, *"the shopper sends …"*, *"the response is 503 with problem type …"*, *"the turn is counted as path …"*) keeps the glue code thin.
- **Sealed `TurnResult` + exhaustive `switch`** (Java 21) instead of a state-machine framework or a chain of handlers. The compiler refuses to build until a new variant is handled everywhere. No dependency is added, and it follows the existing `PlaceOrderOutcome` sealed type (`case PlaceOrderOutcome.Rejected rejected -> …`).
- **Additive metric change.** Keep `outcome` (the SLO rules and dashboards use it) and add one bounded `path` tag (AGENTS.md P1.4, *additive-first*).
- **Fix a path only once it's measured.** The behaviour changes (B4, B5) come after the measurement (B3), so each one is judged on data.

## 4. Proposed changes

| ID | Change | Fixes |
|:--|:--|:--|
| B1 | **Executable catalogue (BDD).** **First, a short check:** confirm that the current Cucumber-JVM release runs on JUnit 6 / Spring Framework 7 (Spring Boot 4.1). If it doesn't, fall back to JUnit tests named by ID (`@DisplayName("T8 …")`), keeping the same tags as JUnit `@Tag`s, and record that in the Change Log. **Then:** add the test-scoped dependencies (`cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine`, `junit-platform-suite`), one `@Suite` runner and one `@CucumberContextConfiguration` that reuses the existing Testcontainers + WireMock setup. Write `src/test/resources/features/turn/{happy,degraded,refused,failed}.feature` with one scenario per row in §2, **pinning today's behaviour, including the gaps** (T8, T9, tagged `@gap`). Move the existing `Assistant*IntegrationTest` assertions that cover a path into scenarios, and delete only the ones that are then fully duplicated. Write `assistant-turn-behaviour.md` as an index (ID → feature:scenario, class, decided in `file:line`, gap), linked from `agent-sequence.md` and `assistant-orchestrator.md`, with a short section *"Adding a path"*: scenario + `TurnResult` variant + index row in the same PR. **No production code changes** | G1 |
| B2 | **One decision point.** `executeConversationLoop` returns a sealed `TurnResult` (`Answered`, `PolicyDenied`, `IterationLimitReached`, `ModelUnavailable(toolsRan, retryAfter)`, …), with the classifier fallback reason carried on `ResolvedIntent`. A new `TurnResultHandler` (in `service/turn/`) maps each variant in one exhaustive `switch` to the response or exception, the persistence rule and the metric. `AssistantChatService` keeps only the happy-path pipeline: load → resolve → loop → handle. **Behaviour stays the same: the B1 tests pass unchanged** | G4 |
| B3 | **Measure each path.** Add the `path` tag to `polaris.assistant.turns{intent, outcome, path}` (bounded `TurnPath` enum, one value per catalogue row, registered at zero like today's series), the span tag `agent.turn.path`, and the same field in the structured log. Add a Grafana panel *"Turn paths: share of turns"* and a recording rule for the happy-path ratio (T1–T3 / all), **provisioned as code** in the existing dashboard | G2, and replaces the 80 % guess with data |
| B4 | **Stop hiding failures.** **T8:** tool discovery reports a failure instead of an empty list. If the resolved intent needs tools and none are available, the turn ends `503` Problem Details (`assistant-tools-unavailable`). `general.conversation` still goes ahead. **T9:** replace the "completed" text with an honest reply (*"I couldn't finish that. Could you rephrase or split the request?"*). Update the catalogue rows and turn B1's pinned tests into the new expected behaviour | G3 |
| B5 | **Standard Assistant Mode (PRD-003 FR-15), gated on §5.1.** While Gemini is unavailable (T12/T16, before any tool ran), a message that matches a fixed command grammar (`search <q>`, `stock <sku>`; then `order <sku> <qty>`, `cancel <order>`) runs through the **same** `IntentToolExecutor`, so intent and policy checks stay. It returns `200` with an additive `mode: "standard"` field. Any other message stays `503`, with the commands listed in `remedy`. New variant `StandardModeAnswered` and new row T18. Split it into B5a (read-only commands) and B5b (write commands, reusing `StageOrderDraftTool` so the confirm/cancel flow is unchanged) | G5 |

**Tests (AGENTS.md P3.2):** every slice keeps one scenario per path. B2 is guarded by B1's feature files passing **without any edit**. B3 adds a `Then the turn is counted as path "<path>"` step to every scenario, so each behaviour also checks its own telemetry. B4 removes `@gap` from T8/T9 and rewrites their `Then` steps to the new behaviour. B5 adds a `standard-mode.feature` tagged `@T18 @PRD-003-S13`. CI fails if a `.feature` file has an undefined step (`cucumber.execution.strict`, the default) or if an index row has no matching `@T<n>` tag (a small check in the B1 runner).

### Example (B1, T8 as it behaves today)

```gherkin
@degraded @T8 @gap
Scenario: Tool discovery fails, so the model answers without tools
  Given the shopper "alice" is signed in
  And Polaris Core MCP fails on "tools/list"
  And TypeSafe classifies the message as "catalog.product.search" with confidence 0.95
  And Gemini replies with text "We have several chargers."
  When the shopper sends "find chargers"
  Then the response status is 200
  And Gemini was offered no tools
  And the turn is counted with outcome "answered"
```

In B4 the same scenario loses `@gap`, and its `Then` steps become *the response is 503 with problem type "assistant-tools-unavailable"* and *Gemini was not called*.

**Out of scope (noted):** the `queryModel` null-fallback chain (`generateResponse` → `generateResponse` without context → `chat`) looks like legacy code. Check it in B2 and remove it in a separate change if it's dead.

## 5. Decisions to confirm

1. **FR-15 vs the stability plan's 503.** PRD-003 asks for a deterministic mode during an outage. The stability plan (§5.1–5.2) chose `503` and no fallback model. Recommended: **keep B5 gated**. Decide after B3 has run for a while: if few turns are hit by outages, the `503` is enough and FR-15 should be removed from the PRD. If not, do B5a (read-only) first.
2. **Policy denial as `200`.** T10 answers with a conversational `200 "Action denied: …"`. Recommended: **keep it**. The chat request itself succeeded, and the denial is the assistant's answer. Only the draft REST endpoints answer `403`.
3. **T8 behaviour.** Recommended: `503` for intents that need tools, and go ahead for `general.conversation`. The alternative is to answer with a fixed "I can't look that up right now" reply as a `200`.
4. **T9 behaviour.** Recommended: `200` with an honest reply, keeping `outcome=iteration_limit`.
5. **`path` vs `outcome`.** Recommended: add `path` and keep `outcome` (additive). Cardinality: about 10 intents × 5 outcomes × at most 18 paths, and most combinations never happen.
6. **BDD scope.** Recommended: Cucumber for the turn paths and for the PRD-003 scenarios the backend can observe (S4 idempotent confirm, S5 out-of-stock problem, S9 draft expiry, S12 IDOR, S13 standard mode). Not for UI-only scenarios (S2 disambiguation card rendering, S15 SSE), which belong in SPA tests, and not for unit-level rules. The alternative, JUnit `@DisplayName` + `@Tag` only, costs less but loses the readable feature files and the per-behaviour report.
7. **Order relative to the stability plan.** B1 can start now (docs and tests only). B2 waits for A3+A4 (they change `AssistantChatService` and add T15/T16). B3 waits for A6 (same dashboard). Then B4, then B5 if §5.1 says so.

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | B1 | Executable turn catalogue (Cucumber) | `in-progress` | — | | | Can start now; Cucumber/JUnit 6 check first |
| 2 | B2 | Sealed `TurnResult`, one decision point | `todo` | — | | | After stability A3+A4 |
| 3 | B3 | `path` metric, span tag, dashboard panel | `todo` | — | | | After stability A6 |
| 4 | B4 | Surface tool-discovery failure, honest iteration-limit reply | `todo` | — | | | |
| 5 | B5 | Standard Assistant Mode (FR-15) | `blocked` | — | | | Gated on §5.1 and B3 data |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| 2026-10-06 | Plan drafted | Turn behaviour is growing; the happy path and edge cases need to be explicit and measured | all |
| 2026-10-06 | Catalogue becomes executable Gherkin (Cucumber-JVM); tags link path ID, class and PRD scenario to the tests; §5.6 added | User asked for BDD with behaviour linked to executable tests | B1, B3, B4, B5 |
| 2026-10-06 | §5 decisions accepted as recommended; plan status Draft → Approved | User asked to apply the plan to Assistant | all |
