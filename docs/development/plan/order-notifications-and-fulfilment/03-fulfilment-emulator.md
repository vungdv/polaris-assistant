# Plan 3: Multi-Partner Fulfilment

- **Programme:** [Order Notifications & Multi-Partner Fulfilment](README.md) (traceability, TR-X, decisions D1–D5)
- **Depends on:** [Plan 2](02-message-broker.md)
- **Status:** Draft for review

## Goal

Several emulated partners receive every new order, race to claim it, and the single winner drives it through packed, dispatched and delivered. Order owns the rules (first-wins claim, guarded transitions) and announces each milestone. Covers PRD-007 FR-2, FR-4, FR-5 and Scenarios 2, 4–7.

## Technical Requirements

**Order context**

| ID | Requirement |
|:--|:--|
| TR-O2 | **Claim is first-wins and atomic.** Only a `PLACED` order can be claimed. Under any number of concurrent claims exactly one succeeds: status → `CONFIRMED`, partner and claim time recorded. Every other claim, **including a repeat by the winner**, is rejected without changing state or revealing the winner |
| TR-O3 | Claim and cancel on the same order are serialized: when they race, exactly one succeeds. The DB forbids a fulfilled order without an assigned partner |
| TR-O4 | Shipment reports advance the order `PACKED → PARCELED`, `DISPATCHED → DELIVERING`, `DELIVERED → DELIVERED`, only from the assigned partner and only from the expected previous status. Anything else (wrong partner, duplicate, late, cancelled order) is a no-op, logged with a reason and counted. Unknown orders are skipped, never retried forever |
| TR-O5 | Every status change announces its milestone once, through the outbox (Plan 1), carrying customer, items, total and assigned partner. Transitions follow TR-X7 |
| TR-O6 | The assigned partner is visible in the existing order read paths (REST and MCP `get_order_details`) |

**Claim API** (OpenAPI): `POST /api/v1/orders/{orderNumber}/claim`, body `{ "partnerId": "…" }`, requires permission `order.fulfil`. `POST` because it's a conditional state-transition command (RFC 9110).

| Outcome | Response |
|:--|:--|
| Claimed | `200` order representation, now including `assignedPartner` |
| Not claimable (already claimed by anyone, or not `PLACED`) | `409` Problem Details, type `…/errors/order-not-claimable`, `status: 409` plus current order state in `orderStatus`; no winner identity |
| Unknown order | `404` |
| `partnerId` blank or > 64 chars | `400` |

**Fulfilment emulator** (new stateless app)

| ID | Requirement |
|:--|:--|
| TR-F1 | A configurable set of partners; **each independently receives every offer** (D3) |
| TR-F2 | Each partner claims after a random pause in a configurable range (deterministic in tests) |
| TR-F3 | Only the winner emits packed → dispatched → delivered on `polaris.fulfilment.shipments`, a configurable step delay apart, with its `partnerId`. A loser logs and drops the offer. No claim retries |
| TR-F4 | No DB and no API beyond actuator; authenticates as one confidential service client shared by all partners (`client_credentials`, secret from env); the trace survives the pauses. Shipment events are sent directly, not via an outbox: a lost report only stalls a demo order (accepted) |
| TR-F5 | Starting-offset policy for new partner groups is decided and documented (TR-B6), including what happens to orders placed before the emulator first starts |

## Slices

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | F1 | First-wins claim | `done` | Plan 2 done; Flyway V16 still free | `slice/f1-first-wins-claim` | [#22](https://github.com/vungdv/hometask1/pull/22) | |
| 2 | F2 | Fulfilment emulator | `done` | — | `slice/f2-fulfilment-emulator` | [#23](https://github.com/vungdv/hometask1/pull/23) | |
| 3 | F3 | Shipment progress drives the order | `done` | — | `slice/f3-shipment-progress` | [#24](https://github.com/vungdv/hometask1/pull/24) | |
| 3b | F2b | Emulator starts in its container image | `done` | — | `slice/f2b-emulator-container-start` | [#26](https://github.com/vungdv/hometask1/pull/26) | Added 2026-09-30 after F4 run found the F2 crash |
| 4 | F4 | Fulfilment end-to-end | `done` | — | `slice/f4-fulfilment-e2e` | [#25](https://github.com/vungdv/hometask1/pull/25) | Unblocked by F2b + Keycloak volume reset; `make e2e-fulfilment` passed twice on the full stack |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

### F1: First-wins claim *(Order)*
**Covers:** TR-O2, TR-O3, TR-O5 (confirmed), TR-O6, TR-X1, TR-X4 (V16), Δ1, Δ3, Δ6, Δ7. **Detail design:** not needed; locking follows ADR-0007.
- 3–10 parallel claims → exactly one `200`, the rest `409`, exactly one `order.confirmed.v1`.
- Repeat claim by the winner, a claim on a `CANCELLED` order → `409`, no event.
- Claim racing cancel → one succeeds, the other is rejected; never a cancelled order with a partner, never a confirmed order with restored stock.
- Token without `order.fulfil` → `403`. The `409` body doesn't reveal the winner.
- `assignedPartner` shown by `GET /api/v1/orders/{n}` and MCP `get_order_details`. The migration applies cleanly to the seeded DB.
- Keycloak: the `order.fulfil` permission and the emulator's service client exist in the realm export.

### F2: Fulfilment emulator *(Fulfilment)*
**Covers:** TR-F1–F5, TR-X2, TR-X3. **Detail design: required.**
- One offer → one claim per partner, each with a distinct `partnerId` (claim endpoint stubbed: the external boundary).
- Winner (`200`) → exactly three shipment events, in order, with its `partnerId`. Loser (`409`) → none.
- Seeded delays over 10 offers → more than one distinct winner.
- Starts without a datasource; claim calls carry a service-account token; one trace links offer → claim → shipment events.

*Design questions:* per-partner consumption model; delay scheduling that preserves the trace; token acquisition and refresh; starting-offset policy (TR-F5).

### F3: Shipment progress drives the order *(Order)*
**Covers:** TR-O4, TR-O5 (milestones). **Detail design:** not needed.
- `PACKED → DISPATCHED → DELIVERED` reports → order `DELIVERED`, three milestone events in order.
- Wrong partner, duplicate or late report, or a report for a cancelled order → no change, no event, counted by reason.
- The same report delivered twice → one milestone event. Transition table unit-tested exhaustively.

### F2b: Emulator starts in its container image *(Fulfilment)*
**Covers:** TR-F2, TR-F4 (repairs F2). **Detail design:** not needed.
- The emulator container starts on the runtime JRE image: the seeded claim-pause generator uses an algorithm available on a plain JRE (e.g. `new Random(seed)` / `SplittableRandom`), keeping deterministic seeded behaviour.
- A check runs the built image (or the packaged jar on a JRE-only runtime) and proves it reaches healthy, so a JDK-only dependency cannot slip past the JDK-based tests again.
- Seeded 10-offer test still yields more than one distinct winner.

### F4: Fulfilment end-to-end
**Covers:** Scenarios 2, 4–7. **Detail design:** not needed.
- One-command run (`make` target) against the full stack: a placed order ends `DELIVERED` with an assigned partner and all 5 `order.*.v1` events on Kafka. A batch of 10 orders shows more than one winning partner.
- EM-002 updated for Δ1–Δ3, Δ6, Δ7 (partner claim replaces staff confirm). PRD-002 notes that `CONFIRMED` now means "claimed by a partner".

## Definition of Done

- [x] TR-O2–O6 and TR-F1–F5 verified by automated tests; Scenarios 2, 4–7 pass.
- [x] Parallel claims yield exactly one `CONFIRMED` order and one `order.confirmed.v1`.
- [x] Wrong-partner, duplicate and late reports change nothing and emit nothing.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| 2026-09-30 | Added slice F2b (between F3 and F4): emulator container fails at startup because the seeded random generator (`L32X64MixRandom`) needs `jdk.random`, absent from the JRE runtime image | F4 e2e run exposed it; F2 tests ran on a full JDK; user approved | F2b (new), F4 (unblocks) |
| 2026-09-30 | Claim 409 body reports the current order state as `orderStatus`, keeping the RFC 7807 `status` member as the numeric 409 | Reviewer of PR #22: reusing `status` for the order state breaks RFC 7807 (AGENTS.md Principle 1); user approved | F1 |
| 2026-09-30 | F1: cancelling a `CONFIRMED` (claimed) order keeps today's behaviour; a separate process will own it. The claim-vs-cancel race criterion applies only to concurrent attempts on a `PLACED` order, and the DB check requires a partner for fulfilled statuses but does not forbid one on `CANCELLED` | User decision: cancel of claimed orders needs its own process | F1 |
