# Detail Design: F2 — Fulfilment Emulator

- **Plan:** [Plan 3: Multi-Partner Fulfilment](../../plan/order-notifications-and-fulfilment/03-fulfilment-emulator.md), slice **F2**
- **Covers:** TR-F1–F5, TR-X2, TR-X3 (and TR-X1 for the service client, already in the realm export)
- **Decision records:** [ADR-0019 Kafka and CloudEvents binding](../../../technical/decisions/0019-kafka-and-cloudevents-binding.md) (§4.1 consumer conventions), programme decisions D1 (claim over REST), D2 (reports over Kafka), D3 (per-partner group), D4 (no claim retry)
- **Builds on:** F1 claim endpoint `POST /api/v1/orders/{orderNumber}/claim`; `libs/polaris-events` contracts (`OrderEvents`, `OrderLifecycleEvent`, `FulfilmentEvents`, `ShipmentEvent`)
- **Status:** Draft for review

## 1. Scope

In: a new stateless Spring Boot app `apps/polaris-fulfilment-emulator` (Fulfilment context) that consumes `order.placed.v1` as an offer for every configured partner, claims through the Order REST API after a random pause, and lets the winner report packed, dispatched and delivered on `polaris.fulfilment.shipments`. Also the reactor, Dockerfile, compose and Makefile wiring (the existing app Dockerfiles gain the new module's `pom.xml`, which the reactor needs).

Out: Order's consumption of shipment events (F3), the end-to-end run and docs (F4), authenticated per-partner producers (programme §7). The shipment event contract already exists in `libs/polaris-events`, so no other context changes.

## 2. Design questions answered

| Question | Decision |
|:--|:--|
| Per-partner consumption | **One Kafka consumer group and one listener container per partner**, group `fulfilment.<partnerId>` (ADR-0019 §4.1.6). Each group independently receives every `order.placed.v1` (D3, TR-F1). Partners come from configuration, so adding one is a config change (§3) |
| Delay scheduling that preserves the trace | The listener **never sleeps**. It captures the OpenTelemetry `Context` of the consumer span (already a child of the producer span that crossed Kafka) and schedules the claim on a `ScheduledExecutorService` through `Context.wrap`. The claim call and each shipment step run inside that same context, so the auto-instrumented HTTP client span and the Kafka producer spans are children of the offer's trace (§4) |
| Token acquisition and refresh | Spring Security OAuth2 client, `client_credentials` grant, one registration `polaris-fulfilment-emulator`, secret from `POLARIS_FULFILMENT_EMULATOR_SECRET`. An `OAuth2AuthorizedClientManager` caches the access token and fetches a new one shortly before it expires; the claim `RestClient` adds it as `Authorization: Bearer` on every call. All partners share this one client (TR-F4) (§5) |
| Starting offset for a new partner group | **`earliest`**, stated explicitly per group. See §6 for what happens to orders placed before the emulator first starts |

## 3. Components

Package `vn.danang.polaris.fulfilment` (one responsibility each, dependencies point inward):

| Class | Role |
|:--|:--|
| `FulfilmentProperties` | `polaris.fulfilment.*`: `partners` (list of ids), `claim-pause` (min, max), `step-delay`, `topic` names |
| `OfferListenerRegistrar` | For each partner, creates and starts a `ConcurrentMessageListenerContainer` on `polaris.order.lifecycle` with group `fulfilment.<partner>`, `auto.offset.reset=earliest`, observation enabled, `AckMode.RECORD` |
| `OfferHandler` | Per record: read with the CloudEvents Kafka binding, ignore every type except `order.placed.v1` (DEBUG, offset committed), log `ce_id`, `ce_type`, `orderNumber`, and hand the offer to the partner |
| `PartnerAgent` | One per partner. Draws a pause from the injected `RandomGenerator`, schedules the claim, on `200` schedules the three steps, on `409` logs and drops, on any other outcome logs and drops. Never retries a claim (TR-F3, D4) |
| `ClaimClient` | Port `Claim claim(orderNumber, partnerId)` returning `WON`, `LOST` or `FAILED`; implemented over `RestClient` with the bearer token. The only outward REST dependency, stubbed in tests |
| `ShipmentPublisher` | Sends one `ShipmentEvent` as a binary-mode CloudEvent (key = order number, `ce_type` from `ShipmentStep`) to `polaris.fulfilment.shipments`; declares that topic (Fulfilment owns it, ADR-0019 §4.4) |
| `FulfilmentMetrics` | Micrometer counters and timer (§7) |

The clock and randomness are beans (`java.util.random.RandomGenerator`, `ScheduledExecutorService`), so tests replace them with a seeded generator and a controllable executor. The generator is `java.util.Random`, not `RandomGenerator.getDefault()` (L32X64MixRandom lives in `jdk.random`, absent from the JRE runtime image). No datasource, JPA, Flyway or web starter is on the classpath; only actuator is exposed (TR-F4).

Not reused: `CloudEventsKafkaBinding` in `polaris-outbox`. That module auto-configures around a datasource and relay; the emulator writes the record with `KafkaMessageFactory` directly (a few lines) and depends on `polaris-events` only.

## 4. Flow and trace continuity

1. A partner's container receives `order.placed.v1`. With listener observation on, Spring Kafka starts a CONSUMER span whose parent is the producer span taken from the record's `traceparent` header, so the listener runs in the order's original trace.
2. `PartnerAgent.onOffer` captures `Context.current()`, picks `pause ∈ [min, max]` and schedules `claim` with `context.wrap(task)`. The listener returns immediately; the offset is committed (`RECORD` ack). A long pause therefore never blocks polling or triggers a rebalance.
3. When the pause ends, `ClaimClient` posts `{"partnerId": …}`. The Spring `RestClient` is built from the auto-configured builder, so it records an HTTP client span (child of the offer's context) and sends `traceparent` to Order. Order's request span and its own outbox events continue the same trace.
4. `200`: the agent schedules PACKED now, then DISPATCHED and DELIVERED each `step-delay` after the previous one, again with the captured context. `ShipmentPublisher` uses a `KafkaTemplate` with observation enabled, so each send is a producer span in the trace and the record carries its `traceparent`. `409`: log `claim lost` with `orderNumber`, `partnerId`, and drop. Nothing else happens.
5. In-flight work lives only in the emulator's memory. If the emulator stops during a pause or between steps, the demo order stalls (accepted, TR-F4); the committed offset means it is not offered again.

Shipment events are sent directly, not through an outbox: a send failure is logged at `ERROR`, counted, and not retried (accepted, TR-F4). Steps are sent in order on one key, and the producer is idempotent, so a key's steps never reorder.

Poison offers follow ADR-0019 §4.1.4: `DefaultErrorHandler` with a short `FixedBackOff` (2 retries), then an `ERROR` log with topic, partition, offset, `ce_id`, `ce_type`, a failure count, and the record is skipped.

## 5. Token acquisition and refresh

```yaml
polaris.fulfilment.auth:
  token-uri: ${POLARIS_FULFILMENT_TOKEN_URI:https://id.polaris.local/realms/polaris/protocol/openid-connect/token}
  client-id: polaris-fulfilment-emulator
  client-secret: ${POLARIS_FULFILMENT_EMULATOR_SECRET:}    # env only, no default
```

The app depends on the `spring-security-oauth2-client` library only, not the Boot security starter, so the registration is built in code from these properties and actuator stays unsecured.

- `token-uri` is set explicitly instead of `issuer-uri`, so the app starts without Keycloak reachable (no discovery call at startup). The token is fetched on the first claim.
- `AuthorizedClientServiceOAuth2AuthorizedClientManager` with the `client_credentials` provider. It reuses the cached token and requests a new one when the current one is within the default 60 s clock skew of expiry. There is no refresh token in this grant; refresh means requesting a new token. No hand-written scheduling or expiry logic.
- The `RestClient` uses `OAuth2ClientHttpRequestInterceptor` for the registration. Claims are serialized through the manager so a cold or expired token is fetched once, not once per partner. A token fetch failure fails that claim with an `ERROR` log and the `FAILED` outcome. The claim is not retried (D4); the next offer tries again.
- The secret comes from `POLARIS_FULFILMENT_EMULATOR_SECRET` (the same variable as the realm import; compose supplies the dev default). The service account holds `order.fulfil` in the realm export.

## 6. Starting-offset policy (TR-F5, TR-B6)

Each partner group is created with **`auto.offset.reset=earliest`**, set explicitly in code next to the group id. It applies only when a group has no committed offset, that is, the first time a partner starts.

| Situation | Behaviour |
|:--|:--|
| Emulator running when the order is placed | Offered immediately, as normal |
| Emulator down, or not yet deployed, when the order was placed | The `order.placed.v1` is still on the topic (Kafka retains it). The first start of each partner group reads from the beginning of the topic and offers **every retained `order.placed.v1`**, each after its own random pause |
| Such an order is still `PLACED` | It is claimed and fulfilled as normal, later than usual. This is the reason for `earliest`: a partner must not silently miss offers (ADR-0019 §4.1.5) |
| Such an order was already claimed, cancelled or is otherwise not `PLACED` | Every partner gets `409`, logs `claim lost` and drops it. No state changes and no shipment events (Order's claim guard, TR-O2) |
| Restart of a group that already has offsets | Resumes where it left off; nothing is re-offered except an uncommitted in-flight record |
| Order placed before the topic's retention window (default 7 days) | No longer on the topic and never offered; it stays `PLACED` until an operator acts (out of scope) |

Trade-offs accepted: the first start on a long-lived environment makes up to `partners × retained placed events` claim calls, spread by the random pauses; all but the genuinely unclaimed ones end in `409`. `latest` would avoid that burst but would strand every order placed while the emulator was absent, which the demo cannot tolerate. Adding a partner later replays history for that partner's new group only, with the same `409` outcome for orders another partner already fulfilled.

## 7. Observability (TR-X2)

- **Logs:** key=value log lines through the same logback setup as the other apps (Boot console pattern with the trace/span correlation, plus the OpenTelemetry appender for OTLP export with `trace_id` and `span_id`). Every line about an offer includes `orderNumber`, `partnerId`, `ce_id`, `ce_type` where known.
- **Metrics (OTLP, Micrometer):** `polaris.fulfilment.offers` (tags `partner`, `outcome=received|ignored`), `polaris.fulfilment.claims` (`partner`, `outcome=won|lost|failed`), `polaris.fulfilment.claim.duration` timer, `polaris.fulfilment.shipments` (`partner`, `step`, `outcome=sent|failed`).
- **Traces:** offer consumer span, claim HTTP client span, shipment producer spans, all in the order's trace (§4). Actuator exposes `health` and `info` only.

## 8. Test plan (F2 acceptance criteria)

The claim endpoint and the token endpoint are stubbed with WireMock (the external boundary). Kafka is a real broker (Testcontainers, same image and no auto-create as compose), an in-memory span exporter captures spans, and the random generator is seeded.

| Criterion | Test |
|:--|:--|
| One offer → one claim per partner with distinct `partnerId` | Publish one `order.placed.v1`; WireMock receives exactly one claim per configured partner, ids all different |
| Winner → exactly three shipment events in order with its `partnerId`; loser → none | Stub `200` for one partner and `409` for the rest; consume `polaris.fulfilment.shipments`: `packed`, `dispatched`, `delivered`, all with the winner's id, none from the others |
| Seeded delays over 10 offers → more than one distinct winner | Unit test of `PartnerAgent` with a seeded generator, a manual scheduler and a stub where the first claim per order wins: 10 offers, at least two winners |
| Starts without a datasource | The Spring context test runs with no `DataSource` bean and no datasource class on the classpath |
| Claim calls carry a service-account token | WireMock token endpoint issues a token; the claim stub only matches `Authorization: Bearer <token>`; the token request used `client_credentials` and the env secret |
| One trace: offer → claim → shipment events | The offer is published with a known `traceparent`; claim requests and every shipment record carry that `trace-id`, and the captured spans share it |
| No claim retries | A `FAILED` claim (e.g. `500`) produces exactly one request and no shipment events |
