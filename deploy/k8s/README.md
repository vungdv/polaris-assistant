# Kubernetes deployment

Manifests and tooling for running Polaris on Kubernetes ([ADR-0021](../../docs/technical/decisions/0021-kubernetes-deployment-platform.md), [plan](../../docs/development/plan/k8s-deployment.md)). Compose stays the inner-loop environment.

| Path | Contents |
|:--|:--|
| `versions.env` | Pinned versions of kind, its node image, kubectl, kubeconform, helm, the schema catalogs and the platform charts |
| `kind/cluster.yaml` | Local kind cluster: host ports 80/443 → NodePorts 30080/30443 |
| `platform/` | Helm values and CRs for third-party components: cert-manager, trust-manager, NGINX Gateway Fabric, the cluster PKI (`pki/`), the CoreDNS rewrite (`coredns/`), the OpenTelemetry Collector (`otel-collector/`) and its log agent (`otel-agent/`), the CloudNativePG operator (`cloudnative-pg/`) |
| `base/` | Cluster-agnostic manifests: the `polaris` namespace (Pod Security `restricted`), then one directory per component (`edge/`: Gateway, certificate, HTTP redirect, tracing policy; `data/`: PostgreSQL Clusters; `redis/`) |
| `overlays/local/` | The kind environment: image tags, replica counts, Secrets from the untracked `deploy/k8s/.env.local` |
| `overlays/local/images/` | Generated and untracked: the Kustomize component that pins each app image to `ghcr.io/<owner>/<app>:<git-sha>` of the current checkout |

| Command | What it does |
|:--|:--|
| `make k8s-up` | Installs the pinned tools into `.tools/bin`, creates the kind cluster `polaris` if missing, installs the platform components (`scripts/k8s/platform.sh`), applies `overlays/local` and waits for the edge |
| `make k8s-images` | Builds the three app images as `ghcr.io/vungdv/<app>:<git-sha>` and loads them into the cluster with `kind load docker-image` (`APPS=polaris` for a subset) |
| `make k8s-smoke` | Runs `scripts/k8s/smoke.sh` against the cluster |
| `make k8s-down` | Deletes the cluster |
| `make k8s-validate` | Renders every overlay and platform kustomization and validates it with kubeconform, and renders each platform chart with its values (no cluster needed) |

Compose's nginx also binds ports 80/443: run `make down` first, or point `KIND_CONFIG` at a copy of `kind/cluster.yaml` with other host ports.

The scripts keep the cluster's credentials in the untracked `.tools/kubeconfig` (override with `K8S_KUBECONFIG`; a relative path
is taken from the repo root), not in `~/.kube/config`: `make k8s-up` and `make k8s-down` never change your kubeconfig
or its current context. `make k8s-up` and `make k8s-smoke` rewrite the file from kind whenever the cluster exists. To use your own kubectl,
helm or k9s against the cluster:

```bash
export KUBECONFIG="$PWD/.tools/kubeconfig"   # from the repo root; or pass --kubeconfig .tools/kubeconfig per command
kubectl --context kind-polaris get pods -A
```

## Application images

CI (`.github/workflows/images.yml`) builds `polaris`, `polaris-assistant` and `polaris-fulfilment-emulator` from their
Dockerfiles on every PR and pushes `ghcr.io/<owner>/<app>:<git-sha>` on a push to `main`. The images run as UID/GID
10001, so `runAsNonRoot` can verify them.

Base manifests name an app image by its bare app name (`image: polaris`). The local overlay maps that name to
`$IMAGE_REGISTRY/<app>:$IMAGE_TAG` through the generated `overlays/local/images` component. `make k8s-up`,
`make k8s-validate` and `make k8s-images` rewrite it from the current commit SHA, so no tag is ever committed.
`IMAGE_REGISTRY` (default `ghcr.io/vungdv`) and `IMAGE_TAG` can be overridden, for example to run a fork's images or
an image CI already pushed. After a new commit, run `make k8s-images` again: the pin moves to the new SHA.

## Edge: TLS, Gateway and DNS

`make k8s-up` installs these from their upstream charts and release manifests, at the versions in `versions.env`
(nothing upstream is copied into the repo), and waits for each to be ready (`K8S_WAIT_TIMEOUT`, default `300s`):

| Component | Namespace | Configured by |
|:--|:--|:--|
| cert-manager | `cert-manager` | `platform/cert-manager/values.yaml` |
| trust-manager | `cert-manager` | `platform/trust-manager/values.yaml` |
| Gateway API CRDs (standard channel, the version NGINX Gateway Fabric is built against, SHA-256 verified) | — | `GATEWAY_API_VERSION` |
| NGINX Gateway Fabric (control plane) | `nginx-gateway` | `platform/nginx-gateway-fabric/values.yaml` |

- **Certificates.** `platform/pki` creates a self-signed root CA (`Polaris Local Root CA`, Secret `cert-manager/polaris-root-ca`)
  and the `polaris-ca` ClusterIssuer that signs with it. `base/edge/certificate.yaml` issues one certificate for
  `polaris.local`, `id.polaris.local` and `grafana.polaris.local` (Secret `polaris/polaris-edge-tls`).
- **Gateway.** `base/edge/gateway.yaml` defines the Gateway `polaris` in the `polaris` namespace: one HTTPS listener per
  host (`https-polaris`, `https-id`, `https-grafana`; routes attach with `sectionName`) and an HTTP listener that only
  redirects to HTTPS. NGINX Gateway Fabric runs its data plane there as Deployment and Service `polaris-nginx`, which
  pass Pod Security `restricted`. The Service is a NodePort on 30080/30443, the ports kind maps to the host. Until a
  later slice attaches an `HTTPRoute`, every HTTPS path answers 404.
- **Trust.** The trust-manager Bundle `polaris-ca-bundle` writes a ConfigMap of the same name into every namespace
  labelled `app.kubernetes.io/part-of: polaris`: `ca.crt` (PEM) and `truststore.jks` (JKS, password `changeit`), each
  with the public roots plus the cluster root CA. It replaces `docker/nginx/truststore.jks` with the same semantics, so
  a Java app mounts it and keeps `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=<mount>/truststore.jks`.
- **DNS.** `platform/coredns/coredns.yaml` declares the CoreDNS Corefile with a rewrite of `polaris.local` and
  `*.polaris.local` to `polaris-nginx.polaris.svc.cluster.local`, so pods reach `https://id.polaris.local` through the
  Gateway, as browsers do (one issuer, TR-K8). CoreDNS restarts only when the Corefile changes.

From the host, add the hosts to `/etc/hosts` and trust the cluster root CA with
`scripts/setup-local-https-mac-m1.sh --k8s` (macOS; skips when no cluster runs). Without it:

```bash
kubectl --kubeconfig .tools/kubeconfig --context kind-polaris -n cert-manager get secret polaris-root-ca -o jsonpath='{.data.tls\.crt}' | base64 -d > /tmp/polaris-root-ca.pem
curl --cacert /tmp/polaris-root-ca.pem https://polaris.local/   # 404 until app routes exist
```

With `KIND_CONFIG` on other host ports, add for example `--resolve polaris.local:18443:127.0.0.1` and use port 18443.

## Observability: OpenTelemetry Collector

`make k8s-up` installs the upstream `opentelemetry-collector` chart (`OTEL_COLLECTOR_VERSION`, contrib image at the
chart's appVersion) twice:

| Release | Kind | Namespace | Configured by | Role |
|:--|:--|:--|:--|:--|
| `otel-collector` | Deployment + Service (OTLP gRPC `4317`, HTTP `4318`) | `polaris` | `platform/otel-collector/values.yaml` | Receives OTLP from the Gateway (and the apps from K8 on), adds Kubernetes attributes, redacts, exports |
| `otel-agent` | DaemonSet, no ports | `observability` | `platform/otel-agent/values.yaml` | Tails the Gateway pod's stdout on the node and forwards the access log to `otel-collector` over OTLP |

- **Pipeline.** Ported from `docker/telemetry/otel-collector-config.yaml`: `memory_limiter` first, redaction and the
  metric attribute allow-list before `batch`, bounded exporter queues. Added: the `k8s_attributes` processor (with a
  ClusterRole to read pods, namespaces and replicasets), so every span, metric and log carries `k8s.namespace.name`,
  `k8s.pod.name` and `k8s.deployment.name`. Gateway spans are renamed from NGINX Gateway Fabric's
  `ngf:polaris:polaris` to Compose's `nginx-gateway`. Every span and log record is printed by the `debug` exporter
  (`kubectl -n polaris logs deploy/otel-collector`). The local Tempo, Loki and Prometheus exporters arrive with K12.
- **Gateway traces.** NGINX Gateway Fabric's data-plane config (`nginx.config.telemetry` in
  `platform/nginx-gateway-fabric/values.yaml`) exports spans to `otel-collector:4317`. `base/edge/tracing-policy.yaml`
  (an `ObservabilityPolicy`) turns tracing on per HTTPRoute, samples every request and propagates W3C `traceparent`.
  A route that isn't listed there isn't traced: add each new HTTPRoute to its `targetRefs`.
- **Gateway access log.** nginx writes Compose's JSON access log (with `trace_id` and `span_id`) to stdout
  (`nginx.config.logging.accessLog`). The agent reads it from `/var/log/pods` through a read-only hostPath, which Pod
  Security `restricted` forbids. That's why it runs in its own `observability` namespace (Pod Security `privileged`),
  not in `polaris`.
- **Grafana Cloud (D8).** Optional. Put the `GRAFANA_CLOUD_*` keys of `.env.template` in the untracked
  `deploy/k8s/.env.local` (or point `K8S_ENV_FILE` at another env file) and re-run `make k8s-up`. `platform.sh` then
  writes the three keys to the Secret `polaris/grafana-cloud` and layers `platform/otel-collector/grafana-cloud.values.yaml`
  (Compose's `otlp_http/grafana_cloud` exporter and `basicauth/grafana_cloud` extension) on the Collector. Without
  them, as in CI, the Secret is removed and the Collector exports to its debug exporter only. Values are read as
  `KEY=value` lines, without quotes.

## Data stores: PostgreSQL and Redis

`make k8s-up` installs the CloudNativePG operator from its upstream chart (`CNPG_VERSION`, release `cloudnative-pg` in
namespace `cnpg-system`, values in `platform/cloudnative-pg/values.yaml`), applies the Clusters and Redis with the
overlay, and waits until every Cluster is `Ready` and Redis is available.

| Compose service | Kubernetes | Database / owner | Credentials (operator-generated) | Primary endpoint |
|:--|:--|:--|:--|:--|
| `polaris-db` | CNPG `Cluster` `polaris-db` (`base/data/polaris-db.yaml`) | `polaris` / `polaris` | Secret `polaris-db-app` | Service `polaris-db-rw:5432` |
| `postgres` (keycloak-postgres) | CNPG `Cluster` `keycloak-db` (`base/data/keycloak-db.yaml`) | `keycloak` / `keycloak` | Secret `keycloak-db-app` | Service `keycloak-db-rw:5432` |
| `redis` | Deployment and Service `redis` (`base/redis/`) | — | none (as in Compose) | Service `redis:6379` |

- **Parity with Compose.** PostgreSQL 16.15 (the release `postgres:16` resolves to) from CNPG's `standard` image on the
  same Debian base, pinned by digest, with initdb in Compose's `en_US.utf8` locale (CNPG defaults to `C`). Each Compose
  database has one owner role and nothing else (no extra roles, databases or extensions: V9's `pg_trgm` index is a
  manual DBA note), so `bootstrap.initdb` (database + owner) mirrors it and no `managed.roles` are needed. `polaris` and
  `polaris-assistant` share `polaris-db` and its owner, as in Compose. Redis is `redis:7.4.11-alpine` (what
  `redis:7-alpine` resolves to) pinned by digest, with persistence off (`--save "" --appendonly no`).
- **Credentials.** CNPG generates each owner's password into the `<cluster>-app` Secret (keys `username`, `password`,
  `host`, `port`, `dbname`, `uri`, `jdbc-uri`). Nothing is committed (TR-K3). The apps read them from K7 (Keycloak) and
  K8/K9 (`polaris`, `polaris-assistant`). Network superuser access stays off. For an admin shell:
  `kubectl -n polaris exec -it polaris-db-1 -c postgres -- psql -d polaris`.
- **Instances and storage.** `base` asks for three instances per Cluster (primary plus two replicas, CNPG failover).
  `overlays/local` runs one each on kind's default StorageClass (`standard`, node-local) and turns off CNPG's
  PodDisruptionBudget, which would block draining the node with a single instance. Data lives on the PVC, so it
  survives pod restarts. `make k8s-down` deletes it with the cluster.
- **Pod Security.** The operator's chart defaults, CNPG's instance and initdb pods, and the Redis pod (UID 999,
  read-only root filesystem with an `emptyDir` at `/data`, no capabilities, seccomp `RuntimeDefault`) all pass
  `restricted`, which the `polaris` namespace enforces.
- **Smoke.** `make k8s-smoke` checks both Clusters and their Secrets, connects to each `-rw` Service with the owner's
  credentials from a `restricted` probe pod (owner, database, collation, PostgreSQL 16), writes a row to `polaris-db`
  (in a throwaway schema, so Flyway's `public` stays empty), deletes the primary pod, waits for CNPG to bring it back
  (`PG_SMOKE_TIMEOUT`, default 180s) and reads the row again. It also runs `redis-cli ping` through the `redis` Service
  and checks persistence is off.
- **Validation.** kubeconform validates the Clusters against `postgresql.cnpg.io/cluster_v1.json` in the pinned CRDs
  catalog. That schema is CNPG 1.29's, identical to the Cluster CRD of the pinned chart, which is why `CNPG_VERSION` is
  held on the 1.29 line: bump it together with `CRDS_CATALOG_REF` once the catalog carries a newer schema.
