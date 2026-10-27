# Tests Improvement

- **Scope:** the automated test suite of every Maven module (`libs/*`, `apps/*`) and its CI pipeline
- **Nature:** strategy spec. Objective and high-level requirements; the *how* lives in [`testing-guideline.md`](../../testing-guideline.md)
- **Status:** Draft for review

## 1. Problem statement

We all know the test pyramid, but we lack a pragmatic guideline, and a guard, to keep our implementation effective. The suite today shows it:

- **Slow:** the full test run takes over 10 minutes. It hurts both human and code-agent feedback loops.
- **Mixed scopes:** unit and integration tests are blended. Complexity, maintenance cost and distrust in verification all rise.

[The Practical Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html) is too broad to be a working reference. Each author optimises for their own change, but the problem is the sum of all changes over time, not something that appears in one day.

## 2. Goals

| ID | Goal |
|:--|:--|
| G1 | A project-specific guideline, derived from the Practical Test Pyramid, that tells an author what kind of test to write and where it lives. |
| G2 | Tests have intentional scope: one reason to fail per test, classified by intent (§4). |
| G3 | A guard that fails the build on violations, and whose every message links back to a guideline rule. |
| G4 | Fast feedback: each stage meets a time budget (§5). |

### Non-goals

- Raising coverage percentage.
- e2e (`tests/e2e`) and perf (`tests/perf`, k6) suites; they are separate stages.
- Rewriting every existing test in one change (see §7).

## 3. Relationship to the existing guideline

`testing-guideline.md` already defines principles, the scope × aspect matrix, stage naming and a placement flow. This spec does **not** duplicate it. It requires the following changes to it:

- Add the intent classification of §4 (core / defensive / edge).
- Give every rule a stable ID (e.g. `TG-3.2`) so the guard can cite it.
- Define what a sociable unit test must satisfy to count as narrow and stable.

## 4. Requirements: test intent

- **R1 Single reason to fail.** A test declares one risk (one matrix row) and asserts only what that row allows.
- **R2 Classification.** Each test for a class is one of: **core behavior** (the contract), **defensive** (invalid input, collaborator failure), **edge case** (boundaries). Order and naming make this visible. *(How it is expressed, e.g. nested class or tag, is an open question.)*
- **R3 Narrow, stable sociable tests.** A sociable unit test uses real in-process collaborators but must not break when an unrelated collaborator's internals change.

## 5. Requirements: stages and budgets

- **R4 Stage purity.** `*Test` (Surefire) never starts Spring, a container, a DB or a socket. `*IntegrationTest` (Failsafe) declares exactly one aspect and asserts no business rules.
- **R5 Time budgets.** Per stage, measured in CI:

| Stage | Budget | Status |
|:--|:--|:--|
| Unit (`make test-unit`) | **TBD** | open |
| Integration (`make test-integration`) | **TBD** | open |

## 6. Requirements: guard

- **R6 Automated.** Stage purity (R4) is enforced by ArchUnit rules in `polaris-test-conventions`, applied to every module.
- **R7 Traceable.** Every violation message states the rule ID and guideline link, e.g. `[TG-4.1] *Test must not use @SpringBootTest. See docs/development/testing-guideline.md#4`.
- **R8 Escape hatch.** Exceptions are explicit and reviewable (mechanism TBD); a silent bypass is not allowed.

*Not guarded in this spec:* single-reason-to-fail (R1/R2) is review-enforced for now; time budgets are measured but not build-failing.

## 7. Adopting on the existing suite

Today: 26 + 27 + 7 + 2 integration tests across `polaris`, `polaris-assistant`, `polaris-outbox` and `polaris-fulfilment-emulator`; `polaris-common` and `polaris-events` have one unit test each.

- Inventory misplaced tests per module, then fix module by module (one vertical slice each, per AGENTS.md Principle 2).
- Per guideline §7, a higher-level test is deleted only after a lower-level test covers the same rule.
- Until a module is fixed, the guard runs in warn mode for it. *(Confirm.)*

## 8. Success criteria

- [ ] Guideline rules carry stable IDs and include R2/R3.
- [ ] Guard runs in CI for every module and every message cites a rule ID.
- [ ] Unit and integration stages meet their budgets on CI.
- [ ] No `*Test` starts a Spring context or container.

## 9. Open questions

1. What are the stage budgets (R5)? Baseline first: measure today's per-stage and per-module time.
2. How is a test's intent (R2) expressed so it can later be guarded: nested classes, `@Tag`, or naming?
3. Warn-then-fail rollout per module (§7), or fail from day one with an allowlist?
4. Is the contract stage (OpenAPI/CloudEvents) in scope here or its own spec?
