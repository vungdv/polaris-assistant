# Q2: Separate unit and integration stages

- **Plan:** [Tests Improvement](../../plan/2026-10-tests-improvement/02.plan.md) · **Slice:** Q2 · **Depends on:** Q1 (done)
- **Scope:** separate unit and integration tests by naming convention (`*Test` = unit, `*IntegrationTest` = integration) and make each stage runnable on its own from Maven and `make`. Build and test tooling only; no production code, no moving of tests between layers. Automated enforcement of the convention (guard, aspect tag) is slice Q2.2.
- **Spec state:** in-review (approval is the PR review of this slice)

## 1. Principle and guidelines

Defined in the [testing guideline](../../testing-guideline.md); this slice applies §3 (matrix) and §4 (naming and stages) and does not redefine them. Main points:

- One purpose (aspect) per test; cheapest layer that can verify it.
- `*Test` is a unit test: no Spring context, no I/O (no socket, DB, container).
- `*IntegrationTest` is an integration test: one real boundary, one declared aspect, no business rules.
- Unit tests run in seconds on every change; integration tests run in minutes before a PR.
- Misplaced tests are fixed module by module in later slices (Q4 to Q7), not here.

## 2. Tasks

### 2.1 Rename misplaced test files

30 classes are named `*Test` but start a Spring context or a real boundary (WireMock, Testcontainers). They are renamed to `*IntegrationTest` (`git mv`, class name and references only, no assertion changes) so the unit stage is Spring-free. Each is classified against the guideline §3 matrix; where a class mixes concerns, the last column says what a later slice does with it.

| Test class (new name) | Module | Aspect (guideline §3) | Note / later step |
|:--|:--|:--|:--|
| `OutboxAutoConfigurationIntegrationTest` | outbox | wiring (cross-cutting) | Auto-configuration conditions via `ApplicationContextRunner`. Keep as wiring smoke test. Q5 |
| `OutboxKafkaAutoConfigurationIntegrationTest` | outbox | wiring (cross-cutting) | As above. Q5 |
| `ProductRepositoryIntegrationTest` | polaris | persistence | Query validity, real DB. Keep; consider `@DataJpaTest` narrowing. Q7 |
| `CustomerRepositoryFuzzyIntegrationTest` | polaris | persistence | Query validity. Keep. Q7 |
| `OrderRepositoryPlacedSummaryIntegrationTest` | polaris | persistence | Query validity. Keep. Q7 |
| `ProductControllerIntegrationTest` | polaris | web (HTTP contract) | Service mocked. Keep; 404/validation rules that are business logic move down to unit. Q7 |
| `CategoryControllerIntegrationTest` | polaris | web (HTTP contract) | Keep. Q7 |
| `OrderControllerIntegrationTest` | polaris | web (HTTP contract) | Keep; state-rule cases (cancel, 409) move down to unit. Q7 |
| `McpServerIntegrationTest` | polaris | web/boundary (MCP contract) | Full `@SpringBootTest`, 69 tests mixing schema contract, tool behaviour and validation. Split: contract stays, rules move down. Q7 |
| `OrderQuoteMcpToolsIntegrationTest` | polaris | mixed | Schema contract plus quote rules (merge SKUs, fractional quantity). Rules move down. Q7 |
| `ProductMcpToolsStructuredContentIntegrationTest` | polaris | mixed | Schema contract plus search behaviour. Q7 |
| `OrderConcurrencyIntegrationTest` | polaris | load / concurrency | Belongs in k6 (`tests/perf`). Delete after k6 scenario exists. Q4, Q7 |
| `OrderEventOrderingConcurrencyIntegrationTest` | polaris | load / concurrency | Same. Q4, Q7 |
| `GracefulShutdownIntegrationTest` | polaris, assistant, fulfilment-emulator | resilience / lifecycle | One per app, real server. Keep as boundary test. Q6, Q7 |
| `OrderDraftRepositoryIntegrationTest` | assistant | persistence | Query and constraint validity. Keep. Q6 |
| `AssistantChatControllerIntegrationTest` | assistant | web (HTTP contract) | Keep. Q6 |
| `GeminiAiModelClientWireMockIntegrationTest` | assistant | boundary (outbound client) | WireMock stub server. Keep. Q6 |
| `OpenAiCompatibleModelClientWireMockIntegrationTest` | assistant | boundary (outbound client) | Keep. Q6 |
| `QwenHealthIndicatorIntegrationTest` | assistant | boundary (outbound client) | Keep. Q6 |
| `ModelProviderSelectionIntegrationTest` | assistant | wiring | Provider selection via context runner plus stubs. Q6 |
| `ResilientModelClientIntegrationTest` | assistant | resilience | Retry/backoff against failing stub. Keep. Q6 |
| `ResilientQwenClientIntegrationTest` | assistant | resilience | Keep. Q6 |
| `CircuitBreakingIntentClassifierIntegrationTest` | assistant | resilience | Keep. Q6 |
| `ModelResilienceProductionConfigIntegrationTest` | assistant | wiring | Asserts production settings only. Candidate to move down to a unit test on the properties. Q6 |
| `ModelPropertiesBindingIntegrationTest` | assistant | mixed (binding rules) | Property binding and defaults; unit-testable without Spring. Move down. Q6 |
| `QwenPropertiesIntegrationTest` | assistant | mixed (binding rules) | Same. Move down. Q6 |
| `RedisTimeoutProductionConfigIntegrationTest` | assistant | wiring | One production-config assertion. Move down or merge. Q6 |
| `GenAiTelemetryConfigIntegrationTest` | assistant | cross-cutting (telemetry wiring) | Keep as the wiring test; content owned by `aop-trace-improvement.md` O1. Q6 |

The aspect column is the classification only; declaring it on each class (tag) and enforcing the convention is Q2.2.

### 2.2 CLI: run unit and integration stages separately

| Command | Runs |
|:--|:--|
| `make test-unit` (`mvn test`) | `*Test` only, via Surefire. No Spring context, no container. |
| `make test-integration` (`mvn verify -DskipUnitTests=true`) | `*IntegrationTest` only, via Failsafe (needs Docker). |
| `make test` (`mvn clean verify`) | Both stages. One JaCoCo report covers both for Sonar. |

Per module: `mvn test -pl apps/polaris`, `mvn verify -pl apps/polaris -DskipUnitTests=true`.

Implementation notes:
- **Surefire + Failsafe (D1, resolved here).** Surefire excludes `**/*IntegrationTest.java` and keeps its default `**/*$*` exclusion (otherwise `@Nested` classes of an excluded outer class would run in the unit stage). Failsafe includes only `*IntegrationTest`. `skipUnitTests` follows `-DskipTests`; `-DskipITs` skips integration only.

### 2.3 Acceptance

| ID | Check |
|:--|:--|
| AC-1 | `mvn clean test`: no `*IntegrationTest` runs; no Spring context or container starts. |
| AC-2 | `mvn verify -DskipUnitTests=true`: only `*IntegrationTest` runs (Failsafe reports; no Surefire). |
| AC-3 | `mvn clean verify`: both stages run; `jacoco.xml` is written after the integration stage. |
| AC-4 | `make -n` shows the three targets map to the commands above. |
| AC-5 | `mvn package -DskipTests` (Docker image build) skips both stages (nothing else is bound to it). |

## 3. Out of scope

Guard, aspect declaration and baseline (Q2.2, D2 and D3); moving rules down into unit tests, deleting duplicates, tagging existing tests (Q5 to Q7); k6 scenarios for the concurrency tests (Q4); pyramid report (Q3); contract stage (Q8); CI job split (Q9; CI already runs `mvn verify`); speed work.

## 4. Revisions

| Date | Change | Reason |
|:--|:--|:--|
| 2026-10-23 | Initial draft | — |
| 2026-10-24 | Restructured: principle reference, rename table classified by the guideline, CLI task. Template sections dropped. | PR review comments |
| 2026-10-24 | Guard, aspect tag and baseline removed; moved to Q2.2 (ArchUnit). | Review decision |
