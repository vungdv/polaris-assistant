# Product Requirements Document: PRD-007
## Order Lifecycle Notifications & Multi-Partner Fulfilment Emulator

- **Context/Domain:** Order Context (`apps/polaris`), Notification Context (`apps/polaris-notification`, new), Fulfilment Context (`apps/polaris-fulfilment`, new)
- **Target Personas:** Shopper, Fulfilment Partner, Polaris Engineers (demo audience)
- **Status:** Draft
- **Last Updated:** 2026-09-28
- **Event Model Reference:** [EM-002](../../technical/event-models/EM-002-order-lifecycle-notifications-and-fulfilment.md)
- **Related:** [PRD-002](PRD-002-comprehensive-order-apis.md) (order lifecycle & statuses)

---

## 1. Problem Statement & Value Proposition

The shop does not fulfil orders itself. It works with **several fulfilment partners** (warehouses / 3PLs), and any one of them may take a given order. The intended flow is:

1. When an order is placed, it is **offered to every fulfilment partner** at the same time.
2. Any partner may **accept (confirm)** the order. The **first partner to accept wins**; once an order is confirmed by a partner, no other partner can take it.
3. The winning partner then **fulfils** the order — packs it, hands it to delivery, and reports delivery — and **updates the order status** at each step.

Today an order is placed and then goes quiet. The order record can move through `PLACED → CONFIRMED → PARCELED → DELIVERING → DELIVERED` ([PRD-002](PRD-002-comprehensive-order-apis.md)), but:

1. **Nobody takes it.** Orders are not offered to any partner, there is no confirmation step, and orders stay `PLACED` forever unless cancelled.
2. **Nobody is told.** The shopper only learns about progress by asking the AI Assistant or calling staff.

This PRD closes the post-purchase loop with two small applications:

- **Notifications** — tells the shopper by email every time their order reaches a new milestone.
- **Fulfilment (emulator)** — pretends to be **several independent fulfilment partners**. Each one sees every newly placed order and races to accept it; the winner packs, ships and delivers it.

It is deliberately a **happy-path demo**. Its secondary purpose is to show how an event-driven integration (Kafka + Spring Boot) — including a **competing-claim (first-wins)** pattern — fits into the existing Polaris stack alongside REST, MCP and OpenTelemetry.

---

## 2. Personas & Use Cases

### Persona A: Shopper
Places an order (directly or through the AI Assistant) and wants to know what is happening with it without asking.

### Persona B: Fulfilment Partner (emulated)
One of several external warehouses/3PLs contracted by the shop. Is offered every new order, decides whether to accept it, and — if it wins the order — fulfils it and keeps the order status up to date. In this PRD all partners are simulated by the Fulfilment emulator.

### Persona C: Polaris Engineer (demo audience)
Wants to watch one order being offered to several partners, claimed by exactly one, and carried through three independent applications over Kafka — visible as a single distributed trace in Grafana and as emails in Mailpit.

### Primary Use Case: "Follow my order"
1. Shopper places an order → receives **"We've received your order"** email. The order is offered to all partners.
2. One partner accepts first → order is confirmed and assigned to that partner → shopper receives **"Your order is confirmed"** email (naming the partner). All other partners' acceptances are turned down.
3. The assigned partner packs the order → **"Your order is packed"** email.
4. The assigned partner hands the parcel to delivery → **"Your order is on its way"** email.
5. The assigned partner reports delivery → **"Your order has been delivered"** email.

---

## 3. Business Vocabulary

The request uses shopper-friendly words; the Order Context already has status names for the same milestones. They are the **same milestones** — no new statuses are introduced.

| Milestone (shopper wording) | Order status (existing, [V1 schema](../../../libs/polaris-common/src/main/resources/db/migration/V1__init_schema.sql)) | Who causes it |
| :--- | :--- | :--- |
| Order drafted / received | `PLACED` | Shopper (or staff on their behalf) places the order; the order is offered to all partners |
| Order confirmed | `CONFIRMED` | The first fulfilment partner to accept the order |
| Order packaged | `PARCELED` | The assigned partner packs the order |
| Order delivering | `DELIVERING` | The assigned partner hands the parcel to delivery |
| Order delivered | `DELIVERED` | The assigned partner reports delivery |

Additional terms:

| Term | Meaning |
| :--- | :--- |
| **Offer** | Making a `PLACED` order visible to every fulfilment partner so they can accept it. |
| **Accept / Claim** | A partner's request to take an offered order. Only one claim per order can succeed. |
| **Assigned partner** | The partner whose claim succeeded. Recorded on the order; the only partner allowed to advance it further. |

> **Why "drafted" = `PLACED`:** the running system has no persisted draft ([EM-001](../../technical/event-models/EM-001-order-staging-out-of-stock-exception.md) §2) — `PLACED` is the first state an order is ever stored in, and it is still awaiting confirmation. Emailing on an unpersisted, in-chat draft would notify the shopper about something that may never exist.

---

## 4. Functional Requirements

### FR-1: Orders are Offered to All Partners (Order Context → Fulfilment Context)
- When an order becomes `PLACED`, every configured fulfilment partner is informed of it at the same time.
- The offer carries what a partner needs to decide and fulfil: order number, line items, total and the time it was placed.

### FR-2: First Partner to Accept Wins (Order Context)
- A partner accepts an offered order by claiming it, identifying itself.
- The Order Context decides the outcome: the **first** claim for an order in `PLACED` status succeeds — the order moves to `CONFIRMED` and the claiming partner is recorded as the **assigned partner**.
- Every later claim for the same order (from any partner, including a repeat from the winner) is turned down as a business conflict and changes nothing. The decision must hold even when claims arrive at virtually the same moment.
- A claim for an order that is not `PLACED` (e.g. cancelled) is turned down the same way.
- Losing partners are told they lost and drop the order.

### FR-3: Order Milestones are Announced (Order Context)
- Every time an order reaches one of the five milestones in §3, the Order Context announces it, once, after the change has been saved.
- The announcement carries enough information for a notification to be written without asking the Order Context anything else: order number, milestone, customer name and email, line items, total, the assigned partner (from `CONFIRMED` onwards), and the time it happened.
- The Order Context remains the **only** owner of order status. Partners *request* status changes through the Order Context's published contract; they never change an order directly.

### FR-4: Multi-Partner Fulfilment Emulator (Fulfilment Context)
- The emulator runs a **configurable list of partners** (default: three, e.g. `partner-north`, `partner-central`, `partner-south`). Each behaves as an independent partner with its own identity.
- Each partner receives every offer and, after a short random pause (configurable, so that different partners win different orders), tries to claim it.
- Only the partner whose claim succeeds continues. It simulates three steps, one after another, with a short configurable pause between them (default: a few seconds, so a demo completes in under a minute):
  1. **Pack** the order.
  2. **Dispatch** the parcel to delivery.
  3. **Deliver** the parcel.
- After each step the partner reports it to the Order Context, which turns the reports into the `PARCELED`, `DELIVERING` and `DELIVERED` milestones.
- No warehouse stock, partner capacity, courier, address validation or failure handling is simulated.

### FR-5: Order Status Follows the Assigned Partner (Order Context)
- When the assigned partner reports a step, the Order Context moves the order to the matching status.
- A report from any partner other than the assigned partner is rejected and changes nothing.
- A report that arrives more than once, or for an order already past that point, changes nothing (safe to replay).

### FR-6: Shopper Notifications (Notification Context)
- The shopper receives one email per milestone (five in total for a completed order), addressed to the customer's email on file.
- Each email states the order number and the milestone in plain language; the first email also lists the items and total; the "confirmed" email names the fulfilment partner.
- Losing claims never produce an email — the shopper sees exactly one "confirmed" email regardless of how many partners tried.
- **Channels:** email is the only channel delivered now and is the default. The design must allow further channels (e.g. SMS, push) to be added later without changing the Order or Fulfilment applications.
- **Local development:** emails are captured by **Mailpit** (no real email is sent); a developer can open the Mailpit web UI and see every message.

### FR-7: One Journey, One Trace (Cross-cutting)
- The whole journey of one order — placement, offer to all partners, every partner's claim (winning and losing), three fulfilment steps, five emails — can be followed in Grafana as connected traces, consistent with the existing observability standards ([PRD-004](PRD-004-gemini-model-observability-and-distributed-tracing.md)–[PRD-006](PRD-006-genai-observability-and-tool-audit.md)).

---

## 5. Acceptance Criteria (Given / When / Then)

### Scenario 1: Placed order is announced, emailed and offered
- **Given** customer Alice Tran (email `alice.tran@example.com`), three emulated partners, and the full stack running with Kafka and Mailpit.
- **When** an order `ORD-xxxxx` is placed for Alice.
- **Then** the order is `PLACED`,
- **And** within a few seconds Mailpit shows one email to `alice.tran@example.com` saying the order was received, listing the items and total,
- **And** all three partners receive the offer.

### Scenario 2: First partner to accept wins
- **Given** order `ORD-xxxxx` is `PLACED` and has been offered to all three partners.
- **When** all three partners try to claim it.
- **Then** exactly one claim succeeds; the order is `CONFIRMED` with that partner as the assigned partner,
- **And** the other two claims are turned down as business conflicts,
- **And** Alice receives exactly one "confirmed" email naming the assigned partner.

### Scenario 3: Assigned partner drives the order to delivered
- **Given** order `ORD-xxxxx` has just been confirmed by `partner-central`.
- **When** `partner-central` completes pack, dispatch and deliver.
- **Then** the order passes through `PARCELED` → `DELIVERING` → `DELIVERED`, in that order,
- **And** Alice receives "packed", "on its way" and "delivered" emails, in that order,
- **And** Mailpit shows exactly five emails for the order.

### Scenario 4: A claim on an already-taken order is rejected
- **Given** order `ORD-yyyyy` is `CONFIRMED` by `partner-north`.
- **When** `partner-south` tries to claim it.
- **Then** the claim is turned down as a business conflict, the assigned partner remains `partner-north`, and no email is sent.

### Scenario 5: A non-assigned partner cannot advance the order
- **Given** order `ORD-yyyyy` is `CONFIRMED` by `partner-north`.
- **When** `partner-south` reports "packed" for it.
- **Then** the report is rejected, the order stays `CONFIRMED`, and no email is sent.

### Scenario 6: Replayed fulfilment report is harmless
- **Given** order `ORD-xxxxx` is already `DELIVERING`.
- **When** the assigned partner's "packed" report for that order is received again.
- **Then** the order stays `DELIVERING` and no additional email is sent.

### Scenario 7: Different partners win different orders
- **Given** the emulator is running three partners with random claim pauses.
- **When** ten orders are placed.
- **Then** each order is assigned to exactly one partner, and more than one partner appears among the assigned partners.

### Scenario 8: End-to-end trace
- **Given** Scenarios 1–3 have run.
- **When** an engineer searches Grafana for `ORD-xxxxx`'s trace.
- **Then** the trace shows spans from the Order, Fulfilment and Notification applications linked through Kafka, including every partner's claim attempt and which one won.

---

## 6. Out of Scope

1. **Unhappy paths:** failed delivery, returns, lost parcels, email bounces, retries with back-off, dead-letter handling.
2. **Unclaimed orders:** if no partner accepts, the order stays `PLACED`; there is no timeout, re-offer, escalation or staff override.
3. **Partner selection rules:** no routing by region, capacity, price or SLA; no partner rejecting/declining an offer explicitly; no re-assignment once an order is confirmed.
4. **Partner onboarding & authentication:** partners are a static configured list inside the emulator; no partner registry, portal or per-partner credentials.
5. **Cancellation notifications:** cancelling an order does not send an email and does not stop an in-flight fulfilment simulation.
6. **Channels other than email**, per-customer channel preferences, unsubscribe, localisation, HTML branding.
7. **Guaranteed-once delivery:** in rare crash cases a shopper may get a duplicate email, or a milestone email may be lost (see [EM-002](../../technical/event-models/EM-002-order-lifecycle-notifications-and-fulfilment.md) §6). Acceptable for the demo. (The first-wins claim rule itself is **not** relaxed — exactly one partner is ever assigned.)
8. **Real warehouse/courier/partner integration** (still out of scope as in [PRD-002](PRD-002-comprehensive-order-apis.md) §5).
9. **Showing live status in the AI Assistant chat** — the Assistant keeps querying order status on demand as today.

---

## 7. Open Questions

| # | Question | Default assumed in this PRD |
| :--- | :--- | :--- |
| Q1 | Who confirms an order — staff, the shopper, a partner, or automatic on placement? | **A fulfilment partner**, by being the first to accept the offered order (FR-2). Staff no longer confirm. |
| Q2 | Should "drafted" mean the in-chat draft before placement? | No — it means `PLACED` (see §3). |
| Q3 | Which application causes "packaged"? | The assigned partner in the Fulfilment emulator (packing is the natural first fulfilment step). |
| Q4 | Should staff be able to see / override which partner holds an order? | Visible via the order's assigned partner in order queries; no override in this PRD. |
| Q5 | How many partners does the emulator run, and should they ever lose deterministically? | Three by default, configurable; random claim pauses decide the winner. |
| Q6 | Should the shopper be told which partner is fulfilling the order? | Yes, in the "confirmed" email only. |
