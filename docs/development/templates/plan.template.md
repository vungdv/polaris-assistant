<!--
PLAN TEMPLATE. Copy to docs/development/plan/<plan-name>.md (or <plan-name>/README.md for a multi-file plan).

A plan DECOMPOSES a big change into slices. It does not specify behavior in detail.
Rule: if a fact describes how one slice behaves, it belongs in that slice's spec, and the plan links to it.
The plan owns only: goal, requirements, ordering/dependencies, shared contracts, risks, and status.
Delete these comments when done.
-->

# <Plan title>

- **Scope:** <what this plan delivers, one or two lines>
- **Out of scope:** <what it deliberately does not>
- **Depends on:** <plans/ADRs, or —> · **Unblocks:** <plans, or —>
- **Status:** Draft for review <!-- Draft for review → Approved → In progress → Done -->

## Goal

<The outcome in user-visible or operator-visible terms. What works at the end that does not work today?>

## Technical Requirements

<!-- One row per requirement. Each must be testable and is covered by at least one slice. -->

| ID | Requirement |
|:--|:--|
| TR-1 | <testable statement> |
| TR-2 | <testable statement> |

## Shared Contracts

<!-- Decisions that more than one slice depends on. Slice specs REFERENCE these and must not redefine them.
     Typical: cross-slice types/events, error format, naming, invariants, config keys, migration ranges. -->

| ID | Contract | Decision | Owning slice |
|:--|:--|:--|:--|
| SC-1 | <e.g. error payload> | <e.g. RFC 7807 with `code` extension> | S1 |

## Decisions

<!-- Open choices that need a human answer, and the ones already made. Link ADRs. -->

| ID | Decision | Status | Resolved in |
|:--|:--|:--|:--|
| D1 | <question> | open / decided: <answer> | <ADR or slice> |

## Spikes (reference only, non-normative)

<!-- Optional. A spike is throwaway code or research used to learn, e.g. to find gaps in the decomposition.
     Spikes are NOT specs. If a spike and a slice spec disagree, the spec wins.
     A code generator must not copy spike code unless a slice spec cites it explicitly. -->

| ID | Question it answered | Location (branch/path) | Finding |
|:--|:--|:--|:--|
| SP-1 | <what we wanted to learn> | <branch or path> | <what we learned; link into affected slice specs> |

## Slices

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Spec | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | S1 | <title> | [spec](../specs/<plan-name>/S1-<slug>.md) | `todo` | — | | | |
| 2 | S2 | <title> | `not written` | `todo` | — | | | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

**Spec states** (kept in the spec's own header): `not written` → `draft` → `in-review` → `approved`. Code generation for a slice starts only when its spec is `approved`.

### S1: <title>
**Covers:** TR-1, SC-1. **Spec:** [S1](../specs/<plan-name>/S1-<slug>.md). **Depends on:** —
- <Two to five bullets: the slice's boundary and the observable outcome. No acceptance-level detail here.>

### S2: <title>
**Covers:** TR-2. **Spec:** not yet written. **Depends on:** S1
- <boundary and outcome>

<!-- Slice sizing rule (Principle 2): as small as possible, never smaller than one vertical slice
     (controller → service → repository → DB) inside ONE bounded context. A multi-context feature is split per context,
     with the contract between them listed under Shared Contracts. -->

## Risks and Open Questions

| ID | Risk / question | Affects | Mitigation / owner |
|:--|:--|:--|:--|
| R1 | <what could invalidate the decomposition> | S2, S3 | <e.g. spec the riskiest slice first> |

## Definition of Done

- [ ] Every slice is `done` and every TR maps to at least one passing test.
- [ ] Each approved slice spec has been folded into the living spec/docs for its bounded context.
- [ ] <plan-level end-to-end check, e.g. a named e2e suite or smoke script>
- [ ] Open Decisions are resolved or explicitly deferred.

## Change Log

<!-- Update whenever a slice spec approval changes the decomposition, a shared contract, or ordering. -->

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| YYYY-MM-DD | <what changed in the plan> | <spec review / finding / user decision> | S2 |
