---
name: execute-plan-spec
description: Executes a spec-driven plan from docs/development/plan/ slice by slice, generating code only from each slice's approved spec (docs/development/specs/) and reviewing the PR against the spec's acceptance criteria. By default runs the next slice and leaves its PR unmerged; `--all` runs the whole plan. e.g. `/execute-plan-spec docs/development/plan/<plan>.md [--all]`.
argument-hint: <path-to-plan-file> [--all]
---

# Execute a Spec-Driven Plan (trunk-based)

Same hub-spoke, trunk-based flow as `execute-plan`, with one difference: **the slice spec is the source of
truth for a slice, and code is derived from it.** The plan only orders the slices and tracks status. Use
the templates in `docs/development/templates/` (`plan.template.md`, `slice-spec.template.md`).

## Input: plan file (required)

Arguments: `$ARGUMENTS`

| Invocation | Mode |
|------------|------|
| `<plan>` | **Default:** run the next slice in the [plan]'s order. Stop at an approved PR; **never merge it** |
| `<plan> --all` | **All:** run every remaining slice in order, merging each approved PR before starting the next |

Before doing anything else, validate the input:

1. **Missing argument**: STOP. Do not guess. List `docs/development/plan/` and ask which plan to run.
2. **File not found**: also try resolving relative to `docs/development/plan/`. If still missing, STOP and
   report the path tried and the available plans.
3. **Not a spec-driven plan**: the file must be Markdown under `docs/development/plan/`, have identifiable
   slices, and have a **Spec** column in its Slice Tracker. If it has no Spec column, STOP and suggest
   `/execute-plan` for it, or adding specs from the template.
4. **Unknown option**: anything other than `--all` after the path: STOP and show the usage.

Read the plan in full. It is the **[plan]** below. Each slice's spec is the **[spec]**; its path comes from
the tracker's *Spec* column and its *Spec state* from the spec's own header.

## Slice Tracker

Same table, order, statuses, single-writer rule and commit convention as `execute-plan`:

| # | Slice | Title | Spec | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|:--|

- Slices run top to bottom, one at a time. A slice starts only when every row above it is `done` or `dropped`.
- Statuses: `todo` → `in-progress` → `in-review` → `approved` (PR approved, not merged) → `done` (merged),
  plus `blocked` and `dropped`.
- Only the coordinator edits the tracker. Update it at every transition and commit it on trunk as its own
  docs-only commit: `docs(plan): <slice-id> → <status>`. Never on the slice branch.
- Specs are **not** edited by this skill, except where stated under *Spec changes*. The coordinator never
  moves a spec to `approved`; only the user does.

## Picking the slice

Walk the tracker top to bottom and act on the first row that isn't `done` or `dropped`:

| First open row is | Action |
|-------------------|--------|
| `approved` | If its PR is merged, mark it `done` and move on. If not: default mode, STOP and ask the user to merge; `--all`, merge it (step 7) |
| `in-progress` / `in-review` | Interrupted run: resume from the recorded *Branch* and *PR* |
| `blocked` | STOP and report the *Notes* reason. Continue only when the user says it is resolved |
| `todo` | Run the **spec gate** below, then start it. Confirm any *External* item first |

If every row is `done` or `dropped`, report the plan complete and check its Definition of Done.

### Spec gate (before any code is generated)

Read the slice's [spec] and check, in order. On any failure, STOP in both modes, record `blocked` + reason in
*Notes*, and tell the user exactly what is needed. Do not draft, approve or work around it.

1. The *Spec* column links to a file that exists (not `not written`).
2. Its **Spec state** is `approved` and **Spec approved by / date** is filled in.
3. **Open Questions** is empty.
4. Every acceptance criterion (AC) has a row in **Test Mapping**.
5. Every slice it depends on is `done`, and every shared contract (SC-n) it cites exists in the [plan].

Offer, once, to help draft the spec from the template; do not start without approval.

## Roles

| Role | Who | Responsible for | Does NOT |
|------|-----|-----------------|----------|
| **Coordinator** | You | Spec gate, hand-offs, tracker, merging in `--all` mode | Write production code, review PRs, or edit/approve specs |
| **Worker** | Fresh sub-agent | Implementing exactly one slice **from its [spec]**, with tests, on its own branch; opening a PR | Go beyond the spec, edit the [plan], change the spec's behavior, or merge |
| **Reviewer** | Fresh sub-agent | Checking the PR against the [spec] and `AGENTS.md`; commenting on the PR | Push fixes, edit the [plan] or [spec], or merge |

Every hand-off includes the **plan path, slice id and spec path**, and nothing else describing behavior. Agents
read the spec themselves, so a summary from you can't drift from it. Use a fresh sub-agent per role and per
iteration so no stale assumptions carry over.

### Worker rules

- Implement **only** what the [spec] says. Anything in *Out of scope* stays out; no speculative extras.
- Read the plan's **Shared Contracts** the spec cites; use them as defined.
- **Spikes are non-normative.** Do not copy spike code unless the spec explicitly cites it, and then only what it cites.
- Write tests first or alongside. Every AC gets at least one test at the seam named in **Test Mapping**
  (real DB, REST, OIDC and OTLP as in production; mock only at external boundaries). Name or tag each test with
  its AC id (e.g. `AC-2`).
- The one allowed edit to the spec: replace `to be written` in **Test Mapping** with the real test names.
  Nothing else in it, in the same PR.
- Follow `AGENTS.md` (vertical completeness, one bounded context, observability, no hardcoded secrets).
- If the spec is ambiguous, contradictory, or unimplementable: **stop and report `blocked: spec-issue` with the
  exact section.** Never guess, and never silently diverge.
- Report `PR-created` + link in the PR description, include an **AC → code → test** table.

### Reviewer rules

Review in this order and comment on the PR:

1. **Spec conformance:** for each AC, find the code that implements it and the test that proves it. Missing or
   untested AC is a blocker.
2. **Scope:** behavior or interfaces in the diff that the spec doesn't ask for (and out-of-scope items) are
   findings, even if they look useful.
3. **Spec fidelity of interfaces:** REST, events, migration and config match the spec's *Interfaces* exactly.
4. **Spec edits:** the diff must change the spec only in the *Test Mapping* test names.
5. `AGENTS.md` principles: observability, tests, single responsibility, boundary isolation.

Report `approved` or `changes-requested`. Classify each finding as **code defect** (fix the code) or **spec
issue** (the spec is wrong or incomplete). Do not suggest patching code to work around a spec issue.

## Slice lifecycle

| Step | What happens | Tracker update |
|------|--------------|----------------|
| 1. **Hand-off** | Spec gate passes; coordinator hands the slice to a worker | `in-progress` |
| 2. **Implement** | Worker branches from trunk, implements the spec, opens a PR | *Branch* set; `blocked` + *Notes* if blocked |
| 3. **Review** | Coordinator assigns the PR to a reviewer | `in-review`, *PR* set |
| 4. **Feedback** | Reviewer comments on the PR, reports `approved` / `changes-requested` | — |
| 5. **Iterate** | Code defects: a fresh worker addresses the comments; repeat 3–4. Spec issues: see *Spec changes* | stays `in-review` |
| 6. **Approve** | Reviewer approves | `approved` |
| 7. **Merge** | **`--all` only.** Merge to trunk, confirm trunk builds, then continue | `done` |

- Default mode ends at step 6: report the PR and stop. Never merge.
- `--all` loops back to *Picking the slice* after step 7.
- **Stop conditions (both modes):** a failed spec gate, a `blocked` slice, an unmet *External* gate, a failed
  merge or trunk build, or a spec/plan change awaiting the user's decision.
- After a slice is merged (`done`), read the spec's **Plan impact** and **Revisions**; if the plan needs
  updating, follow *Adjusting the plan*. Remind the user to fold the approved spec into the living docs for its
  bounded context if the plan's Definition of Done requires it.

## Spec changes

Specs are the source of truth, so a change goes **spec first, then code**.

1. **Propose, don't apply.** When a worker or reviewer reports a **spec issue**, or you spot one, STOP the
   slice, set it `blocked` (note: `spec-issue`), and show the user the section, the problem and a proposed fix.
2. **The user revises and re-approves the spec.** They edit it (adding a row to its *Revisions* table) and set
   the state back to `approved`. You never edit behavior in a spec yourself.
3. **Regenerate.** When the spec is `approved` again, resume the slice: a fresh worker re-reads the spec and
   updates the code and tests to match. Do not hand-patch around the old code.
4. If the revision changes the decomposition, shared contracts or order, follow *Adjusting the plan*.

## Adjusting the plan

The plan can change between or during slices (wrong boundary, a slice too big, a bad order, a shared contract
that must change).

1. **Propose, don't apply.** Describe the change; wait for the user's approval. Never change scope, order,
   shared contracts or decisions on your own.
2. **Apply on approval.** Edit the affected slice sections and tracker rows (splits add rows in place, e.g.
   `S2a`/`S2b`; removed slices become `dropped`). Specs of affected slices must be re-reviewed if the change
   touches them; set their state accordingly with the user.
3. **Log it** in the plan's **Change Log**: date, change, reason, slices affected.
4. **Commit** on trunk as `docs(plan): <summary>`, separate from any slice branch.

If an `in-progress` or `in-review` slice is affected, tell its worker to re-read the spec before continuing.

## Run report

End every run with: each slice worked on and its final status, PR links (and whether merged), the AC coverage
result for each, any spec issues or plan changes raised, the updated tracker, and the next slice.
