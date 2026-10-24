# Q2.2: Convention guard and aspect declaration (ArchUnit)

- **Plan:** [Tests Improvement](../../plan/2026-10-tests-improvement/02.plan.md) · **Slice:** Q2.2 · **Depends on:** Q2 (done)
- **Covers:** TR-I4, SC-3, D2, D3
- **Scope:** enforce the Q2 naming convention in the build and fix the one mechanism by which an integration test declares its aspect. Test tooling only; no production code, no moving or deleting of tests.
- **Spec state:** in-review

## 1. Principle and guidelines

Applies [testing guideline](../../testing-guideline.md) §3 (aspect = one matrix row) and §4 (`*Test` is Spring-free and I/O-free; `*IntegrationTest` declares exactly one aspect). It does not redefine them.

## 2. Decisions

| ID | Decision | Reason |
|:--|:--|:--|
| D2 | **Custom annotation** `@IntegrationAspect(Aspect)`, not `@Tag`. `Aspect` is an enum whose values are the integration rows of guideline §3. | The enum makes the allowed set closed and compiler-checked; a non-repeatable annotation gives "exactly one" for free; a `@Tag` string would allow typos and several values. Q3 reads the annotation to count by aspect. |
| D3 | **ArchUnit rules run as unit tests** (`*Test`, Surefire) in each module, via `ArchTests.in(...)` from a shared rules class. | Rules are plain Java, run in the unit stage in seconds, and are skipped by `-DskipTests` (Docker image builds). No build script or plugin filter to maintain. |
| D3a | **Shared code lives in a new test-support library** `libs/polaris-test-conventions` (annotation, enum, rules, `archunit.properties`). Every consumer depends on it with `<scope>test</scope>`. | Satisfies "no new production dependency": the annotation and ArchUnit never reach a runtime classpath or an image. |
| D3b | **Baseline = ArchUnit `FreezingArchRule`** on the aspect rule only, store committed under `src/test/resources/archunit_store` in each module. | New violations fail; fixed violations are removed from the store, so it only shrinks. Q5 to Q7 tag the tests and empty the stores. |

## 3. Behavior

### 3.1 Rules (evaluated on the test classes of a module, package `vn.danang.polaris`)

| Rule | Applies to | Requirement |
|:--|:--|:--|
| R1 `unit tests start no Spring context` | classes named `*Test` but not `*IntegrationTest` | Depend on none of `org.springframework.boot.test..`, `org.springframework.test.context..` |
| R2 `unit tests open no real boundary` | same | Depend on none of `org.testcontainers..`, `com.github.tomakehurst.wiremock..` |
| R3 `integration tests declare one aspect` | classes named `*IntegrationTest` | Annotated with `@IntegrationAspect` (frozen baseline) |
| R4 `aspect only on integration tests` | classes annotated with `@IntegrationAspect` | Named `*IntegrationTest` |

R1 and R2 have no baseline: Q2 already renamed every offender, so any violation is new. R3 is frozen because existing integration tests are untagged until Q5 to Q7.

### 3.2 Aspect values

`PERSISTENCE`, `WEB`, `BOUNDARY`, `RESILIENCE`, `WIRING`, one per integration row of guideline §3 (`BOUNDARY` = outbound client, messaging, outbox; `WIRING` includes cross-cutting wiring tests). Adding a value requires a guideline §3 change first.

### 3.3 Error and edge cases

| Condition | Expected result |
|:--|:--|
| New `*Test` with `@SpringBootTest`, `@ExtendWith(SpringExtension.class)`, `@Testcontainers` or a `WireMockServer` | Unit stage fails with the rule name and class |
| New `*IntegrationTest` without `@IntegrationAspect` | Unit stage fails (not in baseline) |
| Existing untagged `*IntegrationTest` | Passes (in baseline) |
| An untagged baseline test is tagged or removed | Passes; its entry disappears from the store |
| `-DskipTests` (Docker image build) | Guard does not run; test classes still compile with the new test-scope dependency |
| Module with no integration tests | R3 passes with an empty store |

## 4. Interfaces

### 4.1 Build

- New module `libs/polaris-test-conventions` added to the parent `<modules>` and `dependencyManagement`; ArchUnit version managed in the parent (`archunit.version`).
- Dockerfiles of the three apps copy the new module POM next to the other lib POMs (layer-caching list).
- Consumers (outbox, polaris, polaris-assistant, polaris-fulfilment-emulator) add test-scope `polaris-test-conventions`, and one `TestConventionsTest`. `polaris-common` and `polaris-events` have no integration or Spring tests and are not covered.

### 4.2 Usage

```java
@IntegrationAspect(Aspect.PERSISTENCE)
class ProductRepositoryIntegrationTest { ... }
```

## 5. Non-Functional Requirements

- **Performance:** the guard adds under 5 s per module to the unit stage (class import only).
- **Security:** no new runtime or production dependency.

## 6. Acceptance Criteria

| ID | Criterion | Covers |
|:--|:--|:--|
| AC-1 | Given a `*Test` class with a Spring or Testcontainers/WireMock dependency, when R1/R2 are evaluated, then they fail naming the class. | TR-I4 |
| AC-2 | Given a spring-free `*Test`, then R1 and R2 pass. | TR-I4 |
| AC-3 | Given an untagged `*IntegrationTest`, when R3 is evaluated, then it fails; given a tagged one, it passes. | TR-I4, SC-3 |
| AC-4 | Given `@IntegrationAspect` on a class not named `*IntegrationTest`, then R4 fails. | SC-3 |
| AC-5 | Given each consumer module, `mvn test -pl <module>` runs the guard and passes against the committed baseline. | TR-I4 |
| AC-6 | Given a new untagged `*IntegrationTest` added to a consumer module, then `mvn test` fails (verified once manually, recorded in the PR). | TR-I4 |
| AC-7 | `mvn package -DskipTests` for each app still builds and the guard library is absent from the app jar. | build |

## 7. Test Mapping

| AC | Test level | Test |
|:--|:--|:--|
| AC-1, AC-2, AC-3, AC-4 | unit (ArchUnit on fixture classes) | `TestConventionRulesTest` in `libs/polaris-test-conventions` |
| AC-5 | unit (ArchUnit on the module's real test classes) | `TestConventionsTest` in each consumer module |
| AC-6 | manual, once | PR description |
| AC-7 | build | `mvn package -DskipTests -pl apps/* -am`, jar listing |

## 8. Out of scope

Tagging existing tests, moving or deleting any test (Q5 to Q7); counting by aspect (Q3); CI job split (Q9); a guard for contract or journey tests (Q8); detecting `@Test` classes whose names match neither suffix.

## 9. Open Questions

None.

## 10. Revisions

| Date | Change | Reason |
|:--|:--|:--|
| 2026-10-24 | Initial draft | — |
