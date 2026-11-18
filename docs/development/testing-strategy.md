# Testing Strategy

Testing and verification are becoming more important. AI tools can help us write many tests, but there are still some important questions we need to answer:

- **Test coverage:** How do we know our tests cover the cases that matter? AI may generate 95% of the tests we need, but what about the remaining 5%? Are the most important cases covered and tested correctly?
- **Test scope:** What should a test look like at each level? What should each type of test focus on, and which problems should it catch? See [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html) for more details.
- **Speed and trust:** How quickly can we get feedback after each change, and how much can we trust that feedback?

This testing strategy is a map of how we test a software system. It defines:

- **Types of tests:** Unit tests, integration tests, contract tests, end-to-end (E2E) tests, and so on.
- **Test scope:** What should be tested at each level, which parts of the system should be covered, and what problems each type of test should catch.

The goal is not simply to have more tests, but to make sure we test the right things at the right level and get fast, reliable feedback when we make changes.

The rest of this document answers the three questions in order: §3 defines the types of tests and their scope, §4 how we make sure the cases that matter are covered, and §5 how we keep feedback fast and trustworthy.

## 1. Principles

1. **One purpose per test.** A test declares one risk (one row of the matrix in §3) and asserts only what that row allows.
2. **Cheapest layer that can verify the risk.** If a unit test can prove a business rule, no integration test re-proves it.
3. **Push tests down.** When a higher-level test catches a bug, add a lower-level test for it, then delete higher-level tests that now duplicate it.
4. **Prefer narrow over broad.** Test each boundary on its own. Layer-crossing `@SpringBootTest` slices (controller → application → infrastructure) are limited to a few named wiring smoke tests per app.
5. **Journeys stay few.** End-to-end tests cover critical user paths only, never business-rule permutations.
6. **Mock only at external boundaries.** Domain logic is never mocked in the test of the layer above it unless the matrix row says so.
7. **Tests come from the spec, not from the code.** The cases that matter are the acceptance criteria and error paths in the slice spec. Generated tests are checked against them, never the other way round.

## 2. Terms

| Term | Meaning |
|:--|:--|
| Component | A module or bounded context exposing a published contract (one Maven module, or one DDD context inside it). |
| Scope | What is real versus replaced: *solitary unit* (collaborators faked), *sociable unit* (real in-process collaborators), *narrow integration* (one real external boundary), *broad* (whole system). |
| Scenario | A case (happy path, edge, error) exercised at a given scope. A scenario is not a scope. |
| Aspect | The risk a test targets. Each test declares exactly one. |
| Intent | Why the case exists: **core** (the contract works), **defensive** (invalid input, collaborator failure, unauthorised caller), **edge** (boundaries, empty, limits, duplicates). |

## 3. Test scope: types of tests and what each catches

Each row is one type of test. The "Catches" column is the reason it exists; if a test does not catch that kind of problem, it belongs in another row.

| Aspect (risk) | Scope | Catches | Real vs. faked | Stage / naming | Not allowed here |
|:--|:--|:--|:--|:--|:--|
| Business rule | Unit (solitary or sociable) | Wrong calculation, validation, state transition | All collaborators in-process | `*Test`, unit stage | Spring context, I/O |
| Query / mapping validity (e.g. JPQL in `@Query`) | Narrow integration: persistence | Broken query, mapping, migration, constraint | Real DB (Testcontainers) | `*IntegrationTest` | Business rules |
| HTTP contract of an endpoint | Narrow integration: web slice | Wrong status code, Problem Details body, auth filter, (de)serialisation | Service mocked | `*IntegrationTest` | DB |
| Outbound client / messaging / outbox | Narrow integration: boundary | Wrong request/event shape, serialisation, delivery and retry wiring | Real broker or DB, or stub server | `*IntegrationTest` | Business rules |
| Cross-context contract (OpenAPI, CloudEvents) | Contract | Breaking change between contexts | Schema or provider stub | Contract stage | Full stack |
| Resilience policy (retry, circuit breaker, timeout) | Component | Missing or misconfigured timeout, retry, breaker | Deliberately failing collaborator | `*IntegrationTest` | User journeys |
| Cross-cutting aspect (e.g. AOP tracing) | Unit of the aspect, plus one wiring test | Missing trace/log correlation or metrics | | `*Test` and one `*IntegrationTest` | Re-asserting in every flow test |
| Critical user journey | API / e2e | Components that pass alone but fail together; deployment and config | Whole system | Separate e2e stage (`tests/e2e`) | Rule permutations |
| Load, concurrency, time budget | k6 | Races, overselling, latency regressions | Whole system | `tests/perf` | JVM test suite |

### 3.1 Where does my test go?

1. Is it about load, concurrency or a time budget? → k6 (`tests/perf`).
2. Is it a stable main flow across components? → e2e journey (keep the list short).
3. Is it the shape of a published cross-context interface? → contract stage.
4. Does it need a real external boundary (DB, broker, HTTP stub, container)? → `*IntegrationTest`, one matrix row, no business rules.
5. Is it a business rule, validation or calculation? → `*Test`, no Spring, no I/O.

If a test fits no row, do not force it: raise it as a gap in this strategy.

### 3.2 Cross-cutting aspects

A cross-cutting concern (tracing by AOP, logging correlation, metrics) is:

- unit-tested **once**, on the aspect itself;
- covered by **one** wiring test proving it is applied;
- **not** re-asserted in controller, service or flow tests.

Flow tests may rely on the aspect being present but do not check its output.

## 4. Test coverage: are the cases that matter covered?

Line coverage tells us what code ran, not whether the important behavior was checked. AI-generated tests tend to cover the happy path well and miss the defensive and edge cases, or assert too little. We close that gap from the spec side.

### 4.1 Every acceptance criterion maps to a test

- The slice spec ([template](templates/slice-spec.template.md) §7 Test Mapping) lists every AC and every row of its error and edge table, with the test level from §3 and the test that covers it.
- The test title names the situation and the AC: `Given <situation>, when <trigger>, then <observable outcome> (AC-n)`. Java uses `@DisplayName`. The body is split into `// given`, `// when`, `// then`; assertions only in `then`.
- A slice is not done while any row says `to be written`.

### 4.2 Check each class against the intent list

For each unit under test, the tests show all three intents (§2), in this order: core, defensive, edge. Use this checklist to find the missing 5%:

| Intent | Typical cases to look for |
|:--|:--|
| Core | Each AC's happy path; each state transition; the published response or event shape. |
| Defensive | Invalid and missing input; unauthenticated (401) and unauthorised (403) callers; collaborator down, slow or returning an error; Problem Details on every error path. |
| Edge | Empty, one, many; limits and off-by-one; duplicates and replays (`Idempotency-Key`, redelivered events); time zones and clock boundaries; concurrent updates to the same resource. |

### 4.3 A test must be able to fail

- When reviewing a test (human or AI written), break the behavior it guards (revert the fix, flip a condition) and check the test goes red. A test that stays green protects nothing.
- Assert the observable outcome (response, persisted state, emitted event), not the internal calls that produced it.
- Reviews read the Test Mapping and the `then` blocks first; the number of tests is not a review signal.

### 4.4 Coverage numbers

JaCoCo coverage (unit plus integration, reported to Sonar) is used to **find** untested branches, not as a target. An uncovered branch is either added to the spec and tested, or recognised as dead code and removed.

## 5. Speed and trust: fast, reliable feedback

### 5.1 Stages

The suffix or location selects the stage (automated enforcement follows in the tests-improvement spec):

| Suffix / location | Stage | When it runs | Must not |
|:--|:--|:--|:--|
| `*Test` | Unit | Every change, while iterating (seconds) | Start a Spring context, open a socket, DB or container |
| `*IntegrationTest` | Integration | Before a PR (minutes, needs Docker) | Assert business rules; omit its declared aspect |
| Contract tests | Contract | Separate CI job | Depend on the full stack |
| `tests/e2e` | e2e | Separate CI job, against the running stack | Enumerate rule cases |
| `tests/perf` | k6 | On demand and before release | — |

| Command | Runs |
|:--|:--|
| `make test-unit` (`mvn test`) | `*Test` only (Surefire). No Spring context, no container. |
| `make test-integration` (`mvn verify -DskipUnitTests=true`) | `*IntegrationTest` only (Failsafe, needs Docker). |
| `make test` (`mvn clean verify`) | Unit, then integration. Coverage covers both. |
| `make test-perf`, `make test-concurrency` | k6 load and concurrency scripts. |

### 5.2 Time budgets

Each stage has a time budget measured in CI. Budgets are set after a baseline of today's per-stage and per-module time (open question in the tests-improvement spec). When a stage exceeds its budget, the fix is to push tests down (§1, principle 3), not to drop tests or skip the stage.

### 5.3 Trust: deterministic tests

A failing test must mean the code is wrong. To keep that true:

- **No real time or randomness.** Inject a `Clock` and fixed seeds; never `Thread.sleep` to wait. Wait on a condition with a timeout (e.g. Awaitility) instead.
- **Isolated data.** Each test creates the data it needs and does not depend on test order or on data left by another test.
- **Same interfaces as production.** Real DB and broker via Testcontainers, real JWTs via `JwtMockFactory`; security filters are never disabled.
- **Flaky tests are bugs.** A test that fails intermittently is fixed or quarantined with a linked issue within the same week; it is never retried until green.
- **Clear failures.** The `Given/When/Then` title and the assertion message say which AC broke, so the reader does not need to debug the test to understand the failure.

## 6. Applying the strategy to existing tests

- Misplaced tests are classified (aspect and verdict: keep, move down, move to k6/e2e, merge, delete) in the test inventory, then fixed module by module.
- A higher-level test is deleted only after the lower-level test covering the same rule has landed. Compare coverage before and after.
- Do not rewrite a test to a different layer without keeping the behavior it protected.
