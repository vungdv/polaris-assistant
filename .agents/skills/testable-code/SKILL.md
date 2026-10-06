---
name: testable-code
description: Guidelines for writing testable code via TDD/BDD, 3-bucket logical grouping (Happy path, Invalid input, Edge cases), behavioral naming, small test sizing, and single-assertion discipline.
---

# Testable Code & Behavior-Driven Testing Discipline
## 1. Scopes
Identify the test scope by: 
- Unit-Test: system under test responsibility
- Integration-Test: scenario/situation 

## 2. TDD/BDD Workflow
Define the contract (API, DTOs, schemas) → write failing tests expressing behavior → implement minimum code to pass → refactor while keeping tests green.

## 3. Three-Bucket Grouping
Keep the root test class/file, package, and location untouched — standard for test-runner discovery and reporting. Within that class, group tests into three buckets, in order, using nested classes/blocks (`@Nested`, `describe`, or comment sections) and behaviorally-named test cases:

1. **Happy path** — main successful flow, valid inputs, expected outcomes.
2. **Invalid input** — validation/error/rejection handling: missing or malformed fields, auth/permission failures, constraint violations, illegal state transitions, disallowed operations.
3. **Edge cases** — boundaries, null/empty, concurrency, limits, timeouts.

Never move, merge, split, or rename test files/packages to fit the grouping.

## 3. Sizing & Granularity
- One behavior per test; no shared mutable state or execution-order coupling.
- Cap each bucket at ~3–5 cases. Need more? Split the slice, don't inflate the suite.
- 
## 4. Agent Verification Checklist
- Each test fails for one clear reason?
- Happy/invalid/edge trio maps cleanly to the requirement?
- Test class/file/package structure unchanged (nothing moved, merged, or flattened)?
- Names are behavioral, not arbitrary?
- Groups capped at ~3–5 cases, or slice was split?