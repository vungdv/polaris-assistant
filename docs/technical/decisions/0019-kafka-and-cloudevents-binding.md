# ADR-0019: Kafka & CloudEvents Binding

* **Status:** Accepted (2026-09-29, Plan 2 B2)
* **Amended:** 2026-09-29, Plan 2 B3: the dev broker becomes a three-node cluster; topics carry their own `min.insync.replicas` (§4 items 3–4)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-09-29
* **Technical Story:** Kafka joins the platform, and the outbox relay ([ADR-0018](0018-transactional-outbox-for-integration-events.md)) gets a Kafka transport. Every recorded event, starting with `order.placed.v1`, arrives on Kafka as a CloudEvent in the originating trace. This ADR also fixes the conventions every producer and consumer in Plans 3 and 4 follows.
* **Product Reference:** [PRD-007](../../business/prds/PRD-007-order-notifications-and-fulfilment-emulator.md) (FR-1, FR-3, FR-7)
* **Plan Reference:** [Plan 2: Message Broker Infrastructure](../../development/plan/order-notifications-and-fulfilment/02-message-broker.md) (TR-B1–B7), programme TR-X2, TR-X5, TR-X6
* **Builds on:** [ADR-0018](0018-transactional-outbox-for-integration-events.md), the E3 transport port ([E3 design §3](../../development/design/order-notifications-and-fulfilment/E3-relay-to-a-transport-port.md)), [ADR-0016](0016-genai-observability-and-mcp-audit-standards.md)

---

## 1. Context and Problem Statement

Plan 1 records integration events in an outbox and relays them to an `EventTransport` port, but no transport existed yet. The events stayed pending. Fulfilment partners and Notification (Plans 3–4) will consume these events from Kafka. So the platform needs one agreed answer for all of these:

* how an event is laid out on a Kafka record;
* who creates topics;
* what "delivered" means for the producer;
* how the trace crosses the broker;
* how consumers behave.

**How should Polaris put CloudEvents on Kafka so that producers and consumers interoperate, keep per-aggregate order, and stay in one trace?**

---

## 2. Decision Drivers

* **Standards first ([AGENTS.md Principle 1](../../../AGENTS.md)):** use the CloudEvents 1.0 Kafka protocol binding, W3C Trace Context, and Spring Kafka / Spring Boot configuration. Do not invent our own header scheme or retry logic.
* **Per-key order and no loss (TR-E2, TR-E3, TR-B3):** a key's events arrive in commit order, and an event counts as delivered only after the broker holds it durably.
* **Forward-compatible consumers (TR-X6, TR-B6):** new event types and new fields must never break an existing consumer.
* **Operational ownership (TR-B1, TR-B5):** each topic belongs to the bounded context that produces to it.
* **Traceability (FR-7, TR-B4, TR-X2):** one trace runs from the HTTP request through the Kafka produce to each consumer.

---

## 3. Considered Options

### 3.1 Record layout
| Option | Verdict |
|:--|:--|
| **Binary mode** (attributes in `ce_*` headers, `content-type` header, value = the JSON `data`) | **Chosen.** The value is the plain JSON payload, so it is readable with any tool (`make kafka-tail`). Consumers can route on headers without parsing the body |
| Structured mode (the whole CloudEvent as a JSON envelope in the value) | Rejected. The payload is wrapped a second time, and routing requires parsing the body |
| Spring Kafka `JsonSerializer` with its type headers | Rejected. `__TypeId__` headers tie consumers to Java class names (TR-B2) |

**How it is built:** with the CloudEvents Java SDK (`cloudevents-kafka`, `KafkaMessageFactory.createWriter(...).writeBinary(...)`). The SDK is the reference implementation of the binding, and consumers read records back with the same SDK. We do not write the headers by hand.

### 3.2 Topic provisioning
| Option | Verdict |
|:--|:--|
| Broker auto-create | Rejected (TR-B1). The broker uses its default partition count, and a typo silently creates a new topic |
| A compose init script creates every topic | Rejected. It puts ownership outside the owning context and doesn't carry over to other environments |
| **The owner declares a `NewTopic` bean; Spring Kafka's `KafkaAdmin` creates it** | **Chosen.** The topic's name and partition count live in the owner's code (Order: `OrderLifecycleTopicConfiguration`) |

### 3.3 Trace propagation
| Option | Verdict |
|:--|:--|
| Write only the recorded `traceparent` into the headers | Not enough on its own. No span would show the Kafka produce |
| **Spring Kafka observation on the producer template, plus the hand-off context as a fallback header** | **Chosen.** Spring Kafka creates a PRODUCER span as a child of the relay's hand-off span, and replaces the `traceparent` header with that span's context. Without a tracing SDK, the hand-off (recorded) context is still sent |

---

## 4. Decision Outcome

1. **Transport.** `KafkaEventTransport` in `libs/polaris-outbox` (package `…outbox.kafka`) implements the E3 `EventTransport` port.
   * `OutboxKafkaAutoConfiguration` contributes it when the app has the outbox, Spring Boot Kafka, and `cloudevents-kafka` on the classpath.
   * Opt out with `polaris.outbox.kafka.enabled=false`.
   * The producer starts from the application's own Spring Boot producer factory, so `spring.kafka.*` settings and service connections apply. It then overrides only the settings the guarantees below depend on.
2. **Binding (TR-B2).**
   * CloudEvents 1.0 in **binary mode**.
   * Headers: `ce_specversion`, `ce_id` (the outbox `event_id`, stable across retries), `ce_type` (for example `vn.danang.polaris.order.placed.v1`), `ce_source`, `ce_time`, and `content-type: application/json`.
   * **Record key = aggregate id** (the order number for Order).
   * **Value = the JSON `data` exactly as recorded.** There are no serializer type headers.
3. **Delivery (TR-B3).** The producer runs with:
   * `acks=all` and `enable.idempotence=true`, with `retries` unbounded inside the timeout;
   * `max.in.flight.requests.per.connection=5`, the most that idempotence allows while still keeping order;
   * `linger.ms=0`.

   `send` blocks until the broker acknowledges the record. With `acks=all`, that means every in-sync replica holds it, and the topic's `min.insync.replicas` sets how many must be in sync: on the three-node dev cluster (replication 3, min ISR 2), one node can be down without refusing or losing a write. With fewer in-sync replicas the broker rejects the write (`NotEnoughReplicasException`), and the relay retries it like any other failed attempt. Only then does the relay mark the event delivered. Each send is bounded by `polaris.outbox.kafka.send-timeout` (default 10 s): `max.block.ms`, `request.timeout.ms` and `delivery.timeout.ms` are each capped at that value. A timeout is a failed attempt, which the relay retries with its capped exponential backoff. This transport adds no retry loop of its own. Order across attempts comes from the relay: at most one event per key is in flight at a time ([E3 §5](../../development/design/order-notifications-and-fulfilment/E3-relay-to-a-transport-port.md)).
4. **Topics (TR-B1, TR-B5).**
   * Topic names follow `polaris.<context>.<stream>`.
   * Each topic is owned and provisioned by the one context that produces to it.
   * The broker never auto-creates topics.
   * Order owns `polaris.order.lifecycle`, with **3 partitions × 3 replicas and `min.insync.replicas=2`** in dev (`polaris.order.lifecycle-topic.partitions` / `.replicas` / `.min-insync-replicas`). The owner declares the minimum ISR on the topic rather than relying on the broker default, because it decides how many copies an `acks=all` write needs. Because the key is the order number, an order's events share a partition and keep their order, while a consumer group can process up to 3 orders in parallel. Partitions can be added later but never removed. Adding partitions moves keys to different partitions, so it is a planned change, not a routine one.
   * `KafkaAdmin` provisions topics at startup. If the broker was unreachable then, the transport provisions them again before its first successful send. So events still flow once Kafka returns, without restarting Order (TR-B7).
5. **Tracing (TR-B4).**
   * The relay's hand-off span is a child of the trace context recorded with the event (ADR-0018).
   * Spring Kafka's producer span (`<topic> send`, PRODUCER) is a child of the hand-off span.
   * The record's W3C `traceparent` and `tracestate` headers carry the producer span's context.
   * Result: HTTP request → `outbox publish <topic>` → `<topic> send` → each consumer, all in one trace.
6. **Resilience (TR-B7).** A business request never touches Kafka. While Kafka is down, events stay `PENDING`, and the relay retries with backoff (at most 5 min apart). When Kafka returns, the events are delivered in order per key. Events recorded before the transport existed are delivered on its first start.

### 4.1 Consumer conventions (TR-B6), applied by Plans 3–4

Every consumer of a Polaris topic follows these rules:

1. **Join the producer's trace.** Extract W3C `traceparent`/`tracestate` from the record headers: enable Spring Kafka listener observation, or use the OTel propagator. Processing spans and logs then carry the producer's `trace_id`. Logs include `ce_id`, `ce_type` and the business key (TR-X2).
2. **Read with the CloudEvents binding.** Use `KafkaMessageFactory.createReader(record)` or `CloudEventDeserializer`, and dispatch on `ce_type`. **Ignore unknown types:** skip them with a `DEBUG` log and commit their offset. Deserialize `data` leniently and ignore unknown fields (the contracts library already does this, E1). A new `.vN` type or a new field is therefore never a breaking change.
3. **At-least-once.** The same `ce_id` can arrive more than once, for example after a relay retry or a broker timeout during an outage. Handlers must be idempotent where a duplicate would cause harm. Where they are not, the consumer must accept the duplicate and say so (Notification accepts duplicate emails, programme §7).
4. **A poison record never blocks a partition forever.** Use a bounded retry with backoff (Spring Kafka `DefaultErrorHandler` with `ExponentialBackOff` or `FixedBackOff`, a small attempt count). After the last attempt, log the record at `ERROR` with topic, partition, offset, `ce_id` and `ce_type`, count it in a failure metric, and skip it. A dead-letter topic is out of scope (programme §7). A consumer that adds one must publish it as a topic it owns.
5. **State the starting-offset policy.** Each consumer group documents `auto.offset.reset` for a new group, next to its `group.id`: `earliest` if it must see history (for example a partner group that must not miss offers), `latest` if only new events matter. There is no implicit default.
6. **Groups are named per consumer** (`<app>.<purpose>`, for example `fulfilment.partner-north`). A group reads only topics that another context published as contracts. It never reads another context's database.

---

## 5. Consequences

### Positive
* Any CloudEvents SDK can read every Polaris event. Headers are enough for routing, and the value is plain JSON.
* The broker durably acknowledges each event before the outbox marks it delivered. With the relay's per-key rule, per-order order holds end to end.
* A single trace connects the shopper's request, the Kafka produce and each consumer.
* The code that produces to a topic also declares that topic's name and size.

### Negative / Operational Safeguards
* **Duplicates are possible** (at-least-once, same `ce_id`). Consumer convention 3 covers this.
* **The relay holds a database transaction during a send.** The send-timeout bounds how long. During an outage the relay pays one bounded timeout per cycle, not one per key (E3 §6).
* **Topic changes are code changes.** More partitions require a deliberate re-keying plan, because keys move to different partitions.
* **No authentication, schema registry or ACLs in dev** (TR-X1, programme §7). The binding does not depend on them, so they can be added later.
