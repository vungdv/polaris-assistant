# ADR-0022: API Management Platform for the Public Consumer API

* **Status:** Proposed (2026-10-08)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering
* **Date:** 2026-10-08
* **Technical Story:** Add an API Management (APIM) layer for learning and experimentation, so that Polaris can practise how an enterprise publishes REST APIs to external consumers at high volume, on top of a back end shaped by acquisitions and legacy systems.
* **Plan Reference:** [API Management](../../development/plan/api-management.md)
* **Builds on:** [ADR-0009](0009-gateway-subpath-routing-for-applications.md) (gateway sub-path routing), [ADR-0010](0010-mcp-client-authentication-and-token-forwarding.md) (OAuth 2.0 with Keycloak), [ADR-0021](0021-kubernetes-deployment-platform.md) (Kubernetes platform)

---

## 1. Context and Problem Statement

Today `nginx` (Compose) and NGINX Gateway Fabric (Kubernetes, ADR-0021 D5) are **reverse proxies**. They terminate TLS and route by path. They don't know who the caller is as a business consumer, what the caller is allowed to use, or how much of it.

The goal of this decision is learning. We want to practise the role an APIM plays in a large, acquisition-heavy enterprise, such as a global insurer that grew by merging many companies. Such an enterprise typically has:

* **External consumers** (brokers, agents, partners, aggregators, direct customers' apps) calling public REST APIs at high volume.
* **Several back ends for the same business capability.** Each acquired company keeps its own policy, claims or order system for years. Some speak SOAP or older REST styles, use different identifiers, and have different capacity.
* **One public contract** that has to stay stable while the back ends behind it are merged, migrated and retired.

The APIM is the layer that turns internal services into a managed **product** for those consumers. Its standard responsibilities are:

| Responsibility | What it means |
|:--|:--|
| Consumer onboarding | Consumers register an *application*, receive credentials and subscribe to a *plan* |
| Productisation | APIs are bundled into plans with different quotas, rate limits and SLAs (for example Free, Partner, Premium) |
| Edge security | API keys, OAuth 2.0 client credentials and JWT validation (JWKS), checked before traffic reaches a service |
| Traffic policy | Per-consumer rate limits and quotas, spike arrest, payload size limits, timeouts and circuit breaking towards the back end |
| Mediation | Header and payload transformation, version mapping, and routing one public path to the right legacy back end |
| Lifecycle | Versioning, deprecation (`Deprecation` and `Sunset` headers), publish and retire |
| Developer portal | Catalogue, OpenAPI documentation and self-service subscription |
| Consumer analytics | Calls, latency and errors per consumer, plan and API, with tracing into the back end |

**Which APIM product should Polaris adopt to practise these responsibilities locally, with the standards this repository requires?**

---

## 2. Decision Drivers

* **Covers the full APIM role, not just a gateway.** Plans, applications, subscriptions, a developer portal and consumer analytics must be included, because these are what separate an APIM from a reverse proxy.
* **Concepts transfer to Kong and Azure APIM.** What we learn should map directly onto the products used in industry.
* **Free and self-hosted.** It must run on a laptop under Compose and on the kind cluster from ADR-0021, without a cloud subscription or a licence key.
* **Standards first ([AGENTS.md Principle 1](../../../AGENTS.md)).** OAuth 2.0 and JWT validated against Keycloak's JWKS, OpenAPI 3.x import, W3C Trace Context and OTLP, Problem Details errors.
* **Configuration as code.** APIs, plans and policies must be declared in git and applied reproducibly, not clicked together in a console.
* **Open source with a living release line.** Images must be published and receive security fixes.

---

## 3. Considered Options

| Option | Full APIM role in the free edition? | Runs locally? | Standards fit | Verdict |
|:--|:--|:--|:--|:--|
| **Kong Gateway OSS** | Gateway only. Developer portal, RBAC and the `openid-connect` plugin are Enterprise. The OSS `jwt` plugin needs a static key per consumer, not JWKS discovery | Yes | Partial | **Rejected.** Kong stopped publishing OSS images and packages from 3.10 (March 2025). The OSS line is frozen at 3.9.x, so it no longer receives releases |
| **Azure API Management** (Developer tier, about $48/month) | Yes. Products, subscriptions, portal, policies, analytics | Only the self-hosted gateway runs locally, and it still needs an Azure instance | Good | **Rejected as the base**, because of the subscription cost and cloud dependency. Kept as an optional cloud lab (section 6) |
| **Apache APISIX** (Apache 2.0) | Gateway only. Consumers, `openid-connect`, rate limiting and Gateway API support are strong, but there is no developer portal or subscription workflow | Yes | Good | **Rejected.** It is an excellent gateway, but teaches only half of the APIM role |
| **WSO2 API Manager** (Apache 2.0) | Yes | Yes, but heavy | Good | **Rejected.** Large footprint, and its concept names are further from Kong and Azure |
| **Gravitee APIM Community Edition** (Apache 2.0) | Yes. APIs, plans, applications, subscriptions, developer portal and analytics | Yes, with a Helm chart and a Kubernetes operator | Good. JWT plans against a JWKS URL, OAuth 2.0 plans, OpenAPI import, OpenTelemetry | **Selected** |

---

## 4. Decision Outcome

Chosen option: **Gravitee APIM Community Edition**, used as a dedicated **public API layer** behind the existing edge gateway.

### 4.1 Concept mapping

The concepts map one to one onto the products the team will meet in industry:

| Gravitee | Azure APIM | Kong |
|:--|:--|:--|
| API (v4 proxy) | API | Service + Route |
| Plan (Keyless, API key, JWT, OAuth 2.0) | Product | Consumer Group + auth plugin |
| Application | Subscription owner (user or app) | Consumer |
| Subscription | Subscription (key) | Credential on a Consumer |
| Flow policies (request / response) | Policies (inbound / backend / outbound / on-error) | Plugins |
| Developer Portal | Developer Portal | Dev Portal (Konnect / Enterprise) |
| Management API + Console | Azure Resource Manager + portal | Admin API + Kong Manager |
| Gravitee Kubernetes Operator CRDs | Bicep / ARM / APIOps | decK / Kong Ingress Controller CRDs |

### 4.2 Topology

```
consumer ──HTTPS──▶ edge (nginx / NGINX Gateway Fabric) ──▶ Gravitee gateway ──▶ polaris, legacy-northwind (simulated acquisition)
                    TLS, host routing                        plans, auth, quotas,
                                                             mediation, analytics
```

* **New host `api.polaris.local`** for the public consumer API, served by the Gravitee gateway. The edge keeps terminating TLS and routes the host to Gravitee.
* **`polaris.local` is unchanged.** The first-party web client, MCP and the assistant keep the direct routes from ADR-0009. This keeps the distinction enterprises make between their own channels and the managed external API product.
* **Developer portal** at `developer.polaris.local`. **Management console** at `apim.polaris.local`, signed in through Keycloak OIDC.
* **Identity stays in Keycloak.** Consumer applications are Keycloak confidential clients using the client credentials grant. Gravitee JWT plans validate tokens against `https://id.polaris.local/realms/polaris/protocol/openid-connect/certs`. The APIM never issues its own tokens.
* **Storage reuses what the stack already runs.** The management repository uses the JDBC (PostgreSQL) backend in its own database, and distributed rate limiting uses a dedicated Redis for the APIM (Gravitee's Redis rate-limit repository can't select a logical database in the shared Redis).
* **Observability goes through the existing Grafana stack, not a Gravitee analytics store.** Gravitee's built-in analytics read from Elasticsearch or OpenSearch. We don't run either. Instead (section 4.5):
  * **Per-request records:** the gateway's TCP reporter sends one JSON record per call (API, plan, application, subscription, status, latency, `trace_id`) to the OTel Collector, which forwards it to Loki.
  * **Metrics:** the Collector scrapes the gateway's Prometheus endpoint.
  * **Traces:** the gateway joins the W3C trace started at the edge and exports spans over OTLP, so one trace runs from consumer to database ([AGENTS.md Principle 3.1](../../../AGENTS.md)).
  * **Dashboards:** consumer analytics are Grafana dashboards provisioned from git, next to the existing ones.
* **Configuration as code.** On Kubernetes, APIs, plans and policies are `ApiV4Definition` and related CRDs applied by the Gravitee Kubernetes Operator, in `deploy/k8s/platform/apim/`. On Compose, the same definitions are applied through the Management API by a script. The console is for exploration only; git is the source of truth.

### 4.3 Guardrails

* **No business logic in the APIM.** Policies may authenticate, limit, transform headers and payload shape, route and cache. They must not orchestrate several services or make domain decisions. Domain rules stay inside their bounded context ([AGENTS.md Principle 2.2](../../../AGENTS.md)).
* **Errors are Problem Details.** Gateway-generated errors (401, 403, 429, 502, 504) use `application/problem+json` (RFC 9457), with `Retry-After` on 429.
* **Contracts are OpenAPI.** Each public API is imported from the service's published OpenAPI document, not redefined by hand.

### 4.4 Learning scenarios

The experiment is built as a series of small slices, each one covering a single APIM responsibility:

1. **Publish and subscribe.** Import the catalog API, publish it with a Keyless plan and then a JWT plan, register a partner application in the portal and subscribe.
2. **Plans and quotas.** Free, Partner and Premium plans with different rate limits and daily quotas, enforced across replicas through Redis, verified with a load test (k6).
3. **Acquired-company back end.** A simulated acquisition, "Northwind", keeps its own legacy catalog system (a WireMock container that answers in an older XML format with its own identifiers). The public catalog API routes Northwind SKUs to it and maps the response to the canonical contract in the gateway, so consumers can't tell the two back ends apart.
4. **Migration behind a stable contract.** Weighted routing moves Northwind traffic from the legacy back end to `polaris` (strangler fig), with no visible change to consumers.
5. **Versioning and sunset.** Publish `v2` beside `v1`, mark `v1` with `Deprecation` and `Sunset` headers, then retire it.
6. **Resilience.** Back-end timeouts and a circuit breaker, observed in traces when the emulator is slowed down.
7. **Consumer analytics.** Calls, latency and errors per application and plan, in Grafana, built from Loki records and gateway metrics.

### 4.5 Observability without an analytics store

Gravitee's analytics repository is turned off. The gateway still produces all the data the console would show, but it is sent to the observability stack the rest of Polaris already uses (Loki, Prometheus, Tempo and Grafana locally; Grafana Cloud through the Collector).

| Signal | Path | Used for |
|:--|:--|:--|
| Request record (JSON) | TCP reporter → OTel Collector (`tcplog` receiver, JSON parser) → Loki | Calls, errors and latency per API, plan and application; audit of who called what |
| Gateway metrics | Prometheus endpoint → Collector scrape → Prometheus | Gateway health, throughput, latency histograms |
| Spans | OTLP → Collector → Tempo | One trace from edge through gateway to back end |

**Label rules.** Loki stream labels are limited to low-cardinality values: `service_name`, `api` and `plan`. Application, subscription and consumer IDs stay as fields inside the JSON line, and are queried with LogQL filters and `| json`. Per-consumer panels are LogQL aggregations or Loki recording rules, so new consumers never create new streams.

This follows the pattern enterprises use in production. Azure APIM sends data to Azure Monitor and Application Insights, and Kong uses its Prometheus and OpenTelemetry plugins. In both cases the data goes to a central observability platform instead of the APIM vendor's own store.

---

## 5. Consequences

### Positive

* The whole APIM lifecycle (publish, subscribe, govern, observe, version, retire) can be practised locally at no cost.
* Concepts transfer directly to Azure APIM and Kong (section 4.1).
* The public API layer is separate from the first-party routes, so the experiment cannot break the existing flows.
* Keycloak stays the only identity provider, and OTel stays the only telemetry path.
* Consumer, gateway and back-end telemetry live in one place. A partner's 429 or 504 can be followed from the request record to the trace and the back-end span in the same Grafana.
* No Elasticsearch or OpenSearch to run. That saves 1–2 GB of memory and one stateful component on an already heavy local stack.

### Negative

* Four new components to run and upgrade (gateway, management API, console, portal). The local stack becomes heavier, so the APIM is an optional Compose profile and an optional Kubernetes component.
* An extra network hop on the public path, which adds latency that has to be measured in the load-test scenario.
* Some features from the commercial edition (advanced analytics, some policies) are not available. They are not needed for the scenarios above.

### Consequences of having no analytics store

* **Gravitee's built-in analytics are empty.** The console's API and application dashboards and its request log viewer show nothing. API owners use the Grafana dashboards instead, which we have to build and maintain ourselves.
* **No consumer analytics in the developer portal.** In a real enterprise, partners usually see their own usage in the portal. We can't practise that part of the role. The nearest substitute is a Grafana dashboard filtered by application, which is an internal view, not a self-service one.
* **Request and response body logging has no home.** Gravitee stores logged payloads in the analytics store. Debugging with full payloads is done from traces and the back end's own logs. This also keeps personal data out of the gateway logs.
* **Dashboards depend on the record format.** The TCP reporter's JSON fields may change between Gravitee versions. A smoke test checks that the fields used by dashboards are present after each upgrade.
* **Cardinality has to be managed.** Per-consumer views come from LogQL queries over JSON fields, not from labels. They get slower as volume grows, and frequent views become Loki recording rules.
* **Loki is not an analytics database.** Long-range business reporting (monthly calls per partner for billing) is out of scope. In an enterprise it would come from a data platform fed by the same records.
* **Reversible.** OpenSearch can be added later as an optional component by turning the analytics repository back on. Nothing in sections 4.1–4.4 depends on its absence.

### Risks

* **Business logic creeping into gateway policies.** Mitigated by the guardrail in section 4.3 and by code review of the CRDs.
* **Configuration drift between console and git.** Mitigated by treating git as the source of truth and re-applying definitions in CI.
* **Turning off the analytics repository may not be fully supported** by the chosen Gravitee version (the management API may still expect one). Verified in the first implementation slice. If it can't be turned off, this decision is revisited before any workaround is built.

---

## 6. Optional cloud lab

To compare with a managed product, the same APIs and plans can be reproduced on an Azure APIM Developer tier instance for a limited period, using its self-hosted gateway on the kind cluster. This is a later experiment and is not part of this decision.

---

## 7. Status

Proposed. Accept once scenario 1 runs end to end on Compose with analytics in Grafana (plan slices A1–A3), and the analytics repository is confirmed to be off.
