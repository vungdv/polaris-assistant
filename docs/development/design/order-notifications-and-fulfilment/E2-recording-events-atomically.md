# Detail Design: E2 — Recording Events Atomically

- **Plan:** [Plan 1: Outbox & Generic Event Publishing](../../plan/order-notifications-and-fulfilment/01-outbox-event-publishing.md), slice **E2**
- **Covers:** TR-E1, TR-E4, TR-E5, TR-E7, TR-X4 (Flyway V15). Also the design basis for **E4** (Order adopts it)
- **Decision record:** [ADR-0018 Transactional outbox for integration events](../../../technical/decisions/0018-transactional-outbox-for-integration-events.md) (Proposed)
- **Builds on:** E1 `libs/polaris-events` (published contracts)
- **Status:** Draft for review

## 1. Scope

In: a broker-agnostic publish port, its outbox implementation that records an event in the caller's database transaction, the `outbox_events` table (V15), and auto-configuration that only activates where a datasource exists.

Out: delivering recorded events (E3 relay and transport port), Kafka (Plan 2), and any Order code (E4). Until E3 lands, recorded events stay `PENDING`, which is expected.

## 2. Packaging: a new `libs/polaris-outbox` module

| Option | Verdict |
|:--|:--|
| Put it in `polaris-common` | No. `polaris-common` already forces JPA, Flyway, Security and WebMVC; the same reason E1 created `polaris-events` (Δ5) |
| Put it in `polaris-events` | No. That library is contracts only, with no Spring and no persistence, so stateless apps can use it |
| **New `libs/polaris-outbox`** | **Yes.** Depends on `spring-jdbc`/`spring-tx`, `spring-boot-autoconfigure`, Jackson 3 and the OpenTelemetry API only. No dependency on JPA, web, security or `polaris-events` |

Who depends on what:

```
apps/polaris (Order, from E4) ──► polaris-outbox ──► spring-jdbc, spring-tx, jackson, opentelemetry-api
                              └─► polaris-events  (contracts; E4 builds IntegrationEvents from them)
stateless apps (Plans 3, 4)   ──► polaris-events only
```

Packages (single responsibility, dependencies flow downwards only):

| Package | Contents |
|:--|:--|
| `vn.danang.polaris.outbox` | The port: `IntegrationEventPublisher`, `IntegrationEvent`. Business code imports only this package |
| `vn.danang.polaris.outbox.store` | `OutboxRecord`, `OutboxStore` (append-only for E2; E3 adds read/mark operations), `JdbcOutboxStore` |
| `vn.danang.polaris.outbox.trace` | `W3cTraceContext`: captures `traceparent`/`tracestate` from the current OpenTelemetry context |
| `vn.danang.polaris.outbox.autoconfigure` | `OutboxAutoConfiguration` |
| `vn.danang.polaris.outbox` (impl) | `OutboxIntegrationEventPublisher`: the port's only implementation; orchestrates the pieces above |

## 3. The publish port (TR-E5)

```java
public record IntegrationEvent(String type, String source, String destination, String key, Object data) {}

public interface IntegrationEventPublisher {
    /** Records the event in the current transaction and returns its ce_id. */
    UUID publish(IntegrationEvent event);
}
```

- The fields are exactly the CloudEvents attributes a producer owns (`type`, `source`), plus the logical `destination` and the partition `key` (aggregate id, TR-X6). The producer passes values from `polaris-events`, e.g. `OrderEvents.PLACED_V1`, `OrderEvents.SOURCE`, `OrderEvents.DESTINATION`, order number.
- `data` is any Jackson-serializable payload (the E1 records). It is serialized to JSON **when recorded**, with the application's Jackson 3 `JsonMapper` (fallback: a default `JsonMapper`), so the payload is frozen at commit time and the storage never needs to know the type. A payload that can't be serialized throws and rolls the business change back: fail fast, never lose.
- Nothing in the port names a broker, topic technology or the outbox. A new event type is a new `IntegrationEvent` value; a new transport is an E3 transport port implementation. Neither changes this port or the table.
- The name avoids Spring's `ApplicationEventPublisher`, which is used for **domain** events (§5).

## 4. Recording (TR-E1, TR-E4, TR-E7)

`OutboxIntegrationEventPublisher.publish(event)`:

1. **Fail fast outside a transaction.** If `TransactionSynchronizationManager.isActualTransactionActive()` is false, throw Spring's `IllegalTransactionStateException` (the same exception `Propagation.MANDATORY` throws). Nothing is written. Without this check the insert would auto-commit on its own connection and an event could exist for a change that later rolls back.
   **Also fail fast inside a read-only transaction** (`TransactionSynchronizationManager.isCurrentTransactionReadOnly()`), with the same exception. A read-only JPA transaction never flushes the business change (FlushMode.MANUAL), while the outbox insert is plain JDBC. PostgreSQL would reject that insert, but H2 treats `setReadOnly` as a hint and would commit an event with no change behind it. The check keeps local H2 behaving like production.
2. **Assign `ce_id`**: a random UUID (v4) generated in the application at record time. It is returned to the caller (for logs), stored `UNIQUE`, and reused on every delivery attempt (TR-E4).
3. **Capture trace context**: inject the current OpenTelemetry `Context` with the standard `W3CTraceContextPropagator` into a map, keeping `traceparent` and `tracestate`. Spring Boot's Micrometer→OTel bridge makes the request span current in the OTel context, so this is the placing request's span. No valid span → both `NULL` (e.g. a background job without tracing).
4. **Append** one row through `JdbcOutboxStore`, which uses `JdbcClient` over the application `DataSource`. `JdbcClient` obtains its connection through `DataSourceUtils`, so it joins the caller's transaction under both `DataSourceTransactionManager` and `JpaTransactionManager` (Boot sets the JPA manager's `DataSource`, so JDBC and JPA share one connection). Commit → the row commits with the business change; rollback → it disappears with it.
5. Log at `DEBUG` with `ce_id`, `ce_type` and `key` (trace/span ids are added by the existing logback OTel appender).

**Assumption:** one transactional `DataSource` per context (true for `polaris` and `polaris-assistant`). A context with several datasources must bind the publisher to the one its business data lives in; the auto-configuration requires a single candidate (§6).

## 5. Raising and translating domain events so no state change skips its event

Domain events (inside a bounded context, e.g. `OrderPlaced`) and integration events (published contracts, e.g. `order.placed.v1`) stay separate types:

```
aggregate state-transition method ──registers──► domain event (context-internal record)
repository.save(aggregate) ──Spring Data publishes synchronously, same thread, same transaction──►
context translator (@EventListener) ──maps──► IntegrationEvent (polaris-events payload) ──► IntegrationEventPublisher.publish
                                                                                              └─► INSERT outbox_events (same tx)
```

Rules, applied by E4 and every later adopter:

1. **The event is raised where the state changes.** The aggregate's state-transition method (e.g. `Order.place(...)`, later `Order.claim(...)`) calls `registerEvent(...)` (Spring Data `AbstractAggregateRoot`, a standard mechanism). Status is only changed through those methods, so a transition cannot happen without its event being registered.
2. **Registered events are published by `save`.** Spring Data publishes an aggregate's registered events when it is passed to `save`/`saveAll`, then clears them. Adopters call `save` explicitly after every transition, including dirty-checked updates; this is what E4's "placed order → exactly one event" test and Plan 3's per-milestone tests prove per transition.
3. **Translate synchronously.** The translator is a plain `@EventListener` in the owning context (e.g. `order.event.OrderIntegrationEventTranslator`): it runs on the caller's thread inside the caller's transaction. Never `@Async` and never `@TransactionalEventListener(AFTER_COMMIT)`: those are the "send after commit" pattern the outbox replaces (Δ8). An exception in the translator or publisher propagates out of `save` and rolls the state change back.
4. **No transaction, no change.** If a transition were ever saved outside a transaction, the publisher throws `IllegalTransactionStateException` (step 1 above), so the mistake is found by the first test, not in production.
5. **Idempotent replays raise nothing** because a replay returns the existing aggregate without a transition (TR-O1; the E4 tests cover it).

The library ships no domain-event framework of its own: Spring Data's aggregate events and `@EventListener` are the standard mechanism, and the translator is the only context code that sees both the domain event and the published contract.

## 6. Auto-configuration: apps without a datasource aren't forced to have one

`OutboxAutoConfiguration`, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

| Condition | Why |
|:--|:--|
| `@AutoConfiguration(afterName = DataSourceAutoConfiguration, JacksonAutoConfiguration)` | Evaluate after the datasource and `JsonMapper` exist |
| `@ConditionalOnClass(JdbcClient, PlatformTransactionManager)` | Only with Spring JDBC on the classpath |
| `@ConditionalOnSingleCandidate(DataSource.class)` | No datasource → no beans, no failure. The library never creates a datasource |
| `@ConditionalOnBooleanProperty(name = "polaris.outbox.enabled", matchIfMissing = true)` | Explicit opt-out |
| `@ConditionalOnMissingBean` on each bean | An app can replace the store or the publisher |

Beans: `OutboxStore` (`JdbcOutboxStore`) and `IntegrationEventPublisher` (`OutboxIntegrationEventPublisher`). Stateless apps (fulfilment emulator, notification) depend on `polaris-events` only and never see this module; if one did include it, the configuration backs off.

## 7. Storage schema: `V15__create_outbox_events.sql`

In `libs/polaris-common/src/main/resources/db/migration` with V1–V14, so both apps' existing Flyway location picks it up. Standard SQL only, so it applies on PostgreSQL and on the local H2 default (same rule as V13/V14).

| Column | Type | Notes |
|:--|:--|:--|
| `id` | `BIGINT IDENTITY` PK | Insertion order; E3 uses it for per-key ordering |
| `event_id` | `UUID NOT NULL UNIQUE` | `ce_id`, assigned when recorded (TR-E4) |
| `event_type` | `VARCHAR(255) NOT NULL` | `ce_type`, e.g. `vn.danang.polaris.order.placed.v1` |
| `event_source` | `VARCHAR(255) NOT NULL` | `ce_source`, e.g. `/polaris/order` |
| `destination` | `VARCHAR(255) NOT NULL` | Logical destination (topic in Plan 2) |
| `event_key` | `VARCHAR(255) NOT NULL` | Aggregate id; partition key and ordering key |
| `payload` | `TEXT NOT NULL` | JSON `data`. `TEXT` rather than `JSON`/`JSONB`: opaque to storage (TR-E5), and portable JDBC writes on both H2 and PostgreSQL |
| `traceparent` | `VARCHAR(55)` | W3C `traceparent` of the raising request (TR-E7); `NULL` if none |
| `tracestate` | `TEXT` | W3C `tracestate`; `NULL` if empty. Stored in full, not truncated: W3C allows 32 members with 256-char keys and values, and 512 chars is only the minimum vendors *should* propagate. A bounded column would let a long inbound header fail the business transaction. `TEXT` also avoids a truncation rule of our own (W3C §3.3.1.5) and keeps the value the relay hands on (E3) identical to the one received |
| `occurred_at` | `TIMESTAMPTZ NOT NULL` | `ce_time`: when recorded |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'PENDING'` | `PENDING` / `DELIVERED` (`CHECK`) |
| `attempts` | `INT NOT NULL DEFAULT 0` | Relay bookkeeping (E3) |
| `next_attempt_at` | `TIMESTAMPTZ NOT NULL DEFAULT now` | Relay backoff (E3) |
| `last_error` | `VARCHAR(2000)` | Last delivery failure (E3) |
| `delivered_at` | `TIMESTAMPTZ` | Set exactly when `DELIVERED` (`CHECK`); retention purge key (E3) |

Indexes: `(status, event_key, id)` for "oldest pending per key", and `(status, delivered_at)` for the retention purge. E2 writes only the first ten columns; the delivery columns take their defaults.

The relay columns are created now because V15 is the only version reserved for Plan 1 (TR-X4). If E3's design needs something different, it adds a forward-only `V15_1__...` (sorts between V15 and V16), never an edit to V15.

## 8. Tests

| Test | Kind | Proves |
|:--|:--|:--|
| `OutboxRecordingIntegrationTest` | Real PostgreSQL 16 via Testcontainers, all real migrations V1–V15 | Commit → exactly one row with a unique `ce_id`, `PENDING`, the JSON payload and the active span's `traceparent`; two events → two different ids; rollback → none; publish outside a transaction, or inside a read-only one → `IllegalTransactionStateException` and no row; a maximum-size `tracestate` (32 × 256 chars) is stored in full and the transaction commits. Uses a **test-only** domain event, translator and payload, no Order code |
| `OutboxIntegrationEventPublisherTest` | Unit, fake `OutboxStore` | Fail fast without a transaction and inside a read-only one; field mapping, id and clock; unserializable payload throws |
| `W3cTraceContextTest` | Unit | Valid span → well-formed `traceparent`; no span → none |
| `IntegrationEventTest` | Unit | Required attributes are validated |
| `OutboxAutoConfigurationTest` | `ApplicationContextRunner` | Beans with a datasource; none without one; none when disabled; app beans win |
| `OutboxMigrationH2Test` | Flyway on in-memory H2 | V15 stays portable to the local H2 default; a read-only transaction records nothing on H2 either |

The integration test lives in `polaris-outbox` and runs `polaris-common`'s migrations from its classpath (test scope only), so it exercises the production schema rather than a copy.
