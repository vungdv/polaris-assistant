# Testing Guideline

How we decide what kind of test to write, where it lives, and how it is run. Basis: [The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html). Tooling tiers (automated, k6, Playwright) are in `Engineer-Guidelines.md` §4; this guideline defines what each test is *for*.

## 1. Principles

1. **One purpose per test.** A test declares one risk (one row of the matrix in §3) and asserts only what that row allows.
2. **Cheapest layer that can verify the risk.** If a unit test can prove a business rule, no integration test re-proves it.
3. **Push tests down.** When a higher-level test catches a bug, add a lower-level test for it, then delete higher-level tests that now duplicate it.
4. **Prefer narrow over broad.** Test each boundary on its own. Layer-crossing `@SpringBootTest` slices (controller → application → infrastructure) are limited to a few named wiring smoke tests per app.
5. **Journeys stay few.** End-to-end tests cover critical user paths only, never business-rule permutations.
6. **Mock only at external boundaries.** Domain logic is never mocked in the test of the layer above it unless the matrix row says so.

## 2. Terms

| Term | Meaning |
|:--|:--|
| Component | A module or bounded context exposing a published contract (one Maven module, or one DDD context inside it). |
| Scope | What is real versus replaced: *solitary unit* (collaborators faked), *sociable unit* (real in-process collaborators), *narrow integration* (one real external boundary), *broad* (whole system). |
| Scenario | A case (happy path, edge, error) exercised at a given scope. A scenario is not a scope. |
| Aspect | The risk a test targets. Each test declares exactly one. |

## 3. Scope × aspect matrix

| Aspect (risk) | Scope | Real vs. faked | Stage / naming | Not allowed here |
|:--|:--|:--|:--|:--|
| Business rule | Unit (solitary or sociable) | All collaborators in-process | `*Test`, unit stage | Spring context, I/O |
| Query / mapping validity (e.g. JPQL in `@Query`) | Narrow integration: persistence | Real DB | `*IntegrationTest` | Business rules |
| HTTP contract of an endpoint | Narrow integration: web slice | Service mocked | `*IntegrationTest` | DB |
| Outbound client / messaging / outbox | Narrow integration: boundary | Real broker or DB, or stub server | `*IntegrationTest` | Business rules |
| Cross-context contract (OpenAPI, CloudEvents) | Contract | Schema or provider stub | Contract stage | Full stack |
| Resilience policy (retry, circuit breaker, timeout) | Component | Deliberately failing collaborator | `*IntegrationTest` | User journeys |
| Cross-cutting aspect (e.g. AOP tracing) | Unit of the aspect, plus one wiring test | | `*Test` and one `*IntegrationTest` | Re-asserting in every flow test |
| Critical user journey | API / e2e | Whole system | Separate e2e stage (`tests/e2e`) | Rule permutations |
| Load, concurrency, time budget | k6 | Whole system | `tests/perf` | JVM test suite |

## 4. Naming and stages

Target convention (the enforcement mechanism, aspect declaration and `make` targets are defined by the build, not here):

| Suffix / location | Stage | Must not |
|:--|:--|:--|
| `*Test` | Unit | Start a Spring context, open a socket, DB or container |
| `*IntegrationTest` | Integration | Assert business rules; omit its declared aspect |
| Contract and journey tests | Own stage (contract, e2e) | Depend on the full stack (contract) or enumerate rule cases (journey) |
| `tests/perf` | k6 | — |

Unit tests run in seconds on every change; integration tests run in minutes before a PR; contract, e2e and perf run as separate CI jobs.

## 5. Cross-cutting aspects

A cross-cutting concern (tracing by AOP, logging correlation, metrics) is:

- unit-tested **once**, on the aspect itself;
- covered by **one** wiring test proving it is applied;
- **not** re-asserted in controller, service or flow tests.

Flow tests may rely on the aspect being present but do not check its output.

## 6. Where does my test go?

1. Is it about load, concurrency or a time budget? → k6 (`tests/perf`).
2. Is it a stable main flow across components? → e2e journey (keep the list short).
3. Is it the shape of a published cross-context interface? → contract stage.
4. Does it need a real external boundary (DB, broker, HTTP stub, container)? → `*IntegrationTest`, one matrix row, no business rules.
5. Is it a business rule, validation or calculation? → `*Test`, no Spring, no I/O.

If a test fits no row, do not force it: raise it as a gap in this guideline.

## 7. Applying the rules to existing tests

- Misplaced tests are classified (aspect and verdict: keep, move down, move to k6/e2e, merge, delete) in the test inventory, then fixed module by module.
- A higher-level test is deleted only after the lower-level test covering the same rule has landed. Compare coverage before and after.
- Do not rewrite a test to a different layer without keeping the behavior it protected.
