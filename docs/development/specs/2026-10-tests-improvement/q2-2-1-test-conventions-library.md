# Q2.2.1: Shared test-conventions library

- **Plan:** [Tests Improvement](../../plan/2026-10-tests-improvement/02.plan.md) · **Slice:** Q2.2.1 · **Depends on:** Q2 (done)
- **Covers:** SC-3, D2, D3 (shared part of TR-I4)
- **Scope:** new test-support module `libs/polaris-test-conventions` holding the aspect annotation and the ArchUnit rules, wired into the parent build. No consumer module uses it yet (Q2.2.2 to Q2.2.5). No production code, no test moved or deleted.
- **Spec state:** draft

## 1. Problem

TR-I4 needs one rule set and one aspect-declaration mechanism for four modules. If each module slice defined its own, the rules would drift. The library is the single source of truth, so it lands first and alone.

## 2. Decisions

| ID | Decision | Reason |
|:--|:--|:--|
| D2 | Custom annotation `@IntegrationAspect(Aspect)`, not `@Tag`. `Aspect` is a closed enum. | Compiler-checked values; a non-repeatable annotation gives "exactly one" for free; Q3 reads it to count by aspect. |
| D3 | ArchUnit rules are plain Java in the library (`TestConventionRules`); each module runs them as a `*Test` via `ArchTests.in(...)`. | Runs in the unit stage in seconds; skipped by `-DskipTests`. |
| D3a | Separate library module, consumed with `<scope>test</scope>`. | No new production dependency; ArchUnit never reaches an image. |

## 3. Behavior

### 3.1 Rules (on test classes of package `vn.danang.polaris`)

| Rule | Applies to | Requirement |
|:--|:--|:--|
| R1 unit tests start no Spring context | `*Test`, not `*IntegrationTest` | Depend on none of `org.springframework.boot.test..`, `org.springframework.test.context..` |
| R2 unit tests open no real boundary | same | Depend on none of `org.testcontainers..`, `com.github.tomakehurst.wiremock..` |
| R3 integration tests declare one aspect | `*IntegrationTest` | Annotated with `@IntegrationAspect`. Wrapped in `FreezingArchRule` so consumers can hold a baseline |
| R4 aspect only on integration tests | classes with `@IntegrationAspect` | Named `*IntegrationTest` |

### 3.2 Aspect values

`PERSISTENCE`, `WEB`, `BOUNDARY`, `RESILIENCE`, `WIRING`: one per integration row of testing guideline §3. Adding a value requires a guideline change first.

### 3.3 Edge cases

| Condition | Expected result |
|:--|:--|
| `-DskipTests` build | Library still compiles; no rule runs |
| Consumer without integration tests | R3 passes with an empty store |
| Freeze store absent | `archunit.properties` (in the library) allows creating it on first run; `freeze.store.default.allowStoreUpdate` stays `false` in CI via the consumer slice |

## 4. Interfaces

- Parent `pom.xml`: add `<module>libs/polaris-test-conventions</module>`, `archunit.version`, and `dependencyManagement` entries for the library and `archunit-junit5`.
- Dockerfiles of `apps/polaris`, `apps/polaris-assistant`, `apps/polaris-fulfilment-emulator`: `COPY libs/polaris-test-conventions/pom.xml` next to the other lib POMs (layer-caching list; the parent now lists the module).
- Guideline §4: document `@IntegrationAspect` and the guard.

```java
@IntegrationAspect(Aspect.PERSISTENCE)
class ProductRepositoryIntegrationTest { ... }
```

## 5. Non-Functional Requirements

- No runtime or production dependency; the library is never in an app jar.
- Rule evaluation adds under 5 s per module (class import only).

## 6. Acceptance Criteria

| ID | Criterion |
|:--|:--|
| AC-1 | Given a `*Test` with a Spring, Testcontainers or WireMock dependency, when R1/R2 run, then they fail naming the class. |
| AC-2 | Given a Spring-free `*Test`, R1 and R2 pass. |
| AC-3 | Given an untagged `*IntegrationTest`, R3 fails; given a tagged one, it passes. |
| AC-4 | Given `@IntegrationAspect` on a class not named `*IntegrationTest`, R4 fails. |
| AC-5 | `mvn test` for the whole reactor passes and `mvn package -DskipTests` builds each app. |
| AC-6 | The app jars do not contain `polaris-test-conventions` or ArchUnit. |

## 7. Test Mapping

| AC | Level | Test |
|:--|:--|:--|
| AC-1 to AC-4 | unit (ArchUnit on fixture classes) | `TestConventionRulesTest` in the library |
| AC-5 | build | `mvn test`, `mvn package -DskipTests` |
| AC-6 | build | jar listing, recorded in the PR |

## 8. Out of scope

Applying the rules to any module (Q2.2.2 to Q2.2.5); tagging or moving tests (Q5 to Q7); counting by aspect (Q3).

## 9. Open Questions

None.

## 10. Revisions

| Date | Change | Reason |
|:--|:--|:--|
| 2026-10-24 | Split out of the single Q2.2 spec (PR #34 closed) | One slice per module |
