# Messaging Stability

- **Scope:** Kafka consumers and producers in `polaris` (Order) and `polaris-fulfilment-emulator` (Fulfilment)
- **Reference:** Michael T. Nygard, *Release It!*, 2nd ed.: stability antipatterns and stability patterns
- **Status:** Draft for review

## 1. Problem statement

No system is fault-free. The practical approach is to:

1. list the known failures that apply to this architecture,
2. classify each one as a *Release It!* antipattern,
3. pick a standard stability pattern for each, and accept the residual risk explicitly.

A consumer can't be guaranteed to process every message. The main risks are:

- **Application bug:** a defect triggered by one customer, order or message (a poison message)
- **Infrastructure outage:** Kafka, PostgreSQL or Keycloak unavailable
- **Database contention:** a deadlock or a long lock wait on the order row

## 2. Message flows

| Flow | Producer → topic → consumer | Delivery today |
|:--|:--|:--|
| F1 Order lifecycle | Order (transactional outbox) → `order lifecycle` → one group per partner, `fulfilment.<partnerId>` | Outbox relay retries with capped backoff (max 5 min). Consumer: 2 × 500 ms retries, then **log and skip** |
| F2 Claim | Emulator → `POST /orders/{n}/claim` (REST) | 200 WON / 409 LOST / other FAILED, **never retried** (D4) |
| F3 Shipment reports | Emulator (direct send, **no outbox**) → shipments topic → Order, group `order.shipments` | Producer never retries (TR-F4). Consumer: 3 retries, exponential from 500 ms (≈3.5 s), then **log and skip** |

Both consumer paths already use manual commits, record keys equal to the order number (so each order's events stay in order), status guards (so replays are harmless), and W3C trace propagation.

## 3. Failure modes

| # | Failure | Antipattern | Today | Gap |
|:-:|:--|:--|:--|:--|
| R1 | Poison message (bug, bad payload) | Chain reaction: a blocked partition stalls every order behind it | Bounded retries, then skipped with ERROR log and `skipped` metric | ✅ Partition is never blocked. ❌ **The message is lost.** It's only in the logs, so there's no replay path |
| R2 | PostgreSQL down or slow while consuming | Integration point, cascading failure | Same handler as R1: after ≈3.5 s, **every** record is skipped | ❌ **A transient outage turns into silent data loss** (orders stuck in DISPATCHED, offers never made) |
| R3 | Kafka down: Order producing | Integration point | Outbox keeps events PENDING; relay backs off, then drains | ✅ No loss. Backlog metrics are in place |
| R4 | Kafka down: emulator producing shipments | Integration point | Send fails, report dropped | ⚠️ Accepted for an emulator (TR-F4). A real partner would need an outbox |
| R5 | Kafka down: consuming | Integration point | Client reconnects; uncommitted offsets are re-read | ✅ |
| R6 | Deadlock or lock wait on `orders` (`SELECT … FOR UPDATE`, claim vs. shipment) | Blocked threads | No lock or statement timeout. A deadlock loser is retried 3× and then **skipped** | ❌ The consumer thread can hang on a lock. ❌ A deadlock victim is treated as poison |
| R7 | Keycloak or Order API down during a claim | Integration point | Claim FAILED, offer dropped | ⚠️ Accepted for the emulator (D4) |
| R8 | Redelivery after a rebalance or crash | Unbalanced capacities, duplicate work | Status guard and `ce_id` make re-processing a no-op | ✅ |

**Root cause of R1, R2 and R6:** a single error policy (*retry N times, then skip*) handles both **transient** and **permanent** failures.

## 4. Proposed changes

Each change uses Spring Kafka's standard building blocks rather than custom retry code (AGENTS.md P1.2).

| ID | Change | Pattern | Fixes |
|:--|:--|:--|:--|
| S1 | **Classify exceptions.** Deserialization, validation and `IllegalArgumentException` are *non-retryable*. `TransientDataAccessException`, `CannotAcquireLockException`, `DataAccessResourceFailureException` and timeouts are *retryable* | Fail fast | R1, R2, R6 |
| S2 | **Dead-letter topic.** After retries are exhausted, `DeadLetterPublishingRecoverer` sends the record to `<topic>.DLT` with the original headers (`traceparent`, `ce_*`, exception). This replaces the log-and-skip handler | Dead letter / quarantine | R1 |
| S3 | **Don't skip on infrastructure failure.** Retryable errors back off up to a cap (e.g. 30 s) and keep retrying. The container pauses rather than committing past the record | Circuit breaker (pause/resume container) | R2 |
| S4 | **Bound lock waits.** Set `jakarta.persistence.lock.timeout` (e.g. 2 s) on the `FOR UPDATE` query and a PostgreSQL `statement_timeout`. A timeout then surfaces as a retryable error (S1) | Timeouts | R6 |
| S5 | **DLT replay runbook and alert.** Alert on DLT depth above 0 and on consumer lag. Document how to inspect and re-publish a DLT record | Steady state, transparency | R1 |

**Tests (AGENTS.md P3.2):** Testcontainers cases for each change: a poison record lands in the DLT; a DB outage of about 10 s loses nothing; a held row lock times out and is then retried; the trace continues into the DLT record.

## 5. Decisions to confirm

1. Should the emulator get S1–S3, or should it keep the "lossy by design" policy (TR-F4, D4)?
2. DLT ownership: does each consuming context own its own `<topic>.DLT`? (Recommended, per P2.2.)
3. Should S3 retry indefinitely, or give up after a long cap (e.g. 15 min) and then dead-letter?
4. Slicing: one plan slice per consumer (Order's shipment listener first), each one vertically complete.
