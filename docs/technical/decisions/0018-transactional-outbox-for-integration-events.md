# ADR-0018: Transactional Outbox for Integration Events

* **Status:** Accepted (2026-09-29, with Plan 2 B2: the first real transport, [ADR-0019](0019-kafka-and-cloudevents-binding.md), delivers through this outbox end to end)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-09-29
* **Technical Story:** A reusable, broker-agnostic way for any bounded context that owns a database to publish integration events reliably: an event is stored if and only if the business change commits, then relayed later through a transport port. Order is the first adopter (`order.placed`).
* **Product Reference:** [PRD-007](../../business/prds/PRD-007-order-notifications-and-fulfilment-emulator.md) (FR-1, FR-3, FR-7)
* **Plan Reference:** [Plan 1: Outbox & Generic Event Publishing](../../development/plan/order-notifications-and-fulfilment/01-outbox-event-publishing.md) (decisions D6, D7; departure Δ8)
* **Detail Design:** [E2 — Recording events atomically](../../development/design/order-notifications-and-fulfilment/E2-recording-events-atomically.md), [E3 — Relay to a transport port](../../development/design/order-notifications-and-fulfilment/E3-relay-to-a-transport-port.md)

---

## 1. Context and Problem Statement

PRD-007 turns order milestones into messages: fulfilment partners claim orders from `order.placed.v1`, and shoppers get one email per milestone. [EM-002](../event-models/EM-002-order-lifecycle-notifications-and-fulfilment.md) §6 planned to send each event **after commit** and accepted that an event could be lost if the send failed or the app stopped in between.

That trade-off no longer holds. A lost `order.placed` means no partner ever sees the order and it stays `PLACED` forever; a send before commit announces an order that may roll back. Both the database write and the broker send must happen, or neither, and there is no distributed transaction between PostgreSQL and Kafka that we want to run.

**How should a context publish integration events so that an event exists if and only if its business change commits, without coupling business code to a broker?**

---

## 2. Decision Drivers

* **Correctness (TR-E1, TR-E2):** no phantom events for rolled-back changes and no lost events for committed ones, across broker outages and restarts.
* **Standard patterns ([AGENTS.md Principle 1](../../../AGENTS.md)):** use a named, well-understood pattern and standard mechanisms (Spring transactions, Spring Data domain events, W3C Trace Context, CloudEvents attributes), not a bespoke protocol.
* **Broker independence (TR-E5):** business code depends on a publish abstraction only; Kafka arrives in Plan 2 and could be replaced later.
* **Bounded change scope ([AGENTS.md Principle 2](../../../AGENTS.md)):** reusable by any context with a datasource, without forcing a datasource onto stateless apps (Δ5).
* **Observability (TR-E7, [ADR-0016](0016-genai-observability-and-mcp-audit-standards.md)):** the trace of the request that raised an event continues through delivery.

---

## 3. Considered Options

### Option 1: Send after commit (EM-002 §6, status quo design)
Publish from a `@TransactionalEventListener(AFTER_COMMIT)` straight to the broker.
* **Pros:** simplest; lowest latency.
* **Cons:** an event is lost if the broker is down or the process stops between commit and send; the request either fails after its change committed or silently drops the event. Rejected for `order.placed` (Δ8).

### Option 2: Send inside the transaction
Publish to the broker before commit.
* **Cons:** the event is announced even if the commit then fails (phantom event); the request's latency and availability now depend on the broker. Rejected.

### Option 3: Transactional outbox with an in-app relay — **Chosen**
Insert the event into an `outbox_events` table in the same database transaction as the business change; a relay in the same app reads pending rows and hands them to a transport port, marking them delivered.
* **Pros:** atomic by construction (one local transaction); no new infrastructure; broker outages only delay delivery; the transport is pluggable; per-key ordering and `ce_id` dedupe are natural.
* **Cons:** at-least-once delivery (consumers must tolerate duplicates, keyed by `ce_id`); a relay to operate (backlog, retries, retention); an extra table write per event.

### Option 4: Transactional outbox with CDC (Debezium)
Same table, relayed by change-data-capture from the WAL.
* **Pros:** no polling; lowest relay latency.
* **Cons:** Kafka Connect + Debezium + logical replication configuration to run for three events. Deferred (D7): producers are unchanged if CDC replaces the in-app relay later.

---

## 4. Decision Outcome

**Option 3.** Specifically:

1. **Publish port.** Business code depends only on `IntegrationEventPublisher.publish(IntegrationEvent)` in the new `libs/polaris-outbox` module. An `IntegrationEvent` carries the CloudEvents `type` and `source`, a logical `destination`, the partition `key` (aggregate id) and the `data` payload (a `polaris-events` contract). It names no broker.
2. **Recording.** The outbox implementation serializes `data` to JSON, assigns `ce_id` (UUID) at record time, captures the current W3C `traceparent`/`tracestate`, and inserts one row using the caller's JDBC connection. **Publishing outside a transaction, or inside a read-only one, fails fast** with Spring's `IllegalTransactionStateException`.
3. **Domain vs integration events.** Aggregates raise context-internal domain events from their state-transition methods (Spring Data `AbstractAggregateRoot`); a synchronous in-context translator (`@EventListener`, same thread and transaction) maps them to integration events and publishes them. Asynchronous or after-commit listeners are not allowed on this path.
4. **Storage.** One generic `outbox_events` table (Flyway V15, standard SQL for PostgreSQL and H2) with the event attributes, the JSON payload as text, trace context and delivery state. New event types need no schema change.
5. **Packaging.** `polaris-outbox` auto-configures only when a single `DataSource` is present (`polaris.outbox.enabled` opts out). Stateless apps depend on `polaris-events` only.
6. **Relay** (Plan 1 E3: polling relay, per-key head row locks, capped exponential backoff, behind the `EventTransport` port; see its detail design) and **Kafka transport** (Plan 2, [ADR-0019](0019-kafka-and-cloudevents-binding.md)) are separate decisions built on this one.

---

## 5. Consequences

### Positive
* An event exists if and only if its change commits; broker outages never fail a business request.
* One publishing path and one table for every future event type and context.
* Traces continue from the raising request to consumers via the stored `traceparent`.

### Negative / Operational Safeguards
* **At-least-once:** consumers may see duplicates; `ce_id` is stable across retries so dedupe can be added where it matters (out of scope for Notification, programme §7).
* **Latency:** delivery is asynchronous; TR-E8 targets commit → hand-off under 1 s.
* **Table growth:** delivered rows are purged after a retention period, and backlog and oldest-pending age are exposed as metrics (E3).
* **Discipline:** adopters must save aggregates after every transition so their domain events publish; each adopter's tests assert one event per transition.
* **Per-key ordering precondition ([TR-X8](../../development/plan/order-notifications-and-fulfilment/README.md)):** the relay delivers a key's events in record (`id`) order, which equals commit order only if same-key event-raising transactions are serialised before the event is recorded: a pessimistic lock on the aggregate, or `@Version` so the loser rolls back with its event. Each adopter must meet this for every transition after the first (see the [E3 design §5](../../development/design/order-notifications-and-fulfilment/E3-relay-to-a-transport-port.md)). Plan 1 E4 enforces it on `Order`; `order.placed` alone wouldn't need it because it is the first event of a new key.
