# Q2: Separate unit and integration stages

- **Plan:** [Tests Improvement](../../plan/2026-10-tests-improvement/02.plan.md) · **Slice:** Q2
- **Bounded context:** Build and test tooling (cross-module build configuration; no production code)
- **Covers:** TR-I3, TR-I4 · **Uses shared contracts:** SC-1 (taxonomy, referenced only) · **Owns:** SC-2, SC-3, SC-4 · **Resolves:** D1, D2, D3
- **Depends on:** Q1 (done)
- **Spec state:** in-review <!-- draft → in-review → approved. Approved specs change only through a Revision entry below. -->
- **Spec approved by / date:** — (approval is the PR review of this slice)

## 1. Summary

Today `*Test` and `*IntegrationTest` run in the same Surefire pass, and 30 classes named `*Test` start a Spring context or a real boundary (WireMock, Testcontainers). This slice makes the suffix mean what the [testing guideline](../../testing-guideline.md) says: `*Test` is unit and runs in the unit stage; `*IntegrationTest` is integration and runs in its own stage. Each stage is invocable alone from Maven and `make`, and a build guard rejects a misplaced test.

## 2. Scope

**In scope**
- Unit stage (Surefire) runs `*Test` only; integration stage (Failsafe) runs `*IntegrationTest` only, in every Maven module.
- Entry points: `make test-unit`, `make test-integration`, `make test` (both), `make test-guard`, and the matching Maven commands.
- Aspect declaration mechanism (SC-3) and the guard (TR-I4).
- Renaming the `*Test` classes that boot Spring or open a real boundary to `*IntegrationTest` (rename only, no behavior change).
- Coverage (JaCoCo) still merges unit and integration execution for Sonar.
- Update the places that state the old commands (guideline §4, `Engineer-Guidelines.md` §4, README).

**Out of scope**
- Moving business rules down into unit tests, deleting duplicates, tagging existing integration tests with their aspect (Q5 to Q7).
- k6 migration of concurrency and time-budget tests (Q4). The two known concurrency tests are only renamed here.
- Pyramid report (Q3), contract stage (Q8), CI job split (Q9; CI already runs `mvn verify`, which now runs both stages).
- Speed work (containers, forks, context caching).

## 3. Behavior

### 3.1 Happy path
1. `make test-unit` (= `mvn test`) compiles each module and runs only classes matching `*Test` that are not `*IntegrationTest`. No Spring context, container or socket is started.
2. `make test-integration` (= `mvn verify -DskipUnitTests=true`) runs only `*IntegrationTest` through Failsafe, then verifies the results.
3. `make test` (= `mvn clean verify`) runs the guard, the unit stage, then the integration stage. JaCoCo writes one report covering both.
4. `make test-guard` runs the guard alone. The guard also runs in the `process-test-classes` phase of every module build (skipped with `-DskipTests`, as in the Docker image builds), so a violation fails the build before any test runs.

### 3.2 Rules and invariants
- **Naming → stage (SC-2).** `*IntegrationTest` → integration stage (Failsafe). Any other `*Test` → unit stage (Surefire). Contract and journey stages are decided in Q8 and are not affected.
- **Aspect declaration (SC-3).** An integration test declares its aspect with the standard JUnit tag `@Tag("aspect:<name>")` on the class. `<name>` is one of `persistence`, `web`, `boundary`, `resilience`, `cross-cutting`, mirroring the integration rows of the guideline §3 matrix. Stage selection by aspect works with the standard `-Dgroups` / `-DexcludedGroups` options.
- **Guard rule A.** A `*Test` file (not `*IntegrationTest`) under `src/test/java` must not contain any of: `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`, `@JdbcTest`, `@JsonTest`, `@RestClientTest`, `@AutoConfigureMockMvc`, `@ContextConfiguration`, `SpringExtension`, `ApplicationContextRunner`, `SpringApplication`, `@Testcontainers`, `@EmbeddedKafka`, `WireMockExtension`, `WireMockServer`, `@WireMockTest`, `ServerSocket`.
- **Guard rule B.** An `*IntegrationTest` file must contain `@Tag("aspect:<name>")` with an allowed name, unless its path is listed in `scripts/test-stage-baseline.txt`.
- **Baseline (ratchet).** The baseline lists the integration tests that exist today without an aspect. It may only shrink: Q5 to Q7 tag a test and remove its line. A new untagged integration test is a guard failure. A baseline line whose file no longer exists, or whose file already carries a valid tag, is also a guard failure, so the list cannot go stale.
- Surefire and Failsafe use the same JVM `argLine` (JaCoCo agent plus the Mockito agent where the module uses Mockito).

### 3.3 Error and edge cases
| Condition | Expected result |
|:--|:--|
| A `*Test` contains `@SpringBootTest` | Guard exits non-zero, prints `file: rule A: <matched token>`; build fails before tests run. |
| An `*IntegrationTest` has no `@Tag("aspect:...")` and is not in the baseline | Guard exits non-zero, prints `file: rule B: missing aspect tag`. |
| `@Tag("aspect:unknown")` | Guard exits non-zero (name not in the allowed list). |
| Baseline line for a deleted or already tagged file | Guard exits non-zero, prints `baseline: stale entry <path>`. |
| Module has no `src/test/java` (for example the parent aggregator) | Guard passes for that module. |
| Module has no integration tests (`libs/polaris-common`, `libs/polaris-events`) | Failsafe runs zero tests and succeeds. |
| `make test-integration` with no Docker | Integration tests fail as Testcontainers does; unit stage is unaffected. |

## 4. Interfaces

### 4.1 Build and entry points (SC-4)
| Command | Stages |
|:--|:--|
| `make test-unit` / `mvn test` | guard, unit |
| `make test-integration` / `mvn verify -DskipUnitTests=true` | guard, integration |
| `make test` / `mvn clean verify` | guard, unit, integration |
| `make test-guard` / `scripts/check-test-stages.sh` | guard only (script takes module directories, default all) |

Per module: `mvn test -pl apps/polaris`, `mvn verify -pl apps/polaris -DskipUnitTests=true`.

### 4.4 Configuration
| Key | Source | Default | Notes |
|:--|:--|:--|:--|
| `skipUnitTests` | Maven property | `false` | Skips Surefire only; `skipITs` (standard Failsafe property) skips integration only. |

## 5. Non-Functional Requirements

- **Observability:** not applicable (build tooling). The guard prints one line per violation with the file path.
- **Performance:** `make test-unit` starts no Spring context and no container; the guard adds under 2 seconds for the whole repository (shell + grep).
- **Security:** no secrets, no network access added by the guard.

## 6. Acceptance Criteria

| ID | Criterion | Covers |
|:--|:--|:--|
| AC-1 | Given the repository after this slice, when `mvn test` runs, then no class named `*IntegrationTest` is executed and no Testcontainers or Spring context is started (Surefire report lists only unit classes). | TR-I3 |
| AC-2 | Given a Docker daemon, when `mvn verify -DskipUnitTests=true` runs, then only `*IntegrationTest` classes run (Failsafe reports) and Surefire runs none. | TR-I3 |
| AC-3 | When `mvn clean verify` runs, then both stages run, and `target/site/jacoco/jacoco.xml` includes coverage from both. | TR-I3 |
| AC-4 | Given a `*Test` file containing `@SpringBootTest`, when the guard runs, then it fails and names the file and rule. | TR-I4 |
| AC-5 | Given an `*IntegrationTest` with no valid aspect tag and no baseline entry, when the guard runs, then it fails and names the file. | TR-I4 |
| AC-6 | Given a stale baseline entry, when the guard runs, then it fails. | TR-I4 |
| AC-7 | Given the repository after this slice, when the guard runs, then it passes (all 30 misnamed classes renamed, baseline lists every untagged integration test). | TR-I4 |
| AC-8 | `make test-unit`, `make test-integration`, `make test`, `make test-guard` exist and map to the commands in §4.1. | SC-4 |

## 7. Test Mapping

| AC | Test level | Test |
|:--|:--|:--|
| AC-1 | build run | `make test-unit`; check `target/surefire-reports` has no `*IntegrationTest` and `target/failsafe-reports` is absent |
| AC-2 | build run | `make test-integration`; check `target/failsafe-reports`, Surefire reports absent |
| AC-3 | build run | `make test`; inspect `jacoco.xml` and `jacoco.exec` |
| AC-4, AC-5, AC-6 | guard self-test | `scripts/test/check-test-stages.test.sh` (fixture directories with one violation each, asserts exit code and message) |
| AC-7 | build run | `scripts/check-test-stages.sh` on the repository exits 0 |
| AC-8 | build run | `make -n <target>` prints the expected command |

## 8. Design Notes

- **D1 resolved: Surefire + Failsafe.** The standard Maven lifecycle (`test` / `integration-test` / `verify`), the same `mvn verify` that CI already runs, and no custom executions. Surefire excludes `**/*IntegrationTest.java`; Failsafe includes only it.
- **D2 resolved: `@Tag("aspect:<name>")`, not a custom meta-annotation.** The plan leaned to a meta-annotation, but that needs a shared test-support dependency for every module, which does not exist today. The JUnit tag is the standard mechanism (Principle 1), needs no new module, and can still be wrapped in a meta-annotation later without changing the tag values.
- **D3 resolved: build check script, not ArchUnit.** A shell script over source files needs no dependency and fails before the test stages (it cannot run in `validate`: exec resolves test dependencies, which sibling modules lack in a validate-only reactor). It is heuristic (text match), which is acceptable for a ratchet; ArchUnit can replace it if the heuristic proves too weak. Wired through `exec-maven-plugin` in a parent-pom profile that activates only in modules with `src/test/java`.
- **Nested classes.** Setting Surefire `excludes` replaces its default `**/*$*`, so nested JUnit classes of an excluded outer class would run in the unit stage. The configuration keeps `**/*$*` excluded; `@Nested` classes run through their outer class.
- **Rename policy.** Only the 30 `*Test` classes matching guard rule A are renamed (class, file, and in-repo references). `git mv` keeps history. No assertion changes.
- **Constraints from the codebase:** parent `pom.xml` already binds JaCoCo `prepare-agent` and Surefire `argLine`; `libs/polaris-events` overrides the Surefire `argLine` (no Mockito) and needs the same for Failsafe.

## 9. Open Questions

| # | Question | Owner | Resolution |
|:--|:--|:--|:--|
| — | — | — | — |

## 10. Review Checklist (for the human spec review)

- [ ] Behavior is unambiguous: two different engineers (or generators) would build the same thing.
- [ ] Error and edge paths are specified, not only the happy path.
- [ ] Out-of-scope is explicit.
- [ ] Every AC is testable and appears in Test Mapping.
- [ ] Shared contracts are referenced, not redefined; no other bounded context's internals are touched.
- [ ] Interfaces use standard formats; no secrets or hardcoded config.
- [ ] Open Questions is empty.

## 11. Revisions

| Date | Change | Reason | Plan impact |
|:--|:--|:--|:--|
| 2026-10-23 | Initial draft | — | Decisions D1–D3 resolved here |
