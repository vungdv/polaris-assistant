# Q2.2.4: Convention guard in apps/polaris-assistant

- **Plan:** [Tests Improvement](../../plan/2026-10-tests-improvement/02.plan.md) · **Slice:** Q2.2.4 · **Depends on:** [Q2.2.1](q2-2-1-test-conventions-library.md)
- **Covers:** TR-I4 for `apps/polaris-assistant`
- **Scope:** apply the Q2.2.1 rules to `apps/polaris-assistant` only: test-scope dependency, one `TestConventionsTest`, a frozen baseline of its untagged integration tests. No production code; no test moved, renamed or deleted.
- **Spec state:** draft

## 1. Problem

The unit/integration convention (testing guideline §4) is only a convention until the build enforces it. This slice enforces it in one module so it can be reviewed and reverted on its own, and so the module's baseline is visible in its own diff.

## 2. Behavior

- `apps/polaris-assistant/pom.xml` adds `polaris-test-conventions` with `<scope>test</scope>`.
- New `TestConventionsTest` in `apps/polaris-assistant/src/test/java/vn/danang/polaris/` imports only the module's test classes and runs `ArchTests.in(TestConventionRules.class)`.
- R1, R2, R4 have no baseline: any violation fails the unit stage.
- R3 is frozen: the module's existing untagged `*IntegrationTest` classes are recorded in `src/test/resources/archunit_store` (committed). New untagged ones fail; tagging or removing one drops its entry.

| Condition | Expected result |
|:--|:--|
| New `*Test` with Spring/Testcontainers/WireMock | `mvn test -pl apps/polaris-assistant` fails with rule and class |
| New untagged `*IntegrationTest` | `mvn test -pl apps/polaris-assistant` fails |
| Existing untagged `*IntegrationTest` | Passes (in baseline) |
| `-DskipTests` | Guard does not run; module still builds |

## 3. Acceptance Criteria

| ID | Criterion |
|:--|:--|
| AC-1 | `mvn test -pl apps/polaris-assistant` runs `TestConventionsTest` and passes against the committed baseline. |
| AC-2 | Adding an untagged `*IntegrationTest` makes `mvn test -pl apps/polaris-assistant` fail (verified once manually, recorded in the PR). |
| AC-3 | `mvn package -DskipTests -pl apps/polaris-assistant -am` succeeds and the Dockerfile still builds. |
| AC-4 | The baseline lists only classes that exist and are named `*IntegrationTest`. |

## 4. Verification

AC-1: the module's own `TestConventionsTest` (ArchUnit on real classes). AC-2: manual, once. AC-3: build. AC-4: review of the store diff.

## 5. Out of scope

Tagging existing tests or emptying the baseline (Q5 to Q7); other modules; rule changes (Q2.2.1).

## 6. Open Questions

None.

## 7. Revisions

| Date | Change | Reason |
|:--|:--|:--|
| 2026-10-24 | Split out of the single Q2.2 spec (PR #34 closed) | One slice per module |
