---
name: execute-plan
description: Executes a development plan from docs/development/plan/ in the plan's slice order, trunk-based, tracking progress in the plan's Slice Tracker. By default runs the next slice and leaves its PR unmerged; `--all` runs the whole plan. e.g. `/execute-plan docs/development/plan/<plan>.md [--all]`.
argument-hint: <path-to-plan-file> [--all]
---

# Execute a Development Plan (trunk-based)

## Input: plan file (required)

Arguments: `$ARGUMENTS`

| Invocation | Mode |
|------------|------|
| `<plan>` | **Default:** run the next slice in the [plan]'s order. Stop at an approved PR; **never merge it** |
| `<plan> --all` | **All:** run every remaining slice in order, merging each approved PR before starting the next |

Before doing anything else, validate the input:

1. **Missing argument** — if no path was given, STOP. Do not guess or pick a plan yourself. List the files in
   `docs/development/plan/` and ask the user which one to execute.
2. **File not found** — if the path does not exist (also try resolving it relative to `docs/development/plan/`),
   STOP and report the path you tried, plus the available plans in `docs/development/plan/`.
3. **Not a plan** — if the file is not a Markdown file under `docs/development/plan/`, or it contains no
   identifiable slices, STOP and tell the user why it was rejected.
4. **Unknown option** — anything other than `--all` after the path: STOP and show the usage.

Only once the plan file is validated, read it in full. It is the **[plan]** referenced below and the single
source of truth for slices, ordering, and acceptance criteria. Never execute work that is not in the [plan].

## Slice Tracker

Progress and order live in the [plan] itself, in a **Slice Tracker** table. Example:
[`order-notifications-and-fulfilment/01-outbox-event-publishing.md`](../../../docs/development/plan/order-notifications-and-fulfilment/01-outbox-event-publishing.md).

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|

- **Order:** slices run top to bottom, one at a time. The [plan] owns the order; the skill never picks
  another. A slice starts only once every slice above it is `done` or `dropped`.
- **Statuses:** `todo` → `in-progress` → `in-review` → `approved` (PR approved, not merged) → `done` (merged
  to trunk), plus `blocked` (reason in *Notes*) and `dropped` (removed by a plan change).
- **Missing tracker:** if the [plan] has slices but no tracker, add one before the first slice section, one row
  per slice in the [plan]'s order, all `todo`. Show it to the user and get confirmation before starting.
- **Single writer:** only the coordinator edits the tracker. Workers and reviewers report status back; they
  never edit the [plan].
- **Update at every transition** and commit it on trunk as its own docs-only commit:
  `docs(plan): <slice-id> → <status>`. Keep it out of the slice branch, so slice PRs never conflict on the
  [plan].

## Picking the slice

Walk the tracker top to bottom and act on the first row that isn't `done` or `dropped`:

| First open row is | Action |
|-------------------|--------|
| `approved` | Check whether its PR has been merged. If so, mark it `done` and move to the next row. If not: in default mode, STOP and ask the user to merge it; in `--all` mode, merge it (step 7) |
| `in-progress` / `in-review` | An earlier run was interrupted: resume it from its recorded *Branch* and *PR* rather than starting over |
| `blocked` | STOP and report the reason from *Notes*. Continue only when the user says it is resolved |
| `todo` | Start it. If the row lists anything under *External*, first confirm with the user that it is met |

If every row is `done` or `dropped`, report that the [plan] is complete and check its Definition of Done.

## Roles

Execution follows a **hub-spoke** model: you are the hub, and all communication goes through you. The worker
and the reviewer never talk to each other directly.

| Role | Who | Responsible for | Does NOT |
|------|-----|-----------------|----------|
| **Coordinator** | You | Picking the slice, handing it off, keeping the Slice Tracker current, and (in `--all` mode only) merging approved PRs | Write production code or review PRs yourself |
| **Worker** | Sub-agent | Implementing exactly one slice on its own short-lived branch, keeping it vertically complete and tested, then opening a PR | Change scope beyond the slice, edit the [plan], or merge its own PR |
| **Reviewer** | Sub-agent | Reviewing the worker's PR against the slice's acceptance criteria and `AGENTS.md`, and posting feedback as comments on the PR | Push fixes to the branch, edit the [plan], or merge the PR |

### Slice lifecycle

| Step | What happens | Tracker update |
|------|--------------|----------------|
| 1. **Hand-off** | The coordinator hands the slice to a worker | `in-progress` |
| 2. **Implement** | The worker creates a slice branch from trunk, implements the slice, opens a PR, and reports back `PR-created` plus the PR link, or `blocked` plus the reason | *Branch* set; `blocked` + *Notes* if blocked |
| 3. **Review** | The coordinator assigns the PR to a reviewer | `in-review`, *PR* set |
| 4. **Feedback** | The reviewer comments directly on the PR, then reports back `approved` or `changes-requested` | — |
| 5. **Iterate** | If changes are requested, the worker addresses the PR comments; repeat steps 3–4 | stays `in-review` |
| 6. **Approve** | The reviewer approves the PR | `approved` |
| 7. **Merge** | **`--all` mode only.** The coordinator merges the PR to trunk and confirms trunk builds before the next slice | `done` |

- **Default mode ends at step 6.** Never merge the PR: report it to the user and stop. The next run marks the
  slice `done` once it sees the PR merged.
- **`--all` mode** loops back to *Picking the slice* after step 7, until the [plan] is complete or a stop
  condition hits.
- **Stop conditions (both modes):** a `blocked` slice, an unmet *External* gate, a failed merge or trunk
  build, or a proposed plan change awaiting the user's approval. Record the state in the tracker, report, and
  stop.

Every hand-off, to the worker or the reviewer, must include the plan file path and the slice identifier. That
way each agent reads the slice from the [plan] itself, not from the coordinator's summary of it.

## Adjusting the plan

The [plan] can change between slices, or mid-slice when implementation shows it is wrong (a wrong file list,
a missing step, a slice that is too big, an order that doesn't work).

1. **Propose, don't apply:** when a worker or reviewer finds a plan problem, or you do, describe the change to
   the user and wait for approval. Never change scope, order, contracts, decisions or acceptance criteria on
   your own.
2. **Apply on approval:** edit the affected slice sections, and update the tracker rows. A split adds new rows
   in place (e.g. `F2a`, `F2b`), a removed slice becomes `dropped`, and a reorder moves rows.
3. **Log it:** add one row to the [plan]'s **Change Log** (create the section at the end if it's missing):
   date, change, reason, slices affected. Status changes aren't logged there.
4. **Commit** on trunk as `docs(plan): <summary>`, separate from any slice branch.

If the change affects a slice already `in-progress` or `in-review`, tell the worker to re-read the slice from
the [plan] before continuing.

## Run report

End every run with a short report: each slice worked on and its final status, PR links (and whether each is
merged), any plan changes made, and the updated tracker with the next slice.
