# Detail Design: E3 — Relay to a Transport Port

- **Plan:** [Plan 1: Outbox & Generic Event Publishing](../../plan/order-notifications-and-fulfilment/01-outbox-event-publishing.md), slice **E3**
- **Covers:** TR-E2, TR-E3, TR-E6, TR-E8, TR-X2 (and TR-X4: V15's delivery columns confirmed; E3 adds no `V15_x`, E4 later added `V15_1` for `orders.version`). Relies on TR-X8 (producers serialise same-key changes; §5)
- **Extends:** [E2 — Recording events atomically](E2-recording-events-atomically.md) (same `libs/polaris-outbox` module, same `outbox_events` table)
- **Decision record:** [ADR-0018 Transactional outbox for integration events](../../../technical/decisions/0018-transactional-outbox-for-integration-events.md) (Proposed; Option 3 "in-app relay")
- **Status:** Draft for review

## 1. Scope

In: the transport port a broker adapter implements, the in-app relay that hands pending `outbox_events` rows to it (ordered per key, retried with backoff, safe with several app instances), the retention purge, relay metrics, hand-off tracing and structured logs, and their auto-configuration.

Out: any real transport (Kafka is Plan 2), Order code (E4), a dead-letter state (programme §7: failed events retry and alert instead), and CDC (D7).

## 2. Design questions answered

| Question | Decision |
|:--|:--|
| Relay trigger | **Polling** every `poll-interval` (default **250 ms**), running cycles back-to-back while they deliver something without failure. No post-commit signal (§4) |
| Multi-instance ordering | **Row locks on the head event of each key**: `SELECT … FOR UPDATE SKIP LOCKED`, hand-off and mark in one short relay transaction. No leader election, no lease columns (§5) |
| Backoff limits | Exponential per event: **1 s × 2ⁿ, capped at 5 min**, never given up; a failure also ends the current batch (§6) |
| Transport port shape | `EventTransport.send(OutgoingEvent)`: id, type, source, time, destination, key, JSON payload and the W3C trace context; returns only when the transport has accepted the event (§3) |
| V15 delivery columns and indexes | **Confirmed unchanged.** E3 adds no `V15_x` migration (§8). E4 later added `V15_1` (`orders.version`, TR-X8), which doesn't touch the outbox |

## 3. The transport port

Package `vn.danang.polaris.outbox.transport` (the relay's only outward dependency; a broker adapter depends on this package alone):

```java
public interface EventTransport {
    /** Hands one event to the transport; returns once it is accepted (e.g. broker ack). Throws on failure. */
    void send(OutgoingEvent event) throws Exception;
}

public record OutgoingEvent(
        UUID id,              // ce_id, the same on every attempt (TR-E4)
        String type,          // ce_type
        String source,        // ce_source
        Instant time,         // ce_time (when recorded)
        String destination,   // logical destination: the topic in Plan 2
        String key,           // aggregate id: partition key (TR-X6)
        String payload,       // JSON data, exactly as recorded; datacontenttype application/json
        W3cTraceContext traceContext) {   // traceparent / tracestate to propagate (TR-E7)
    Map<String, String> traceHeaders();   // {"traceparent": …, "tracestate": …}, empty entries omitted
}
```

- The fields are the CloudEvents 1.0 required attributes plus `time` and `datacontenttype` (fixed JSON), the logical destination and the partition key. Plan 2 maps them 1:1 to a binary-mode Kafka CloudEvent (`ce_*` headers, key, value) and the W3C headers; no field names a broker.
- **Synchronous and bounded.** `send` must return only after the transport has durably accepted the event and must bound its own duration (for Kafka: `delivery.timeout.ms`). The relay marks the event delivered only after `send` returns, which is what makes delivery at-least-once.
- **Any exception is a failed attempt** (§6). The relay never inspects transport-specific exception types.
- **Trace context handed over** is that of the relay's hand-off span, which is a child of the recorded request context (§7). With no tracing SDK, the span is non-recording and the handed-over context is the recorded one unchanged. Either way the consumer stays in the raising request's trace (FR-7).
- A new transport is a new `EventTransport` bean; the port, relay and table are unchanged (TR-E5).

## 4. Relay trigger: polling

- One daemon thread per app instance (`polaris-outbox-relay`), owned by a `SmartLifecycle` bean, so it starts after the context is ready and stops on shutdown (finishing the in-flight batch).
- Each cycle is one relay transaction (§5). If the cycle **delivered at least one event and nothing failed**, the next cycle runs immediately; otherwise the thread waits `poll-interval`. A cycle hands off at most one event per key (its head), and a delivery can make that key's next event the head, so this drains a backlog back-to-back, including a single hot key (e.g. an order with several quick transitions after an outage), instead of one event per poll.
- **TR-E8:** worst case commit → hand-off is one poll interval plus one cycle: 250 ms + a few ms of indexed query, well under 1 s. The integration test asserts it.
- A post-commit in-process signal would only speed up the instance that raised the event, and PostgreSQL `LISTEN/NOTIFY` has no H2 equivalent (dev–prod parity). Neither is needed for 1 s; both can be added later behind the same relay without changing producers or storage.
- Cost: one indexed `SELECT` per instance every 250 ms when idle.

## 5. Ordering across failures, retries and instances

One relay cycle is one transaction of its own on the relay thread (a `TransactionTemplate` over the app's transaction manager):

```sql
-- 1. Lock the due head (oldest pending event) of up to batch-size keys, oldest first.
SELECT id, event_id, event_type, event_source, destination, event_key, payload,
       traceparent, tracestate, occurred_at, attempts
FROM outbox_events e
WHERE e.status = 'PENDING'
  AND e.next_attempt_at <= :now
  AND NOT EXISTS (SELECT 1 FROM outbox_events p
                  WHERE p.status = 'PENDING' AND p.event_key = e.event_key AND p.id < e.id)
ORDER BY e.id
FETCH FIRST :batchSize ROWS ONLY
FOR UPDATE SKIP LOCKED
```

2. For each locked head, in id order: open the hand-off span, call `transport.send`, then
   - success → `UPDATE … SET status='DELIVERED', delivered_at=:now WHERE id=:id`;
   - failure → `UPDATE … SET attempts=attempts+1, next_attempt_at=:retryAt, last_error=:error WHERE id=:id`, and **stop the batch** (§6).
3. Commit. Record delivery metrics after the commit.

Why this preserves **per-key commit order** (TR-E3):

| Situation | Outcome |
|:--|:--|
| Several events for one key (recorded by serialised transactions, see the precondition below) | Only the key's oldest `PENDING` row qualifies (`NOT EXISTS`); the next one becomes selectable only after the previous one is committed `DELIVERED`. At most one event per key is in flight anywhere |
| A head is failing | It stays `PENDING` with a future `next_attempt_at`, so it is not due, and its followers are not heads: **that key is held back, other keys are not** |
| Two instances | Instance B's `SKIP LOCKED` passes over heads A has locked, and B cannot pick a locked head's follower because B still sees the head as `PENDING`. If A commits while B scans, PostgreSQL re-checks the locked row and finds it `DELIVERED`. So **no double hand-off and no reordering in normal operation** |
| Crash after hand-off, before commit | The transaction rolls back (the connection dies with the process); the row is still `PENDING` with the same `event_id` and is re-sent by whichever instance polls next. **At-least-once, same `ce_id`** (TR-E4) |
| App restart / transport outage | Nothing is lost: rows stay `PENDING` until handed off (TR-E2). The business transaction never calls the transport |

**What the relay guarantees, and what it relies on.** The relay hands off a key's events in `id` order, and `id` is assigned when `publish` inserts the row, **not** when the transaction commits. So "id order = commit order per key" holds only if producers meet this precondition:

> **Producer precondition ([TR-X8](../../plan/order-notifications-and-fulfilment/README.md)):** transactions that raise events for the same key must be serialised **before** the event is recorded. Either load the aggregate with a pessimistic lock (`PESSIMISTIC_WRITE` / `SELECT … FOR UPDATE`) before the transition, or make them mutually exclusive with optimistic locking (`@Version`), so the losing transaction rolls back together with its event.

Without that, two unserialised same-key transactions T1 and T2 can record ids 10 and 11, T2 can commit first and have 11 handed off, and T1 can then commit 10. The relay cannot tell this apart from a normal commit: it is a producer-side ordering bug, not a relay bug. A relay-side guard (for example, taking only rows older than the oldest running transaction, via `pg_snapshot_xmin(pg_current_snapshot())`) is PostgreSQL-only and would break H2 parity, so E3 does not add one.

**`Order` does not meet the precondition yet.** `placeOrder` locks the **product** rows, not the order row. `Order` has no `@Version`, and `cancelOrder` loads the order without a lock. The outbox row is inserted at `save()`/listener time, before the flush-time `UPDATE` would lock the order row. `order.placed` (E4) is safe regardless: it is the first event of a new key, and nothing else can raise an event for that key before the order commits. The later transitions (cancel, and Plan 3's claim and shipment milestones) need the precondition. **E4 enforces TR-X8 on `Order`** (`@Version` or a row lock before recording, with a concurrency test against real Postgres); E3 changes no Order code. The precondition is also stated on `IntegrationEventPublisher` and in ADR-0018's consequences.

Why not the alternatives:

- **Lease/owner columns** (`locked_by`, `locked_until`): they let the hand-off happen outside a transaction, but add a takeover rule, a clock-skew assumption, and double hand-off whenever a send outlives its lease. Row locks give the same exclusion with nothing to expire: a dead instance's locks vanish with its connection.
- **Leader election** (advisory lock, ShedLock): serialises all keys on one instance; `pg_advisory_lock` has no H2 equivalent.

Trade-off accepted: a relay transaction (one pooled connection) stays open for the duration of one batch's hand-offs. Transports are required to bound `send` (§3), and a failure ends the batch (§6), so a broker outage costs one bounded send per cycle, not `batch-size` of them.

## 6. Retries and backoff (TR-E6)

- `retryAt = now + min(initial × multiplier^(attempts), max)` where `attempts` is the count **before** this failure: 1 s, 2 s, 4 s … capped at **5 min** (`backoff.initial`, `backoff.multiplier`, `backoff.max`). Exponential backoff with a cap is the standard named pattern (AGENTS.md P1.2). No jitter: each instance runs one serial relay loop, so there is no retry storm to spread.
- **Never dropped:** there is no terminal failure state (`status` stays `PENDING`); `attempts` and `last_error` (truncated to the column's 2000 chars) show what is wrong, and the backlog and oldest-pending-age metrics alert on it.
- **A failure ends the batch.** When the transport is down every send would fail after its own timeout; stopping at the first failure avoids paying that timeout per key (a minimal circuit breaker). The failed head is now not due, so the next cycle reaches the other keys: one event-specific failure (e.g. an oversized payload) cannot starve other keys.
- On recovery, each held-back head is retried when its `next_attempt_at` passes (at most `backoff.max` later), then its followers drain in order.

## 7. Observability (TR-X2, ADR-0016)

**Tracing.** Each hand-off runs in a span `outbox publish <destination>` (`SpanKind.PRODUCER`) whose parent is the **recorded** `traceparent`/`tracestate`, extracted with the standard `W3CTraceContextPropagator`, not the relay thread's context. Attributes: `messaging.destination.name`, `messaging.message.id` (`ce_id`), `polaris.outbox.event_key`, `polaris.outbox.attempt`. A failed send records the exception and sets status `ERROR`. The span is current during `send`, and `OutgoingEvent.traceContext` is captured from it, so a transport that propagates either one keeps the chain request → hand-off → consumer. The `OpenTelemetry` bean is used when present, otherwise the no-op API.

**Metrics** (Micrometer, exported by the apps' existing OTLP registry):

| Metric | Type | Tags | Meaning |
|:--|:--|:--|:--|
| `polaris.outbox.backlog` | gauge | — | `PENDING` rows (TR-E6) |
| `polaris.outbox.oldest.pending.age` | gauge, seconds | — | now − `occurred_at` of the oldest `PENDING` row; `0` when empty (TR-E6) |
| `polaris.outbox.delivery.lag` | timer (histogram) | `destination` | recorded → handed off, for each delivered event (TR-E8) |
| `polaris.outbox.handoff` | timer (histogram) | `destination`, `outcome` = `success`/`failure` | transport invocations, latency, error rate |
| `polaris.outbox.purged` | counter | — | delivered rows removed by retention |

Gauges are read from the database (two indexed queries) when the registry samples them. They exist whenever the outbox is configured, with or without a transport, so a growing backlog is visible even when no transport is configured. Every instance reports the same table-wide value; dashboards use `max`, not `sum`.

**Logs** (SLF4J, `key=value`; `trace_id`/`span_id` come from the existing logback OTel appender while the hand-off span is current): `DEBUG` per delivery (`ce_id`, `ce_type`, `key`, `destination`); `WARN` per failed attempt (`ce_id`, `key`, `attempt`, `retry_in`, error); `INFO` once at start saying whether a transport is configured; `INFO` per purge that removed rows; `ERROR` if a relay cycle itself fails (e.g. database down), after which the loop continues on the next interval. A fatal JVM `Error` (e.g. `OutOfMemoryError`) is logged and **stops** the relay rather than polling on in an unknown state; recorded events stay pending and the backlog metrics show them. Logs never go to protocol `stdout`.

## 8. V15 delivery columns and indexes: confirmed, no `V15_x` from E3

| V15 item | Verdict |
|:--|:--|
| `status` `CHECK (PENDING, DELIVERED)` and the `delivered_at` consistency check | **Keep.** No dead-letter state (programme §7); a failing event is `PENDING` with `attempts > 0` |
| `attempts`, `next_attempt_at`, `last_error VARCHAR(2000)` | **Keep.** Exactly the backoff bookkeeping of §6. The recording insert now sets `next_attempt_at = occurred_at` from the application clock instead of relying on the column's database default, because the relay compares it with the application clock: with one authoritative clock, an app clock behind the database's cannot delay new events (TR-E8). The default stays as a harmless fallback. Different app instances still compare against their own clocks; NTP-level skew only shifts a retry by that skew |
| `delivered_at` | **Keep.** Set when marked delivered; retention key |
| Lease/owner columns | **Not needed**: row locks (§5) |
| `idx_outbox_events_pending (status, event_key, id)` | **Keep.** Serves the `NOT EXISTS` head check (equality on `status, event_key`, range on `id`) and the backlog count |
| `idx_outbox_events_delivered (status, delivered_at)` | **Keep.** Serves the retention `DELETE` |

A partial index `WHERE status = 'PENDING'` would be smaller, but H2 has no partial indexes and the healthy backlog is near zero, so the composite index is enough. If production volume ever shows otherwise, it is an additive `V15_x`/later migration.

## 9. Retention (TR-E6)

`DELETE FROM outbox_events WHERE status = 'DELIVERED' AND delivered_at < :now - retention`, every `purge-interval` (default 1 h), retention default **7 days**. The predicate names `DELIVERED`, and the V15 check guarantees a `PENDING` row has no `delivered_at`, so the purge can never touch pending events. The purge runs on the relay thread whether or not a transport is configured.

## 10. Packaging and configuration

New packages in `libs/polaris-outbox`, dependencies flowing downwards only:

| Package | Contents |
|:--|:--|
| `…outbox.transport` | `EventTransport`, `OutgoingEvent` (the port; depends only on `…outbox.trace`) |
| `…outbox.relay` | `OutboxRelay` (one cycle), `RetryBackoff`, `OutboxRetention` (purge), `OutboxRelayWorker` (`SmartLifecycle` scheduling thread) |
| `…outbox.store` | adds `OutboxRelayStore` / `JdbcOutboxRelayStore` (lock heads, mark delivered/failed, purge, backlog queries) and `PendingEvent`. E2's append-only `OutboxStore` is unchanged |
| `…outbox.telemetry` | `OutboxMetrics` (Micrometer), `HandOffTracing` (OpenTelemetry API) |
| `…outbox.autoconfigure` | `OutboxAutoConfiguration` gains the relay beans; `OutboxProperties` |

New dependency: `micrometer-core` (the Micrometer API the apps already ship with actuator). With no `MeterRegistry` bean (an app without actuator), the meters are kept in a local in-memory registry and not exported.

Properties (`polaris.outbox.*`, all optional, 12-factor via env):

| Property | Default |
|:--|:--|
| `relay.enabled` | `true` |
| `relay.poll-interval` | `250ms` |
| `relay.batch-size` | `100` |
| `relay.backoff.initial` / `.multiplier` / `.max` | `1s` / `2.0` / `5m` |
| `retention.period` / `retention.purge-interval` | `7d` / `1h` |

**Several `EventTransport` beans** fail startup with a clear message (mark one `@Primary`, or disable the relay): the relay never goes silently idle, and never splits a key's events across transports.

**No transport configured** (no `EventTransport` bean, the situation until Plan 2): the worker logs once that events stay pending, never polls for hand-off, and raises no errors; the backlog and oldest-age gauges keep reporting and the purge still runs. The worker resolves the transport lazily at start, so a transport contributed by a later auto-configuration (Plan 2) is still found.

## 11. Tests

| Test | Kind | Proves |
|:--|:--|:--|
| `OutboxRelayIntegrationTest` | Real PostgreSQL 16 (Testcontainers), all migrations; a recording test transport is the only stub; relay instances driven directly with an adjustable clock | Commit order per key, `DELIVERED`/`delivered_at`, CloudEvents attributes and recorded trace context handed over; transport down → `PENDING`, `attempts`, `last_error`, 1 s then 2 s backoff, a failure ends the batch; recovery → all delivered in order per key; one failing key holds back only itself; two relay instances concurrently → every event once, in order per key; crash after hand-off before commit (an `Error` from the transport, and the relay's database session terminated with `pg_terminate_backend`) → re-sent with the same `ce_id`; new rows are due at their application-clock recording time; no transport → worker idle, events stay pending; purge removes only old delivered rows; backlog, oldest-pending-age, delivery-lag and hand-off metrics |
| `OutboxRelayWorkerIntegrationTest` | Real PostgreSQL 16, auto-configured worker with a test transport bean | Commit → hand-off in under 1 s with default settings (TR-E8), then `DELIVERED`; a 12-event single-key backlog drains in order in under 1 s; metrics in the app's registry |
| `OutboxRelayWorkerTest` | Unit, mocked relay | A failing cycle is retried next interval; a fatal `Error` stops the relay; delivering cycles run back-to-back |
| `HandOffTracingTest` | Unit, in-memory OpenTelemetry SDK | Hand-off span is a `PRODUCER` child of the recorded context and current while open; its context (with `tracestate`) is what the transport gets; failures set `ERROR` and record the exception; no-op API hands the recorded context over unchanged |
| `RetryBackoffTest` | Unit | Exponential growth and cap |
| `OutgoingEventTest` | Unit | Required attributes; trace headers omit absent values |
| `OutboxAutoConfigurationTest` | `ApplicationContextRunner` | Relay worker and metrics beans present with a datasource and idle without a transport; two transports fail startup clearly; absent without a datasource or when the outbox is disabled; `relay.enabled=false` keeps recording and metrics without a worker |
| `OutboxMigrationH2Test` | Flyway + relay on H2 | The head-locking query (`FOR UPDATE SKIP LOCKED`), backlog queries and purge run on the local H2 default; one head per key per cycle, delivered in order |
