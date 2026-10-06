# Assistant BDD (Gherkin + Cucumber-JVM)

- **Scope:** `polaris-assistant` only: executable Gherkin specifications for the chat turn (`POST /api/v1/assistant/chat`) and the draft endpoints (`/api/v1/assistant/sessions/{sessionId}/drafts/{draftId}/{confirm,cancel}`)
- **Related:** `assistant-turn-behaviour.md` (turn paths T1–T17, slice B1), PRD-003 §5 (Scenarios 1–15), `client-ai-model-stability.md` (A3, A4)
- **Status:** Draft (2026-10-06)

## 1. Problem statement

The assistant's behaviour is specified in prose (PRD-003 §5 Given/When/Then, the turn-path table in `assistant-turn-behaviour.md` §2) and verified by JUnit integration tests (`Assistant*IntegrationTest`, `OrderDraftControllerIntegrationTest`) whose names and `@DisplayName`s repeat that prose by hand. Nothing links a business scenario to the test that proves it, and nothing fails when a scenario has no test.

`assistant-turn-behaviour.md` B1 already chose Cucumber-JVM, but as **one** slice that adds the tooling, the stubs, ~25 scenarios and an index together. That is too big to review and mixes "does the tooling work" with "is the behaviour right". This plan replaces B1 with small slices: **infrastructure first, one success scenario to demonstrate the approach, then the remaining use cases one feature file at a time.**

## 2. Design choices

- **Cucumber runs as an ordinary JUnit Platform test.** `cucumber-junit-platform-engine` + `junit-platform-suite` + `cucumber-spring`, all `test` scope, versions pinned through `cucumber-bom` in the parent `pom.xml` `dependencyManagement`. One `@Suite` class (`RunCucumberTest`) is picked up by Surefire, so `mvn test` / `make test` run it with no extra runner or plugin.
- **One Spring context for every scenario.** A single `@CucumberContextConfiguration` class: `@SpringBootTest(classes = PolarisAssistantApp.class)` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)`, so scenarios share one cached context and one PostgreSQL container. Scenario state lives in `@ScenarioScope` beans; stubs are reset in a `@Before` hook.
- **Stub only at external boundaries, over the production protocol** (AGENTS.md P1.3 *Testing Alignment*, P3.2):

  | Dependency | Production interface | BDD double |
  |:--|:--|:--|
  | Gemini | HTTP `generateContent` | WireMock, real `GeminiAiModelClient` (as `AssistantModelOutageIntegrationTest`) |
  | TypeSafe | HTTP | WireMock, real `TypeSafeIntentClassifier` |
  | Order Management `/customers/me` | HTTP | WireMock, real `HttpCurrentCustomerClient` |
  | Polaris Core MCP | MCP over HTTP | `PolarisMcpClient` test double, extending `FakeOrderManagementMcp` (see §5.2) |
  | Redis intent taxonomy | Redis | `RedisIntentManager` backed by `DefaultIntentManager` (as today's integration tests) |
  | PostgreSQL | JDBC | Testcontainers `postgres:16` (real) |
  | Identity provider | JWT | `SecurityMockMvcRequestPostProcessors.jwt()` with subject + `PERM_*` authorities |

  One WireMock server serves Gemini, TypeSafe and customers on separate paths, wired with `@DynamicPropertySource` (`polaris.ai.base-url`, `polaris.typesafe.base-url`, customers base URL).
- **Scenarios assert observable contracts only:** HTTP status, Problem Details `type`, `Retry-After`, card types, persisted rows, metric `outcome`. Never the model's prose, except fixed texts Polaris writes itself.
- **Small, shared step vocabulary**, one step class per concern: `ShopperSteps` (identity, sending a message), `GeminiSteps`, `TypeSafeSteps`, `McpSteps`, `ResponseSteps`, `PersistenceSteps`, `MetricSteps`. Target ≈15–20 steps in total by the end; a new step needs a reason in the PR.
- **Tags link behaviour to business and code:** `@T<n>` (turn path), `@PRD-003-S<n>` (PRD scenario), `@happy` / `@degraded` / `@refused` / `@failed` (class), `@gap` (today's behaviour, changed by a later slice of `assistant-turn-behaviour.md`), `@smoke`.
- **Reports per build:** `cucumber.plugin=pretty,html:target/cucumber/report.html,json:target/cucumber/report.json`; undefined or pending steps fail the build (strict, the Cucumber 7 default; `cucumber.publish.quiet=true`).
- **BDD only for major behaviour.** Unit rules (widget merging, fallback reason codes, `Retry-After` rounding) stay plain JUnit. UI-only PRD scenarios (S2 card rendering, S10 PKCE refresh, S15 SSE) are out of scope here.

### Layout

```
apps/polaris-assistant/src/test/
  java/vn/danang/polaris/assistant/bdd/
    RunCucumberTest.java               # @Suite @IncludeEngines("cucumber") @SelectClasspathResource("features")
    CucumberSpringConfiguration.java   # @CucumberContextConfiguration @SpringBootTest …
    support/  ExternalStubs.java, ScenarioContext.java
    steps/    ShopperSteps.java, GeminiSteps.java, …
  resources/
    junit-platform.properties          # glue, plugins, tag filter
    features/
      smoke.feature
      turn/happy.feature, degraded.feature, refused.feature, failed.feature
      draft/confirm.feature, cancel.feature
```

## 3. Slices

### C1 — BDD infrastructure (walking skeleton)

**Goal:** Cucumber runs inside `mvn test` with the real Spring context, and one trivial scenario proves the wiring end to end.

1. **Compatibility gate (first):** confirm the current Cucumber-JVM release runs on Spring Boot 4.1 / Spring Framework 7 / JUnit 6. If not, STOP, mark the slice `blocked` and propose the fallback (JUnit `@DisplayName("T<n> …")` + `@Tag`) as a plan change.
2. Parent `pom.xml`: import `cucumber-bom`. `apps/polaris-assistant/pom.xml`: `cucumber-java`, `cucumber-spring`, `cucumber-junit-platform-engine`, `junit-platform-suite` (all `test`).
3. `RunCucumberTest`, `CucumberSpringConfiguration` (context + PostgreSQL + one WireMock server + the `RedisIntentManager` / `PolarisMcpClient` doubles, all with no behaviour scripted yet), `junit-platform.properties`.
4. `features/smoke.feature`, tagged `@smoke`:
   ```gherkin
   Feature: Assistant service is up
     Scenario: The assistant reports ready
       When the readiness probe is called
       Then the response status is 200
   ```
5. `Makefile`: `test-bdd` target (`mvn -pl apps/polaris-assistant test -Dtest=RunCucumberTest`).
6. `apps/polaris-assistant/README` (or `docs/development/`) short *"Writing a scenario"* section: where features live, the tag vocabulary, how to run one tag (`-Dcucumber.filter.tags=@T2`), where the report is.

**Acceptance:** `mvn test` runs the smoke scenario and every existing test; an undefined step fails the build (checked once by hand and noted in the PR); `target/cucumber/report.html` is produced. **No production code changes.**

### C2 — First success scenario: catalog search (T2, PRD-003 S1)

**Goal:** demonstrate BDD on the flagship journey, and establish every stub seam once (TypeSafe, Gemini function call → text, MCP tool, `/customers/me`, persistence) so later slices only add steps.

```gherkin
Feature: Chat turn – happy path

  Background:
    Given the shopper "alice" is signed in with permissions "catalog.read"

  @happy @T2 @PRD-003-S1
  Scenario: Catalog search answers with a product list grounded in live stock
    Given TypeSafe classifies the message as "catalog.product.search" with confidence 0.95
    And Polaris Core offers the tool "search_available_products"
    And "search_available_products" returns the products:
      | sku           | name                  | price | available |
      | NG-CHARGER-01 | Nova 65W Fast Charger | 24.90 | 200       |
    And Gemini first calls "search_available_products" with query "charger"
    And Gemini then replies with text "We have the Nova 65W Fast Charger in stock."
    When the shopper sends "do you have fast chargers?"
    Then the response status is 200
    And the reply carries a "PRODUCT_LIST" card with sku "NG-CHARGER-01"
    And the conversation stores the user message, the tool turn and the reply
    And the turn is counted with outcome "answered"
```

Adds the steps above, `turn/happy.feature`, and a first row in the index (§C8 format) inside `assistant-turn-behaviour.md`. If an existing JUnit test fully duplicates this scenario, delete it in the same PR; otherwise leave it.

**Acceptance:** the scenario passes; breaking the behaviour on purpose (e.g. dropping the card) makes it fail with a readable Gherkin-level message (shown in the PR description).

### C3 — Remaining happy paths (T1, T3; PRD-003 S3, S6)

`turn/happy.feature`: direct answer (T1), draft staged with `ORDER_DRAFT` card and no order placed (T3, S3), order history/status through `list_customer_orders` / `get_order_status` (S6). Replaces the overlapping parts of `AssistantOrderStagingIntegrationTest`.

### C4 — Draft confirmation and cancellation (PRD-003 S4, S5, S9, S11)

`draft/confirm.feature`, `draft/cancel.feature` over the REST draft endpoints: idempotent confirm with `Idempotency-Key` (S4), out-of-stock Problem Details with remedy (S5), expired draft (S9), concurrent stock depletion (S11), cancel. Uses `FakeOrderManagementMcp` as is. Replaces the overlapping parts of `OrderDraftControllerIntegrationTest`.

### C5 — Degraded turn paths (T4–T9)

`turn/degraded.feature`: low confidence (T4), TypeSafe unavailable (T5), tool outside the intent (T6), tool fails (T7), tool discovery fails (T8, `@gap`), iteration limit (T9, `@gap`). Pins **today's** behaviour; `assistant-turn-behaviour.md` B4 later removes `@gap` and rewrites T8/T9.

### C6 — Refused paths (T10, T11; PRD-003 S12, S14)

`turn/refused.feature`: Polaris Core MCP denies a tool (T10, `200 "Action denied: …"`), another user's session (T11 / S12 IDOR, `403` Problem Details), no token (S14, `401`). Replaces `AssistantSessionAccessIntegrationTest` where fully duplicated.

### C7 — Failed paths (T12–T17)

`turn/failed.feature`: model unavailable before / after tools (T12, T13, `503` + `Retry-After`), turn deadline (T14), model rejects our request (T15, `500`), breaker open (T16), unexpected error (T17). Replaces overlapping parts of `AssistantModelOutageIntegrationTest` and `AssistantTurnTimeBudgetIntegrationTest`. **External:** T15/T16 need stability A3+A4 merged; until then, ship T12–T14 + T17 and leave T15/T16 as a follow-up row.

### C8 — Traceability check

Turn `assistant-turn-behaviour.md` §2 into the index (ID → `feature:scenario`, class, decided in `file:line`, gap), linked from `agent-sequence.md`. Add a check in the runner module (a plain JUnit test reading the `.feature` files) that **every** T-ID and backend-observable PRD-003 scenario in the index has exactly one tagged scenario, so a missing scenario fails CI. Add the *"Adding a path"* rule: scenario + index row in the same PR.

**Tests across all slices (AGENTS.md P3.2):** each slice's scenarios are its tests; every slice ends with `mvn test` green and no `@wip` scenario left. JUnit tests are removed only where a scenario now covers the same assertions in full.

## 4. Order and dependencies

C1 → C2 are strictly first. C3–C6 only depend on C2 and can each ship independently. C7 depends on stability A3+A4 for T15/T16 only. C8 last, since it requires every row to exist. `assistant-turn-behaviour.md` B2 (sealed `TurnResult`) should wait for C3, C5, C6, C7, so that its refactor is guarded by the full catalogue.

## 5. Decisions to confirm

1. **Replace `assistant-turn-behaviour.md` B1 with this plan.** B1 is `in-progress` but has no branch. Recommended: mark B1 `dropped` (*"split into assistant-bdd.md C1–C8"*), and make B2 depend on this plan instead.
2. **MCP double at the `PolarisMcpClient` interface, not over HTTP.** Stubbing MCP's streamable-HTTP JSON-RPC in WireMock is costly and adds little over the existing contract-faithful `FakeOrderManagementMcp`. Recommended: interface-level double now; `HttpPolarisMcpClient` keeps its own WireMock test. Alternative: a WireMock MCP stub for full protocol parity.
3. **Demo scenario.** Recommended: T2 catalog search (business-readable, touches every seam once). Alternative: T1 direct answer (fewer steps, but C3 then has to build the MCP and card plumbing).
4. **Existing integration tests.** Recommended: remove a JUnit test only when a scenario fully covers it, slice by slice. Alternative: keep all of them until C8 and delete in one pass.

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | C1 | BDD infrastructure + smoke scenario | `todo` | — | | | Cucumber / JUnit 6 compatibility gate first |
| 2 | C2 | First success scenario: catalog search (T2, S1) | `todo` | — | | | Establishes all stub seams |
| 3 | C3 | Happy paths T1, T3 (S3, S6) | `todo` | — | | | |
| 4 | C4 | Draft confirm / cancel (S4, S5, S9, S11) | `todo` | — | | | |
| 5 | C5 | Degraded paths T4–T9 | `todo` | — | | | T8, T9 tagged `@gap` |
| 6 | C6 | Refused paths T10, T11 (S12, S14) | `todo` | — | | | |
| 7 | C7 | Failed paths T12–T17 | `todo` | Stability A3+A4 merged (T15, T16 only) | | | |
| 8 | C8 | Index + traceability check | `todo` | — | | | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| 2026-10-06 | Plan drafted | User asked for BDD (Gherkin + Cucumber) for Polaris Assistant in minimal slices: infrastructure, one success case, then the remaining use cases | all |
