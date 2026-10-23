# Tests Improvement

- **Scope:** the automated test suite of every Maven module (`libs/*`, `apps/*`) and its CI pipeline
- **Nature:** strategy plan. Objective and high-level requirement 
- **Status:** Draft for review

## 1. Problem statement

The test suite should give fast, trustworthy feedback at a cost the team is willing to pay on every change. 
Today test suite is mixing of different concerns/aspects: 
- unit-tests, integration-tests
- e2e tests and api tests are already in their own places.

The intentional & objective of the tests are still mixing each other like 
- integration-tests sometimes do performance tests, performance test can be cover better in api test such as k6
- integration-tests cover business rules, it should focus on the integration between the layers only. 

We are lacked of a strategy for tests to verify such as
- component scope
- stable, main flow cross components
- cross-cutting concern should be separate like traces are implementing by AOP.

We haven't a comprehensive guideline for these testing concerns/aspects

Observed today (counts from `src/test`, to be re-measured in slice Q1):

| Module | `*Test` classes | Using `@SpringBootTest` | Named `*IntegrationTest` |
|:--|--:|--:|--:|
| `../../../../apps/polaris` | 42 | 21 | 15 |
| `../../../../apps/polaris-assistant` | 65 | 13 | 12 |
| `../../../../libs/polaris-outbox` | 19 | 4 | 7 |

- Unit and integration tests share the `*Test` suffix and the same surefire run, so "run only the fast tests" has no clean answer.
- Concurrency, ordering and time-budget checks (e.g. `OrderEventOrderingConcurrencyTest`, `AssistantTurnTimeBudgetIntegrationTest`) live inside the JVM integration suite. `../../../../tests/perf` (k6: `api-test.js`, `order-concurrency-test.js`) already exists for load and concurrency.
- `../../../../Engineer-Guidelines.md` §4 defines tiers by tool (automated / k6 / Playwright), not by what each kind of test is *for* or *where its boundary is*.
- The pyramid is likely inverted, most visibly in `../../../../apps/polaris` (21 of 42 classes boot Spring, against 13 of 65 in `apps/polaris-assistant`). Nothing today tracks the shape (see TR-I9).

## 2. Goal

Every test has exactly one declared purpose, lives in the layer that can verify that purpose cheapest, and is run by a
named, separately invocable stage. A developer can run the fast feedback loop (unit) in seconds, the integration stage
in minutes, and nothing in the JVM suite is asserting what k6 or e2e should.

Out of scope: production code changes, new product features, and speed mechanics (containers, forks, context caching).
Those stay in `test-speed.md` and are re-sequenced after the taxonomy lands (see Q6).

## 3. Test scope & aspects

Basis: [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html).

**Terms**
- **Component:** a module or bounded context (one Maven module, or one DDD context inside it) exposing a published contract.
- **Scope:** what is real versus replaced in a test: solitary unit (collaborators faked), sociable unit (real in-process collaborators), narrow integration (one real external boundary), broad (whole system).
- **Scenario:** the cases (happy path, edge, error) exercised at a given scope. It is not a scope.
- **Aspect:** the risk a test targets. Each test declares exactly one.

**Scope and aspect matrix**

| Purpose (risk) | Scope | Real vs. faked | Stage / naming | Not allowed here |
|:--|:--|:--|:--|:--|
| Business rule | Unit (solitary or sociable) | all collaborators in-process | `*Test`, unit stage | Spring context, I/O |
| Query / mapping validity (e.g. JPQL in `@Query`) | Narrow integration: persistence | real DB | `*IntegrationTest` | business rules |
| HTTP contract of an endpoint | Narrow integration: web slice | service mocked | `*IntegrationTest` | DB |
| Outbound client / messaging / outbox | Narrow integration: boundary | real broker or DB, or stub server | `*IntegrationTest` | business rules |
| Cross-context contract (OpenAPI, CloudEvents) | Contract | schema or provider stub | contract stage | full stack |
| Resilience policy (retry, circuit-breaker, timeout) | Component | deliberately failing collaborator | `*IntegrationTest` | user journeys |
| Cross-cutting aspect (e.g. AOP tracing) | Unit of the aspect + one wiring test | | | re-asserting in every flow test |
| Critical user journey (stable main flow across components) | API / e2e | whole system | separate e2e stage | rule permutations |
| Load, concurrency, time budget | k6 | whole system | `../../../../tests/perf` | JVM suite |

**Rules**
- **One purpose per test.** A test declares one row of the matrix and asserts only what that row allows.
- **Push tests down.** When a higher-level test catches a bug, add a lower-level test for it, and delete higher-level tests that duplicate lower ones.
- **Prefer narrow over broad.** Layer-crossing slices (controller → application → infrastructure) are limited to a few wiring smoke tests per app. Each boundary is otherwise tested on its own.
- **Journeys stay few.** Only critical paths, with no business-rule permutations.