# Qwen Model Client

- **Scope:** `polaris-assistant`, add Qwen provider for `AssistantModelClient` reasoning model.
- **Reference:** `GeminiAiModelClient`
- **Status:** Draft, in review (spike results in §8; §7 decisions are Proposed until a reviewer signs off)

## 1. Problem statement

Polaris Assistant depends on Gemini for reasoning. It is a critical integration point and carries two risks:
- A single point of failure
- Cost

So we propose adding a second reasoning option: Qwen served by Ollama.

### Success criteria (definition of done)

1. With `polaris.ai.provider=qwen` and `QWEN_BASE_URL` pointing at a local Ollama (`qwen3:4b`), a chat turn that needs no tool returns a reply, and a turn that needs a tool completes the tool round trip (call, tool result, final text) through `AssistantChatService`, with no code change beyond the S0a/S0b/S1 wiring and the new provider.
2. The `assistant-bdd.md` scenario subset covering chat and a single tool call passes against `qwen` (the full suite is not required; see §7.3).
3. With `provider=gemini` (default) every existing test passes unchanged apart from imports and config keys, and existing ConfigMaps (env var names) keep working.
4. A provider failure surfaces as the same 503 as Gemini (X1) and `/actuator/health` names the selected provider.

## 2. Design principle: one protocol client, not a "Qwen" protocol

Qwen has no wire protocol of its own. It is a **model family** served over the de-facto standard **OpenAI Chat Completions** API (AGENTS.md P1: align with the standard lower layer, not a bespoke abstraction).

So the class is `OpenAiCompatibleModelClient`, selected by `polaris.ai.provider=qwen`, with Qwen as the default model name. Ollama's OpenAI-compatible endpoint is **verified** by the spike (§8). Hosted DashScope is expected to speak the same API but is **unverified**; it is not a goal of this plan (§5).

`AssistantChatService` keeps depending on the `AssistantModelClient` contract only. A `@Primary` `DelegatingModelClient` implements the same contract, reads **only** `polaris.ai.provider` (`ModelProviderProperties`) once at startup, and delegates every call to the selected provider client. Each provider client owns its complete configuration (`GeminiProperties`, `QwenProperties`); providers never know about each other and the shared `AssistantAiProperties` goes away. Flow of one call: `AssistantChatService` → `DelegatingModelClient` → selected provider client (wrapped by its circuit breaker, X3) → `ModelResponse`. Failures surface as `ModelUnavailableException` (X1), so the 503 mapping does not care which provider ran.

## 3. Main requirements (functional)

The contract is the existing `AssistantModelClient` interface; the new client is a drop-in for Gemini. The wire format is the public [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat/create) (`POST {base-url}/chat/completions`, `stream=false`), not restated here. **`base-url` includes the API version path**, e.g. `http://localhost:11434/v1` for Ollama, so the client appends only `/chat/completions` and `/models`. This keeps the client provider-agnostic: a server whose version prefix differs (DashScope's is unverified, see §8) needs only a different `base-url`.

Each F item has acceptance scenarios (given/when/then) that map directly to tests. Scenarios use the real captures in §8 where one exists (F1.1, F1.2, F1.4, F1.7) and the clearly marked `synthetic-*` fixtures otherwise (F1.3, F1.5, F1.6).

### F1. Qwen behaviours beyond the spec

(a) `tool_calls[].function.arguments` is a JSON **string**: parse to `Map`; malformed gives empty args and a `warn`, not a failure. (b) Reasoning output is never returned in `text`: the `reasoning` message field is ignored, and an inline `<think>…</think>` block in `content` (not observed on Ollama, see §8) is also stripped (§7.5). (c) A tool call without an `id` gets a stable generated one so the `tool` message can carry `tool_call_id` (not observed on Ollama, which returned `call_…` ids; kept as defence, §8). (d) No `thoughtSignature`.

| Scenario | Given | When | Then |
|:--|:--|:--|:--|
| F1.1 text reply | server returns `content: "Hi…"`, `finish_reason: stop` | `generateResponse` | `ModelResponse.text` is that content, no tool calls |
| F1.2 tool call | server returns one `tool_calls` entry with `arguments: "{\"city\":\"Hanoi\"}"` | `generateResponse` | one tool call, name `get_weather`, args `Map{city=Hanoi}` |
| F1.3 malformed args | `arguments: "{not json"` | `generateResponse` | tool call with empty args, a `warn` log, no exception |
| F1.4 reasoning field | message has `content: "Hi"` and a long `reasoning` | `generateResponse` | `text` is `"Hi"`; reasoning is absent from text, logs and telemetry |
| F1.5 inline think | `content: "<think>x</think>Hi"` | `generateResponse` | `text` is `"Hi"` |
| F1.6 missing id | tool call has no `id` | `generateResponse`, then the caller sends a tool result | the call has a non-blank generated id, and the next request's `tool` message carries the same `tool_call_id` |
| F1.7 tool round trip | history has an assistant tool call and a tool result | `generateResponse` | request body has the `tool` message with `tool_call_id`; reply text is returned |

### F2. Provider selection

`DelegatingModelClient` delegates to the client named by `polaris.ai.provider` (`gemini` default, `qwen`). Unknown value, or `qwen` without `base-url`, fails startup. One provider per deployment; no runtime failover.

| Scenario | Given | When | Then |
|:--|:--|:--|:--|
| F2.1 default | `provider` unset | context starts, a call is made | `GeminiAiModelClient` handles it |
| F2.2 qwen | `provider=qwen`, `base-url` set | a call is made | `OpenAiCompatibleModelClient` handles it, Gemini is not called |
| F2.3 unknown | `provider=foo` | context starts | startup fails naming the allowed values |
| F2.4 missing url | `provider=qwen`, blank `base-url` | context starts | startup fails naming `QWEN_BASE_URL` |

### Config (`application.yml`)

Each provider has its own block, so defaults never leak between providers. Existing env var names are kept for Gemini, so current ConfigMaps keep working.

```yaml
polaris:
  ai:
    provider: ${POLARIS_AI_PROVIDER:gemini}      # gemini | qwen
    gemini:                                      # was polaris.ai.*
      api-key: ${GEMINI_API_KEY:${POLARIS_AI_API_KEY:}}
      model: ${POLARIS_AI_MODEL:gemini-3.6-flash}
      base-url: ${POLARIS_AI_BASE_URL:https://generativelanguage.googleapis.com}
      timeout-seconds: ${POLARIS_AI_TIMEOUT_SECONDS:30}
      turn-deadline: ${POLARIS_AI_TURN_DEADLINE:25s}
      # system-prompt: default in GeminiProperties
    qwen:
      api-key: ${QWEN_API_KEY:}                  # blank is valid (Ollama)
      model: ${QWEN_MODEL:qwen3:4b}              # verified on Ollama; hosted model names unverified
      base-url: ${QWEN_BASE_URL:}                # required when provider=qwen, includes version path: http://localhost:11434/v1
      timeout-seconds: ${QWEN_TIMEOUT_SECONDS:60}  # local small models are slower; final value set in S1 (§7.9)
      turn-deadline: ${QWEN_TURN_DEADLINE:25s}     # to be raised in S1 (§7.9)
      # system-prompt: default in QwenProperties (duplicated from Gemini on purpose, see §7.8)
```

## 4. Cross-cutting concerns

Observability, tests, structure, resilience and config follow AGENTS.md P1 and P3; they are not restated. **A slice is done only when its functional items (§3) pass and the X items below that its code touches are met.** Only provider-specific points are listed.

| ID | Provider-specific requirement |
|:--|:--|
| X1 | Failure contract identical to Gemini: transport error, timeout or non-2xx gives `ModelUnavailableException` with a failed `ModelCall` and any `Retry-After`. Failure text is never returned or persisted. |
| X2 | Per-call timeout is the provider's `timeout-seconds`, capped by `ModelRequestContext.deadline()`. Exhausted deadline gives `TurnDeadlineExceededException` inside `ModelUnavailableException` with no HTTP request made. |
| X3 | Each provider client is wrapped by its own named Resilience4j breaker (`gemini`, `qwen`) with retry inside it, beneath `DelegatingModelClient` (§7.6), using the Gemini retryable/non-retryable classification (`ModelFailures`). |
| X4 | `@GenAiGeneration(provider = PROVIDER_QWEN)` fills `ModelCall`: `requestModel`, `responseModel` (`model`), `responseId` (`id`), `usage` (`prompt_tokens`/`completion_tokens`), `finishReason` (`stop`/`tool_calls`/`length`). `reasoning` text is never captured. |
| X5 | Env vars `QWEN_API_KEY`, `QWEN_BASE_URL`, `QWEN_MODEL`; the key is never logged. |
| X6 | WireMock tests through the real HTTP interface (as `GeminiAiModelClientWireMockTest`), using the §8 fixtures as stub bodies where they exist. |
| X7 | Mapper, parser and transport client are separate units; `GeminiAiModelClient` stays unchanged. |
| X8 | `Authorization: Bearer {api-key}` only when the key is non-blank. A blank key is valid for Qwen, unlike Gemini's local-echo fallback. Ollama accepted both with and without the header (§8). |
| X9 | `/actuator/health` shows the selected provider as an informational contributor and is not part of readiness (readiness would make a local Ollama outage restart the pod). Probe: `GET {base-url}/models` within the provider's timeout (verified on Ollama, §8). |

## 5. Out of scope

- Streaming (`stream=true`). The service uses non-streaming calls today.
- Automatic Gemini ↔ Qwen failover (see §7.2).
- Hosted DashScope support: only Ollama is verified. Adding it is a separate change once its endpoint and auth are checked (§8).
- Changing intent classification (TypeSafe stays as is).
- K8s manifests and Grafana panels for `qwen` (follow-up slice after the client lands).

## 6. Slices

Every slice also satisfies §4. The table lists only what is **specific** to each slice.

| Slice | Title | Functional | Cross-cutting focus | Slice-specific work and tests |
|:--|:--|:--|:--|:--|
| S0a | **Properties and config move** | none | X5 | `AssistantAiProperties` → `GeminiProperties` (`polaris.ai.gemini.*`) plus `ModelProviderProperties`. Env var names unchanged, so ConfigMaps are unaffected; grep and update every direct `polaris.ai.*` key (test yml, `-D` overrides, docs). Existing suite stays green with only imports and keys changed. |
| S0b | **Delegator and resilience generalisation** | none | X3, X7 | `DelegatingModelClient` delegating to Gemini only; `ResilientGeminiModelClient` → `ResilientModelClient(provider)` (§7.6); `Duration turnDeadline()` on the contract (§7.7). Existing suite green; tests: delegation, `turnDeadline()` forwarded, Gemini breaker behaviour unchanged. |
| S1 | **Qwen client and selection** | F1, F2 | X1, X2, X4, X7, X8 | `OpenAiCompatibleModelClient` with mapper and parser; `QwenProperties`; `qwen` registered in `DelegatingModelClient` with startup validation. Tests: F1.1-F1.7 and F2.1-F2.4 on the §8 fixtures; Bearer header only with a key; default `systemPrompt` of `QwenProperties` equals `GeminiProperties` (§7.8). Between S1 and S2, `provider=qwen` has no breaker and is dev-only; the default stays `gemini`. **Decision gate: §7.9 must be settled before S1 merges.** |
| S2 | **Qwen resilience** | none | X3 | Breaker `qwen` with retry via `ResilientModelClient`; runbook line for `scripts/ollama.sh`. Tests: breaker opens and the next call fails fast without HTTP; retry 503 → 200; no retry on 400. |
| S3 | **Qwen health** | none | X9 | Health indicator for the selected provider. Tests: UP/DOWN against WireMock `/models`; not in readiness group. |

**Order:** S0a → S0b → S1 → S2 → S3. S1 is the smallest reachable vertical slice for Qwen; S2 and S3 harden it and can each be a small PR.

## 7. Decisions

Status: **Proposed**, pending reviewer sign-off.

1. **Client name and shape.** A single `OpenAiCompatibleModelClient`; Qwen is configuration.
2. **Failover.** None in this plan; one provider per deployment, chosen by config.
3. **Tool-calling quality on small models.** `qwen3:4b` on 8 GB can call tools (spike: single call and round trip worked) but multi-step order flows are untested. `qwen` is dev-only until the BDD suite (`assistant-bdd.md`) passes against it.
4. **PR granularity.** S0a, S0b, S1, S2, S3 as separate PRs (replaces the earlier "S1+S2 together"; reviewability over bundling, per review). S1 includes selection wiring so it is reachable.
5. **Reasoning handling.** Drop it. Ollama returns it in a separate `reasoning` field (§8), which the parser ignores; an inline `<think>` block is also stripped defensively. Not kept in telemetry (privacy under `content-capture`).
6. **Where the circuit breaker sits.** Generalise `ResilientGeminiModelClient` into one `ResilientModelClient(provider)` decorator wrapping each provider client, so `DelegatingModelClient` delegates to an already-guarded client. Gemini behaviour is unchanged. Part of S0b.
7. **Who reads `turnDeadline`.** Configured per provider. `AssistantModelClient` gains `Duration turnDeadline()`; each provider returns its own, `DelegatingModelClient` forwards to the selected one, and `AssistantChatService` asks the contract instead of reading properties (`AssistantChatService.java:91`). Part of S0b.
8. **`systemPrompt` duplicated per provider.** Trade-off: each provider can be tuned independently (Qwen small models may need a different prompt) and providers stay decoupled, at the cost of silent drift when one default is edited. Mitigation: a test in S1 asserts the two defaults are equal; a deliberate divergence must change that test and say why.

9. **Reasoning tokens vs the 25s turn deadline.** The spike saw ~3,300 reasoning tokens for a trivial prompt (§8), which can exhaust `QWEN_TURN_DEADLINE` on small hardware. Gate for S1: pick one of these (or combine) before merge, recorded here:
   - (a) disable thinking through a request option, if Ollama's OpenAI-compatible endpoint supports one (to be checked in the spike, not yet run);
   - (b) a larger default `QWEN_TURN_DEADLINE` and `timeout-seconds` for Qwen only;
   - (c) a smaller or non-thinking model (`qwen2.5:3b`, per `scripts/ollama.sh`).

   **Decided 2026-10-18: (b).** Qwen gets its own larger default `QWEN_TURN_DEADLINE` and `timeout-seconds`; the Gemini defaults are unchanged. The S1 worker picks the values from measured `qwen3:4b` latency on the spike hardware and states them in the PR for review. (a) and (c) are not pursued in this plan.

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | S0a | Properties and config move | done | | feat/qwen-s0a-properties | [#22](https://github.com/vungdv/polaris-assistant/pull/22) | Merged despite red smoke test (already red on main) |
| 2 | S0b | Delegator and resilience generalisation | done | | feat/qwen-s0b-delegator | [#23](https://github.com/vungdv/polaris-assistant/pull/23) | Merged; smoke test red with the same 5 failures as main |
| 3 | S1 | Qwen client and selection | done | §7.9 decided (b) on 2026-10-18; met | feat/qwen-s1-client | [#25](https://github.com/vungdv/polaris-assistant/pull/25) | |
| 4 | S2 | Qwen resilience | done | | feat/qwen-s2-resilience | [#26](https://github.com/vungdv/polaris-assistant/pull/26) | |
| 5 | S3 | Qwen health | in-progress | | | | |

## 8. Spike results

Run 2026-10-17 against local Ollama (`scripts/ollama.sh`, model `qwen3:4b`, server at `localhost:11434`), non-streaming. Fixtures are in [`qwen-model-client-fixtures/`](qwen-model-client-fixtures/). The `reasoning` strings in the response fixtures are truncated (marked in the file); nothing else is edited. `tool-result.request.json` is **hand-assembled** from the real `tool-call` exchange (assistant `tool_calls` with `id` and `type`, without the non-standard `index` Ollama adds, plus a `tool` message), then sent for real; only its response is a capture. The `synthetic-*` fixtures (`no-id-tool-call`, `inline-think`, `malformed-args`) are **hand-written, not captures**, each carries a `_synthetic` key saying so, and they exist only to make F1.3, F1.5 and F1.6 testable.

| # | Assumption | Result | Evidence |
|:--|:--|:--|:--|
| 1 | `tool_calls[].function.arguments` is a JSON string | **Verified**: `"{\"city\":\"Hanoi\"}"`; `finish_reason: tool_calls`, `content` is `""` | `tool-call.response.json` |
| 2 | `<think>` arrives inline in `content` | **Disproved on Ollama**: reasoning arrives in a separate message field `reasoning`; `content` is clean. F1(b) is revised: ignore `reasoning`, keep inline stripping only as defence | `text.response.json`, `tool-call.response.json` |
| 3 | Tool call may lack `id` | **Not observed**: Ollama returned `call_bcon1mjg` (plus a non-standard `index`). Generation stays as defence; F1.6 uses `synthetic-no-id-tool-call.response.json` | `tool-call.response.json` |
| 4 | Tool result round trip with `tool_call_id` | **Verified**: final reply `The current weather in Hanoi is 31°C and humid.`, `finish_reason: stop` | `tool-result.*.json` |
| 5 | Bearer header | **Accepted and ignored** by Ollama (request sent with `Authorization: Bearer test-key`; the round trip sent none and also worked). Whether DashScope requires it is **UNVERIFIED** | commands below |
| 6 | `GET {base}/models` health probe | **Verified**: 200 with `{"object":"list","data":[{"id":"qwen3:4b",…}]}` | `models.response.json` |
| 7 | Response carries `id`, `model`, `usage.prompt_tokens`/`completion_tokens` for X4 | **Verified** (`usage` also has `prompt_tokens_details`) | all response fixtures |
| 8 | DashScope: same API, compatible-mode path (`…/compatible-mode/v1`), Bearer key, model `qwen-plus` | **UNVERIFIED**, not run (needs an account/key). Hence `base-url` includes the version path and DashScope is out of scope (§5) | n/a |
| 9 | Behaviour on non-2xx (400, 503) and timeouts | **UNVERIFIED** against real Ollama; S1 tests use WireMock-synthesised errors | n/a |

Also observed, relevant to sizing: a trivial 5-word prompt produced ~3,300 completion tokens of `reasoning` on `qwen3:4b`, and the tool call turn ~1,700. With `timeout-seconds: 60` and `turn-deadline: 25s`, thinking can exhaust the deadline on small hardware; see decision §7.9 (S1 gate).

### Reproduce

```bash
scripts/ollama.sh install                      # once
F=docs/development/plan/qwen-model-client-fixtures
curl -s localhost:11434/v1/models
curl -s localhost:11434/v1/chat/completions -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer test-key' -d @$F/tool-call.request.json
curl -s localhost:11434/v1/chat/completions -H 'Content-Type: application/json' \
  -d @$F/tool-result.request.json
# Still to run (UNVERIFIED rows 3, 8, 9):
#   hosted endpoint:  curl -s "$QWEN_BASE_URL/chat/completions" -H "Authorization: Bearer $QWEN_API_KEY" ...
#   error shapes:     request with a bad model name -> expect 404/400 body; stop Ollama -> connection refused
```

## Change Log

| Date | Change | Reason | Slices |
|:--|:--|:--|:--|
| 2026-10-18 | §7.9 decided: (b) larger Qwen-only `QWEN_TURN_DEADLINE` and `timeout-seconds` | User decision at the S1 gate; thinking-disable (a) and non-thinking model (c) not pursued | S1 |
