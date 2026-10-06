# Plan 4: Notification

- **Programme:** [Order Notifications & Multi-Partner Fulfilment](README.md) (traceability, TR-X, decisions)
- **Depends on:** [Plan 2](02-message-broker.md); N2 also needs [Plan 3](03-fulfilment-emulator.md)
- **Status:** Draft for review

## Goal

The shopper receives one email per order milestone, delivered to Mailpit in dev, driven only by order lifecycle events. This plan closes the programme: the full journey runs end to end in one trace. Covers PRD-007 FR-6, FR-7 and Scenarios 1, 3, 8.

## Technical Requirements

| ID | Requirement |
|:--|:--|
| TR-N1 | One email per `order.*.v1` to the order's customer: `placed` lists items and total, `confirmed` names the partner, the others state order number and milestone |
| TR-N2 | The event is the only input; no call back into Order |
| TR-N3 | Channels are pluggable (email enabled now; SMS/push later without touching existing channels) |
| TR-N4 | Duplicate emails on redelivery are accepted (README §7); unknown event types are ignored |
| TR-N5 | Starting-offset policy for the new consumer group is decided and documented (TR-B6); the first start must not email every historical order |
| TR-N6 | Mailpit in compose (pinned, healthchecked), SMTP internal only, UI on `localhost:8025` |
| TR-N7 | Stateless app: no DB, no API beyond actuator; each send logged with `orderNumber`, `ce_id` and `trace_id`, and counted by channel, milestone and outcome |

## Slices

### Slice Tracker

Slices run top to bottom; only the `execute-plan` coordinator edits this table.

| # | Slice | Title | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | N1 | Notification service | `todo` | Plan 2 done | | | |
| 2 | N2 | Full journey & documentation | `todo` | Plan 3 done | | | |

**Statuses:** `todo` → `in-progress` → `in-review` → `approved` (not merged) → `done` (merged), plus `blocked` (reason in *Notes*) and `dropped`.

### N1: Notification service
**Covers:** TR-N1–N7, TR-X2, TR-X3, Scenario 1. **Detail design:** not needed.
- Against real Kafka and Mailpit: 5 lifecycle events (hand-produced) → 5 emails to the customer, asserted through Mailpit's API. The confirmed email names the partner; the placed email lists items and total.
- In the running stack, placing an order delivers "We've received your order" to Mailpit, in the same trace as the placing request.
- An unknown event type → no email, no error.

### N2: Full journey & documentation
**Covers:** Scenarios 1–8, programme Definition of Done. **Detail design:** not needed.
- One-command e2e run (`make` target): a placed order ends `DELIVERED` with a partner and exactly 5 emails in Mailpit, in under a minute.
- Scenario 8: a documented manual Tempo check in `tests/e2e/README.md` showing one trace spanning Order, the partner claims, and Notification.
- EM-002 marked as-built (all Δ1–Δ8, §6 delivery guarantees reflect the outbox).

## Definition of Done

- [ ] TR-N1–N7 verified by automated tests; Scenarios 1 and 3 pass end to end; Scenario 8 recorded.
- [ ] The programme Definition of Done ([README §8](README.md#8-programme-definition-of-done)) is met.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
