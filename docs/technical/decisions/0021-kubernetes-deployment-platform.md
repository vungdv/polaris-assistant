# ADR-0021: Kubernetes Deployment Platform

* **Status:** Proposed (2026-10-06, Kubernetes plan K1)
* **Deciders:** Polaris Architecture Team, Core Platform Engineering, SRE Lead
* **Date:** 2026-10-06
* **Technical Story:** Run the whole stack that `docker-compose.yml` runs today on Kubernetes, starting with a local kind cluster, with manifests that stay cloud-agnostic so a cloud environment is only a new overlay.
* **Plan Reference:** [Kubernetes Deployment](../../development/plan/k8s-deployment.md) (TR-K1–TR-K11, decisions D1–D10)
* **Builds on:** [ADR-0006](0006-grafana-domain-routing-and-tls-architecture.md) (domains and TLS), [ADR-0009](0009-gateway-subpath-routing-for-applications.md) (gateway sub-path routing), [ADR-0019](0019-kafka-and-cloudevents-binding.md) (Kafka cluster and topic ownership)

---

## 1. Context and Problem Statement

Compose runs the gateway, Keycloak, PostgreSQL, Redis, a three-node Kafka cluster, the OTel Collector and the three applications on one host. That is fine for the inner loop, but it can't show what production needs: several replicas behind a load balancer, rolling restarts, health-driven traffic, disruption budgets, network isolation and a non-root runtime.

The flows that work on Compose (OIDC login through `https://id.polaris.local`, the APIs and MCP through `https://polaris.local`, assistant SSE chat, order events through Kafka, one trace from the gateway down) have to keep working on Kubernetes without app-specific workarounds.

**How should Polaris package, install and operate the stack on Kubernetes so that it stays standard, reproducible and portable from a laptop to a cloud cluster?**

---

## 2. Decision Drivers

* **Standards first ([AGENTS.md Principle 1](../../../AGENTS.md)):** plain Kubernetes APIs, the Gateway API, upstream operators and charts. No bespoke templating or forked charts (TR-K1).
* **Reproducible (TR-K2):** one command from a clean machine; every tool, chart and image version pinned; application images tagged by git SHA.
* **Config as data (TR-K3):** ConfigMaps and Secrets generated from an untracked env file with the same keys as `.env.template`.
* **Parity with Compose (TR-K6, TR-K8):** same Kafka topology and topic ownership, same Postgres version, one OIDC issuer for browsers and pods.
* **Least privilege (TR-K10):** Pod Security `restricted`, default-deny NetworkPolicies.
* **Verified in CI (TR-K11):** every change is smoke-tested on a kind cluster in GitHub Actions through the same public interfaces as production.

---

## 3. Considered Options and Decision Outcome

Each row is one decision. The chosen option is in bold.

| # | Decision | Chosen | Why | Rejected alternative |
|:--|:--|:--|:--|:--|
| D1 | Packaging for our apps | **Kustomize** (`base` + `overlays/<env>`) | Ships with `kubectl`, no templating language, overlays map one-to-one to environments | Helm umbrella chart: a templating layer we'd own for three apps |
| D2 | PostgreSQL | **CloudNativePG** operator, one `Cluster` per owner (`polaris-db`, `keycloak-db`) | Declarative backups and failover on standard Postgres images; keeps one database per owner as in Compose | Bitnami chart, plain StatefulSet: failover and backups become ours to script |
| D3 | Kafka | **Strimzi** operator, KRaft `KafkaNodePool` of 3 dual-role nodes | Same topology as Compose (TR-B1, [ADR-0019](0019-kafka-and-cloudevents-binding.md)); operator-managed rolling restarts that respect ISR | Bitnami chart |
| D4 | Keycloak | **Keycloak Operator** with `KeycloakRealmImport`, placeholders for `DEFAULT_PASSWORD` and the emulator secret from a Secret | Production mode instead of `start-dev`; upstream-supported realm import from the same realm files | Plain Deployment with `--import-realm` |
| D5 | Gateway | **Gateway API** (`Gateway` + `HTTPRoute`) with **NGINX Gateway Fabric** | The Gateway API is the standard successor to Ingress (ingress-nginx is retired); keeps the nginx semantics of today's gateway and native OTel tracing | Envoy Gateway |
| D6 | TLS | **cert-manager** with a self-signed local root CA (`ClusterIssuer`), distributed to pods by **trust-manager** as a JKS bundle | Replaces the mkcert certificates and the hand-copied `truststore.jks`; apps trust the CA through a mounted truststore, not by running as root | Keep mkcert certificates as static Secrets |
| D7 | In-cluster resolution of `id.polaris.local` | **CoreDNS rewrite** of `*.polaris.local` to the Gateway Service | One issuer `https://id.polaris.local/realms/polaris` for browsers and pods (TR-K8) with no app change, as the nginx network alias does on Compose | `hostAliases` per pod: needs the Gateway IP in every manifest |
| D8 | Observability backend | **OTel Collector → Grafana Cloud** (already configured in `.env`); the local Prometheus, Loki, Tempo and Grafana come later as an optional component (plan K12) | Smallest working slice; the in-cluster LGTM stack is large and optional locally | Deploy the LGTM charts from the start |
| D9 | Image registry | **GHCR**, pushed by CI; local runs use `kind load docker-image` | No registry to run on the laptop | Local registry container |
| D10 | Kafka topics | **Provisioned by the owning service** at startup, as today; Strimzi's Topic Operator is disabled | No behaviour change; the topic contract stays with its owner ([ADR-0019](0019-kafka-and-cloudevents-binding.md) §3.2) | `KafkaTopic` CRs: moves topic ownership out of the owning context |

### 3.1 Cluster and layout

* **Local cluster:** [kind](https://kind.sigs.k8s.io/), one node created from `deploy/k8s/kind/cluster.yaml`. Host ports 80 and 443 map to fixed NodePorts 30080 and 30443, where the Gateway data plane is exposed. No pod needs `hostPort`, which `restricted` forbids. Compose's nginx binds the same host ports, so the two environments don't run side by side.
* **Layout:** `deploy/k8s/{kind,platform,base,overlays/local}`. Third-party components are installed from upstream Helm charts or operator manifests at pinned versions, configured from `deploy/k8s/platform/`.
* **Namespace:** all Polaris workloads run in the `polaris` namespace, which enforces Pod Security `restricted` (with `warn` and `audit` at the same level). Operators run in their own namespaces.
* **Pinned versions:** tool versions (kind, its node image by digest, kubectl, kubeconform, schema catalogs) live in `deploy/k8s/versions.env`. `scripts/k8s/tools.sh` installs them into `.tools/bin` and checks each download's SHA-256.
* **Entry points:** `make k8s-up` (create the cluster if missing, apply `overlays/local`), `make k8s-down` (delete it), `make k8s-smoke`, `make k8s-validate`. Up and down are idempotent.
* **CI:** the `Kubernetes` workflow renders every overlay with `kubectl kustomize`, validates it with `kubeconform` in strict mode (Kubernetes schemas plus the CRDs catalog), then runs `make k8s-up` and `scripts/k8s/smoke.sh` on a kind cluster.

---

## 4. Consequences

### Positive
* One standard toolchain (`kubectl` + Kustomize + upstream operators), the same from a laptop to a cloud cluster. A cloud environment is a new overlay.
* Operators own the stateful lifecycles (failover, rolling restarts, realm import) that Compose leaves to hand-run scripts.
* Every slice is verified in CI on a real cluster, so drift between manifests and behaviour is caught on the PR.

### Negative
* Three operators (CloudNativePG, Strimzi, Keycloak) plus cert-manager, trust-manager and NGINX Gateway Fabric to install and upgrade. Each is pinned and bumped deliberately.
* Production-mode Keycloak and a three-node Kafka make the local cluster heavier than Compose's `start-dev`.
* kind and Compose compete for host ports 80/443, so only one of them runs at a time on a laptop.

### Risks
* `polaris-assistant` uses `ddl-auto: update` on the shared database. That conflicts with forward-only migrations and becomes riskier with several replicas. Tracked as a follow-up, not decided here.

---

## 5. Status

Proposed. The plan's Definition of Done requires this ADR to be accepted once the full stack runs on the cluster.
