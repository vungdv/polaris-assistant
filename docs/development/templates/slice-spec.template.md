<!--
SLICE SPEC TEMPLATE. Copy to docs/development/specs/<plan-name>/<slice-id>-<slug>.md

The spec is the source of truth for ONE slice, inside ONE bounded context. Code and tests are derived from it.
- Write for a reader who has not seen the conversation. Do not rely on the plan for behavior.
- Every acceptance criterion must be testable and must map to a test.
- Reference plan Shared Contracts by ID. Never redefine them.
- Scale to the slice: a tiny slice needs only Summary, Acceptance Criteria and Test Mapping.
Delete these comments when done.
-->

# <Slice ID>: <title>

- **Plan:** [<plan name>](../../plan/<plan-name>.md) · **Slice:** <S1>
- **Bounded context:** <one context only>
- **Covers:** <TR-1, TR-2> · **Uses shared contracts:** <SC-1>
- **Depends on:** <slice IDs, or —>
- **Spec state:** draft <!-- draft → in-review → approved. Approved specs change only through a Revision entry below. -->
- **Spec approved by / date:** — 

## 1. Summary

<Two to four sentences: what this slice delivers and why, in terms of observable behavior.>

## 2. Scope

**In scope**
- <behavior or capability>

**Out of scope** (so the generator does not invent it)
- <explicitly excluded behavior, later slice, or other context>

## 3. Behavior

<The core of the spec. Describe what the system does, not how it is coded. Use whichever fits:
 flows as numbered steps, state transitions, rules as bullets, small tables for input→output.
 Include error and edge paths here, not only the happy path.>

### 3.1 Happy path
1. <step>

### 3.2 Rules and invariants
- <rule that must always hold>

### 3.3 Error and edge cases
| Condition | Expected result |
|:--|:--|
| <e.g. duplicate request with same Idempotency-Key> | <e.g. 200 with the original response, no new side effect> |
| <e.g. downstream unavailable> | <e.g. 503 Problem Details, nothing persisted> |

## 4. Interfaces

<!-- Only what this slice adds or changes. Standard formats: OpenAPI 3.x for REST, CloudEvents for events.
     Link to schema files rather than pasting large ones. Remove sub-sections that do not apply. -->

### 4.1 REST
| Method + path | Request | Success | Errors (RFC 7807) |
|:--|:--|:--|:--|
| `POST /api/v1/<resource>` | <body/headers> | `201` <body> | `400`, `409`, ... |

### 4.2 Events (CloudEvents)
| Type | Direction | Payload schema | Ordering/key |
|:--|:--|:--|:--|
| `<type>.v1` | publishes / consumes | <link> | <key> |

### 4.3 Data
- **Migration:** <Flyway version, forward-only, additive>. <tables/columns/indexes>
- **Ownership:** only this context reads or writes these tables.

### 4.4 Configuration
| Key | Source | Default | Notes |
|:--|:--|:--|:--|
| `<ENV_VAR>` | environment | <value> | no secrets in the repo |

## 5. Non-Functional Requirements

<!-- Include only what is relevant, with measurable limits. Principle 3 baseline applies to every slice. -->

- **Observability:** `traceparent` propagated on <entrypoints>; spans record status/exceptions; structured logs carry `trace_id`/`span_id`; metrics: <invocations, latency histogram, error rate, plus any slice-specific>.
- **Resilience:** <timeouts, retry/backoff, circuit breaker, idempotency, as applicable>.
- **Security:** <authn/authz rules, scopes, data sensitivity>.
- **Performance:** <e.g. p95 < 300 ms at N rps>.

## 6. Acceptance Criteria

<!-- Given/When/Then or an equally precise testable statement. IDs are referenced by tests and the review checklist. -->

| ID | Criterion | Covers |
|:--|:--|:--|
| AC-1 | Given <state>, when <action>, then <observable result>. | TR-1 |
| AC-2 | Given <state>, when <failure>, then <observable result>. | TR-1 |

## 7. Test Mapping

<!-- Every AC maps to at least one test at the production-equivalent seam (REST, OIDC, OTLP, real DB).
     Mock only at external boundaries. Add telemetry-propagation and timeout tests where relevant. -->

| AC | Test level | Test (class/file#name, or `to be written`) |
|:--|:--|:--|
| AC-1 | integration (REST + real Postgres) | `to be written` |
| AC-2 | integration | `to be written` |

## 8. Design Notes (optional)

<!-- Only when a non-obvious decision is needed. Keep short; promote anything lasting to an ADR. 
     Spike references go here and are NON-NORMATIVE: the spec above wins on any conflict. -->

- **Constraints from the codebase:** <existing patterns to follow, with file paths>
- **Decisions:** <decision and reason, or ADR link>
- **Spike reference (non-normative):** <SP-1 link, what it showed, what to NOT copy>

## 9. Open Questions

<!-- Must be empty before the state can become `approved`. -->

| # | Question | Owner | Resolution |
|:--|:--|:--|:--|
| Q1 | <question> | <person> | <answer, once known> |

## 10. Review Checklist (for the human spec review)

- [ ] Behavior is unambiguous: two different engineers (or generators) would build the same thing.
- [ ] Error and edge paths are specified, not only the happy path.
- [ ] Out-of-scope is explicit.
- [ ] Every AC is testable and appears in Test Mapping.
- [ ] Shared contracts are referenced, not redefined; no other bounded context's internals are touched.
- [ ] Interfaces use standard formats (OpenAPI, CloudEvents, RFC 7807); no secrets or hardcoded config.
- [ ] Open Questions is empty.

## 11. Revisions

<!-- After approval, any change lands here first, then code is regenerated. Never patch code without a spec change. -->

| Date | Change | Reason | Plan impact |
|:--|:--|:--|:--|
| YYYY-MM-DD | Initial draft | — | — |
