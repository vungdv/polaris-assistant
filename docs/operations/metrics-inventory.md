# Metrics Inventory and Overlap Review: Polaris, Polaris-Assistant, Prometheus

Snapshot taken 2026-10-03 from the running local stack (`prometheus` container, `/api/v1/series{job="otel-collector"}`),
cross-checked against the source code, `docker/telemetry/otel-collector-config.yaml`, the Prometheus rule files and the
provisioned Grafana dashboards.

## 1. Pipeline and resource labels

```
Polaris / Polaris-Assistant (Micrometer OtlpMeterRegistry, ms base unit)
Polaris-Assistant GenAI      (agento11y OTel SDK, seconds)
        │  OTLP/HTTP :4318
        ▼
otel-collector ── memory_limiter → redaction/secrets → transform/metric_allowlist → batch
        │  prometheus exporter :8889                     └─► Grafana Cloud (OTLP)
        ▼
Prometheus (scrape job "otel-collector", 10s) ── rules: assistant-slo.rules.yml, genai-token-prices.yml
```

Every app series carries the same identity labels:

| Label | Value | Note |
|---|---|---|
| `job` | `otel-collector` | scrape job, not the service |
| `instance` | `otel-collector:8889` | the collector, not the pod |
| `exported_job` | `Polaris`, `Polaris-Assistant`, `Polaris-Fulfilment-Emulator`, `nginx-gateway` | from `service.name` |

Volume: **3,052 series** (Polaris 1,419 · Fulfilment emulator 947 · Assistant 674 · nginx 7).
1,104 of them are histogram `_bucket` series.

## 2. Inventory

Legend: **C** counter · **G** gauge · **H** histogram (Micrometer Timer → `_bucket/_count/_sum` + a separate `_max` gauge
+ often an `_active` long-task timer). `(n)` = distinct values observed. Used = referenced by a dashboard or rule.

### 2.1 Polaris (core) — business metrics

| Metric (Prometheus name) | Type | Labels | Series | Source | Used |
|---|---|---|---|---|---|
| `polaris_order_shipment_reports_total` | C | `outcome`(applied, ignored) — **`step`, `reason` dropped, see B1** | 1 | `ShipmentReportHandler.java:66` | no |
| `polaris_outbox_backlog_events` | G | – | 1 | `OutboxMetrics` | no |
| `polaris_outbox_oldest_pending_age_seconds` | G | – | 1 | `OutboxMetrics` | no |
| `polaris_outbox_pending_events` | G | `event_type` | 0 (only while pending) | `OutboxMetrics` | no |
| `polaris_outbox_pending_oldest_age_seconds` | G | `event_type` | 0 (only while pending) | `OutboxMetrics` | no |
| `polaris_outbox_handoff_milliseconds` | H | `destination`(1), `event_type`(5), `outcome`(success) | 360 | `OutboxMetrics` (percentile histogram, 69 buckets) | no |
| `polaris_outbox_delivery_lag_milliseconds` | H | `destination`(1), `event_type`(5) | 360 | `OutboxMetrics` (percentile histogram, 69 buckets) | no |
| `polaris_outbox_purged_events_total` | C | – | 1 | `OutboxMetrics` | no |
| `polaris_kafka_consumer_oldest_record_age_seconds` | G | `group`(4) | 4 | `ConsumerGroupMetrics` | no |
| `polaris_kafka_consumer_blocked_record_age_seconds` | G | `group`(4) | 4 | `ConsumerGroupMetrics` | no |
| `polaris_kafka_consumer_records_skipped_records_total` | C | `group`, `topic` | 0 (created on first skip, see B3) | `ConsumerGroupMetrics` | no |

### 2.2 Polaris-Assistant — business and GenAI metrics

| Metric | Type | Labels | Series | Source | Used |
|---|---|---|---|---|---|
| `polaris_assistant_turns_total` | C | `intent`(10: configured intents + `unknown`), `outcome`(answered, fallback, policy_denied, iteration_limit, failed) | 50 | `AssistantOutcomeMetrics` | assistant dashboard |
| `polaris_assistant_drafts_staged_total` | C | – | 1 | `AssistantOutcomeMetrics` | assistant dashboard |
| `polaris_assistant_draft_lifetime_milliseconds` | H | `outcome`(6), `cause`(none, price-changed, insufficient-stock, product-inactive) | 96 | `AssistantOutcomeMetrics` (SLO buckets 15s…30m) | assistant dashboard |
| `gen_ai_client_operation_duration_seconds` | H | `gen_ai_operation_name`(generateText, execute_tool), `gen_ai_provider_name`(gemini, typesafe), `gen_ai_request_model`(2), `gen_ai_agent_name`(polaris-assistant, polaris-intent-classifier), `gen_ai_tool_name`(4), `error_type`, `error_category`, `otel_scope_name` | 102 | agento11y SDK | AI dashboard |
| `gen_ai_client_token_usage` | H | `gen_ai_token_type`(input, output, reasoning), `gen_ai_token_semantics`(inclusive), provider, model, agent, operation, `otel_scope_name` | 51 | agento11y SDK | AI dashboard, price rule |
| `gen_ai_client_tool_calls_per_operation_count` | H | provider, model, agent, `otel_scope_name` | 36 | agento11y SDK | no |
| `processedSpans_total`, `queueSize_ratio`, `otlp_exporter_seen_total`, `otlp_exporter_exported_total`, `otel_sdk_metric_reader_collection_duration_seconds` | C/G/H | `otel_scope_name`, `type` | 9 | agento11y's embedded OTel SDK (self-telemetry) | no |

### 2.3 Framework metrics shared by Polaris and Polaris-Assistant

| Family | Type | Labels | Series (both apps + emulator) | Used |
|---|---|---|---|---|
| `http_server_requests_milliseconds` (+`_max`, `_active`, `_active_max`) | H | `method`(2), `uri`(9), `status`(5), `outcome`(SUCCESS, CLIENT_ERROR, SERVER_ERROR), `exception`, `error` | 140 | SLO rules, both HTTP dashboards |
| `spring_security_*` (filterchains before/after per filter, authentications, authorizations, http_secured_requests) | C/H | `spring_security_*` (filter name/section/position/size, object, authentication type), `authentication_*`, `error` | 152 | no |
| `spring_data_repository_invocations_milliseconds` (+`_max`) | H | `method`(17), `state`(SUCCESS), `exception`(None) | 72 | no |
| `spring_kafka_listener_milliseconds` (+`_max`, `_active*`) | H | `messaging_kafka_consumer_group`, `spring_kafka_listener_id`, `messaging_source_name`, `messaging_source_kind`, `messaging_operation`, `messaging_system`, `error` | 32 | no |
| `spring_kafka_template_milliseconds` (+`_max`, `_active*`) | H | `messaging_destination_name`, `spring_kafka_template_name`, `name`, `result`, `error`, `exception`, `messaging_*` | 24 | no |
| `kafka_consumer_*`, `kafka_producer_*`, `kafka_app_info_*` (Kafka client, 200 families) | C/G | `client_id`, `spring_id`, `group`, `kafka_version`, `topic`, `partition`, `node_id` | **1,130** | no |
| `lettuce_milliseconds` (+`_max`, `_active*`) | H | `db_operation`(6), `db_system`, `net_sock_peer_addr`, `net_sock_peer_port`, `net_transport`, `error` | 80 | no |
| `hikaricp_connections_*` (12 families) | G/C/H | `pool` | 30 | no |
| `jdbc_connections_{active,idle,max,min}` | G | `name` | 8 | no |
| `jvm_*`, `process_*`, `system_*`, `disk_*`, `tomcat_sessions_*`, `logback_events_total`, `application_*_time` | G/C/H | `area`, `id`, `gc`, `cause`, `action`, `state`, `level`, … | ~230 | no |

### 2.4 Prometheus-side series (recording rules)

| Recorded series | Labels | Derived from |
|---|---|---|
| `sli:requests:rate5m` | `service`, `endpoint`(chat, draft-decision), `sli`(availability, latency) | `http_server_requests_milliseconds_count{exported_job="Polaris-Assistant"}` |
| `sli:bad_requests:rate5m` | same | `_count` with `status=~"5.."` or `_bucket{le="30000"/"2000"}` |
| `sli:error_ratio:window` | same + `window`(5m, 30m, 1h, 6h) | the two above |
| `sli:error_budget:ratio` | same | constants |
| `polaris:gen_ai_token_price_usd` | `gen_ai_provider_name`, `gen_ai_request_model`, `gen_ai_token_type` | constants (price table) |

## 3. Findings

### 3.1 Defects (fix regardless of the overlap work)

| # | Finding | Evidence | Impact |
|---|---|---|---|
| **B1** | Collector allow-list silently drops `step` and `reason` from `polaris.order.shipment.reports`. | Code tags `step, outcome, reason`; Prometheus shows only `outcome`. `keep_matching_keys` regex has neither `step` nor `reason`. | Counters for different steps/reasons collapse into one data-point identity in the exporter: values overwrite each other, the metric is wrong, not just coarse. |
| **B2** | No per-instance identity on any app series (`instance` = the collector). | `exported_instance` absent on all 3,052 series. | Two replicas of one service emit identical series; the exporter keeps one, counters jump. SLO rules can't tell instances apart. |
| **B3** | Failure/skip series are created lazily. | `polaris_outbox_handoff{outcome="failure"}` and `polaris_kafka_consumer_records_skipped` don't exist yet. | The first failure appears as a new series at 1, so `increase()` shows 0 — the exact issue `AssistantOutcomeMetrics.registerKnownSeries` already fixed for the assistant. |
| **B4** | Service naming differs between data and rules. | Data: `exported_job="Polaris-Assistant"`; rules emit `service="polaris-assistant"`. | Two spellings of the same service; joins between raw and recorded series need relabeling. |

### 3.2 Metric overlap

| # | Overlap | Series | Verdict |
|---|---|---|---|
| **O1** | Kafka client metrics (`kafka_consumer_*`, 200 families) vs `spring_kafka_listener` + `polaris_kafka_consumer_*`. Only lag is unique to the client set. | 1,130 | Keep ~5 families (records lag/lag_max/consumed, rebalances, producer errors); drop the rest. |
| **O2** | `*_active_milliseconds` long-task timers next to every Observation timer (http, security, kafka, lettuce). Nothing reads them; in-flight concurrency is already in `tomcat`/`hikaricp` gauges. | 114 | Drop. |
| **O3** | `*_max_milliseconds` gauges next to every histogram. Max over a step is lossy and duplicates the histogram's top bucket. | 89 | Drop (keep only if a dashboard needs a peak). |
| **O4** | `spring_security_filterchains_*_{before,after}_total`: 40 counters equal to the request count, one per filter. | 152 (whole family) | Disable the Spring Security observations; auth failures are visible as `http_server_requests{status="401"/"403"}`. |
| **O5** | `polaris_outbox_backlog` = `sum(polaris_outbox_pending)`; `polaris_outbox_oldest_pending_age` = `max(polaris_outbox_pending_oldest_age)`. | 2 (+DB query work) | Keep only the per-`event_type` gauges; derive totals in PromQL. |
| **O6** | Outbox timers use `publishPercentileHistogram()` → 69 buckets × 5 event types × 2 timers. | 720 | Replace with ~10 explicit SLO buckets. |
| **O7** | `jdbc_connections_*` duplicates `hikaricp_connections_{active,idle,max,min}`. | 8 | Drop `jdbc`. |
| **O8** | Agent SDK self-telemetry (`processedSpans_total`, `queueSize_ratio`, `otlp_exporter_*`) overlaps with collector self-metrics (`otelcol_receiver_accepted_*`), and uses non-semconv camelCase names. | 9 | Drop or rename; low priority. |
| — | `polaris_assistant_turns_total` vs `http_server_requests{uri="/api/v1/assistant/chat"}` vs `gen_ai_client_operation_duration{gen_ai_agent_name="polaris-assistant"}` | – | **Not overlap — keep all three.** Transport availability, business outcome per intent, and per-model-call latency/cost answer different questions. |

### 3.3 Label overlap and inconsistency

| # | Concept | Current spellings | Problem |
|---|---|---|---|
| **L1** | "did it fail" | `outcome`(SUCCESS/CLIENT_ERROR/SERVER_ERROR), `status`, `error`, `exception`, `result`(success), `state`(SUCCESS), `error_type`, `error_category` | 4–5 labels per timer saying the same thing; values mix `none` / `None` / `SUCCESS` / `success`. `http_server_requests` carries `status`, `outcome`, `exception` and `error` together. |
| **L2** | Consumer group | `group` (custom + Kafka client), `messaging_kafka_consumer_group` (Spring Kafka) | Same values, two names: no join without `label_replace`. |
| **L3** | Topic / destination | `topic`, `messaging_destination_name`, `messaging_source_name`, `destination` (outbox) | Same Kafka topic, four names. |
| **L4** | Event type | `event_type` (outbox) | Semconv name is `cloudevents.event_type`. |
| **L5** | Kafka template name | `name` and `spring_kafka_template_name` (identical values) | Pure duplicate. |
| **L6** | Kafka client identity | `client_id`, `spring_id`, `kafka_version` | Per-client churn on every restart; `group` already identifies the consumer. |
| **L7** | Redis peer | `net_sock_peer_addr`, `net_sock_peer_port`, `net_transport` | Constant values, deprecated semconv keys. |
| **L8** | Time unit | Micrometer metrics in `milliseconds`, GenAI metrics in `seconds` | Mixed units on one dashboard; OTel semconv and Prometheus convention are seconds. |
| **L9** | GenAI operation | `gen_ai_operation_name="generateText"` | Not a semconv value (`chat`, `generate_content`, `execute_tool`, …). Needs checking against what agento11y / Grafana AI Observability expects before changing. |

## 4. Recommendations

Ordered by value ÷ risk. Series estimates are from this snapshot.

| # | Change | Where | Series impact | Breaks dashboards/rules? |
|---|---|---|---|---|
| **R1** | Add `step` and `reason` to `transform/metric_allowlist`, and add a test that every tag key used in code matches the allow-list regex. | `otel-collector-config.yaml` | +few (correctness) | no |
| **R2** | Keep only a short allow-list of Kafka client metrics with the collector `filter` processor; drop `client_id`, `spring_id`, `kafka_version`. | collector | **−1,036** | no (unused) |
| **R3** | Replace `publishPercentileHistogram()` on the outbox timers with explicit buckets via `management.metrics.distribution.slo.polaris.outbox.handoff` / `.delivery.lag` (e.g. 10ms, 50ms, 100ms, 250ms, 500ms, 1s, 5s, 30s, 60s, 5m). Drop the total gauges (O5). | `OutboxMetrics`, Polaris `application.yml` | **≈ −560** | no (unused) |
| **R4** | Turn off Spring Security observations: `management.observations.enable.spring.security=false`. | both apps' `application.yml` | **−152** | no (unused) |
| **R5** | Drop `*_active_*` and `*_max_*` series with a collector `filter` (or `ObservationPredicate`). | collector | **−203** | no (unused) |
| **R6** | `management.metrics.enable.jdbc=false`; drop agent SDK self-metrics. | `application.yml`, collector | −17 | no |
| **R7** | Pre-register zero series for outbox `outcome="failure"` per event type and for `records_skipped` per group (B3), same as `AssistantOutcomeMetrics`. | `OutboxMetrics`, `ConsumerGroupMetrics` | +~10 | no |
| **R8** | Instance identity (B2) and one service label (B4): set `service.instance.id` in the apps and either `honor_labels: true` on the scrape job, or switch to Prometheus 3's native OTLP receiver with `promote_resource_attributes: [service.name, service.instance.id, deployment.environment.name]` and remove the exporter hop. Use the same `service_name` in the SLO rules. | Prometheus, collector, rules | 0 | **yes** — `exported_job` selectors in rules and dashboards change |
| **R9** | Standardize labels on OTel semconv (Principle 1): `error.type` (only set on failure) instead of `error`/`exception`/`result`/`state`; `messaging.consumer.group.name` instead of `group`; `messaging.destination.name` instead of `topic`/`destination`; `cloudevents.event_type` instead of `event_type`; drop `name` (L5) and `net_*` (L7). Business `outcome` labels on `polaris.*` metrics stay — they are domain enums, not error flags. | custom metrics in code; collector `transform` for framework ones; allow-list | ≈ 0 (fewer labels per series) | **yes** for custom metrics — none are on dashboards today, so cheapest now |
| **R10** | Seconds everywhere: `management.otlp.metrics.export.base-time-unit=seconds` in both apps. Optionally adopt Spring's OpenTelemetry HTTP conventions (`http.server.request.duration` with `http.request.method`, `http.route`, `http.response.status_code`, `error.type`), which also removes the L1 overlap on HTTP. | both apps, SLO rules (`le="30"` / `le="2"`), 3 dashboards | 0 | **yes** — do it in one change with the rule/dashboard updates and the rule unit tests |

**Projected result:** ≈ 3,050 → ≈ 1,100 series (**−64 %**), with every dashboard, SLO rule and alert unchanged after
R1–R7. R8–R10 are the label-consistency work; they touch the SLO rules and dashboards, so ship each as its own change
with `promtool test rules` (`docker/telemetry/prometheus/tests/assistant-slo.test.yml`) updated in the same diff.

### Suggested allow-list after R1 + R9

```
^(http|url|server|error|exception|jvm|db|messaging|cloudevents|system|process|spring|authentication|network|rpc|
  state|area|id|pool|gc|cause|action|outcome|status|method|uri|level|topic|partition|client|type|path|version|vendor|
  runtime|compiler|main|cache|scope|service|deployment|host|le|quantile|gen_ai|intent|step|reason)([._].*)?$
```

(`net`, `kafka`, `group`, `destination`, `event`, `name`, `result` removed once R9 lands; `step`, `reason`,
`cloudevents` added. `topic`/`partition` stay because the kept Kafka client lag metric is labelled with them.)
