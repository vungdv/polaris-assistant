# Kubernetes deployment

Manifests and tooling for running Polaris on Kubernetes ([ADR-0021](../../docs/technical/decisions/0021-kubernetes-deployment-platform.md), [plan](../../docs/development/plan/k8s-deployment.md)). Compose stays the inner-loop environment.

| Path | Contents |
|:--|:--|
| `versions.env` | Pinned versions of kind, its node image, kubectl, kubeconform, helm, the schema catalogs and the platform charts |
| `kind/cluster.yaml` | Local kind cluster: host ports 80/443 → NodePorts 30080/30443; a control plane that runs every workload and a tainted worker for the stateless apps (K11) |
| `platform/` | Helm values and CRs for third-party components: cert-manager, trust-manager, NGINX Gateway Fabric, the cluster PKI (`pki/`), the CoreDNS rewrite (`coredns/`), the OpenTelemetry Collector (`otel-collector/`) and its log agent (`otel-agent/`), the CloudNativePG operator (`cloudnative-pg/`), the Strimzi operator (`strimzi/`), the Keycloak Operator (`keycloak-operator/`: patches over its upstream release manifests), metrics-server (`metrics-server/`), the CNPG Barman Cloud plugin (`barman-cloud/`) and the backups' object store (`object-store/`) |
| `base/` | Cluster-agnostic manifests: the `polaris` namespace (Pod Security `restricted`), then one directory per component (`edge/`: Gateway, certificate, HTTP redirect, tracing policy; `data/`: PostgreSQL Clusters; `redis/`; `kafka/`: the Kafka cluster; `keycloak/`: the Keycloak server and its HTTPRoute; `polaris/`: the Order & Catalog service and its HTTPRoutes; `polaris-assistant/`: the assistant and its HTTPRoutes; `polaris-fulfilment-emulator/`: the fulfilment emulator, no route; `swagger-ui/`; `network-policies/`: default-deny and the §Topology allow list) |
| `overlays/local/` | The kind environment: image tags, replica counts, Secrets from the untracked `deploy/k8s/.env.local` |
| `overlays/local/realms/` | Generated and untracked: the Kustomize component that imports `docker/keycloak/*.json` (KeycloakRealmImport `polaris-realm`, ConfigMap `keycloak-master-realm`) |
| `overlays/local/images/` | Generated and untracked: the Kustomize component that pins each app image to `ghcr.io/<owner>/<app>:<git-sha>` of the current checkout |

| Command | What it does |
|:--|:--|
| `make k8s-up` | Installs the pinned tools into `.tools/bin`, creates the kind cluster `polaris` if missing, installs the platform components (`scripts/k8s/platform.sh`), applies `overlays/local` and waits for the edge, the data stores, Kafka, Keycloak and the apps whose images are loaded |
| `make k8s-images` | Builds the three app images as `ghcr.io/vungdv/<app>:<git-sha>` and loads them into the cluster with `kind load docker-image` (`APPS=polaris` for a subset) |
| `make k8s-smoke` | Runs `scripts/k8s/smoke.sh` against the cluster |
| `make k8s-e2e-fulfilment` | Runs the fulfilment e2e (`tests/e2e/run-fulfilment.sh`, as `make e2e-fulfilment` on Compose) through the cluster's Gateway, and checks the order events inside a Kafka node |
| `make k8s-down` | Deletes the cluster |
| `make k8s-kafka-topics`, `-tail`, `-cluster`, `-offsets`, `-groups`, `-leaders` | The Kafka admin commands of `make kafka-*`, run in a Kafka node with `kubectl exec` (`scripts/k8s/kafka.sh`; `TOPIC=`, `NODE=1..3`) |
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
  `redis:7-alpine` resolves to) pinned by digest, with persistence off (`--save "" --appendonly no`) and memory bounded
  below the 256Mi limit (`--maxmemory 200mb --maxmemory-policy volatile-lru`: only TTL'd product-cache keys are
  evicted, never the assistant's intent taxonomy).
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
  and checks persistence is off and the memory bound and eviction policy are set.
- **Validation.** kubeconform validates the Clusters against `postgresql.cnpg.io/cluster_v1.json` in the pinned CRDs
  catalog. That schema is CNPG 1.29's, identical to the Cluster CRD of the pinned chart, which is why `CNPG_VERSION` is
  held on the 1.29 line: bump it together with `CRDS_CATALOG_REF` once the catalog carries a newer schema.

## Kafka

`make k8s-up` installs the Strimzi cluster operator from its upstream chart (`STRIMZI_VERSION`, release `strimzi` in
namespace `strimzi`, values in `platform/strimzi/values.yaml`, watching `polaris`), applies the Kafka cluster with the
overlay and waits until it is `Ready`.

| Compose | Kubernetes |
|:--|:--|
| `kafka-1`..`kafka-3` (`apache/kafka:4.1.1`, node ids 1-3, broker + controller) | `Kafka` `kafka` and `KafkaNodePool` `dual-role` (`base/kafka/kafka.yaml`): pods `kafka-dual-role-1`..`3`, node ids 1-3, broker + controller, Kafka 4.1.1 |
| `INTERNAL` listener `kafka-N:9092`, plaintext, no authentication | Listener `plain` on 9092, plaintext, no authentication: bootstrap Service `kafka-kafka-bootstrap:9092`, nodes `kafka-dual-role-N.kafka-kafka-brokers:9092` |
| `HOST` listener `localhost:9094-9096` (for `make run`) | None. Use `make k8s-kafka-*`, or `kubectl port-forward` for a single node |
| Fixed `CLUSTER_ID` | Generated by Strimzi once and kept in the `Kafka` status |
| Named volumes `kafka_N_data` | PersistentVolumeClaims `data-0-kafka-dual-role-N` (default StorageClass), which also hold the KRaft metadata log |

- **Configuration.** As in Compose: `default.replication.factor=3`, `min.insync.replicas=2`,
  `auto.create.topics.enable=false`, offsets and transaction logs with RF 3 (min ISR 2), and
  `group.initial.rebalance.delay.ms=0`. Losing any one node loses no `acks=all` write and keeps the controller quorum.
- **Topics (D10).** Each service still creates its own topics at startup. The Entity Operator (Topic and User
  Operators) isn't deployed: there are no `KafkaTopic` or `KafkaUser` resources, and no authentication to manage.
- **Version.** Strimzi 0.49 runs Kafka 4.0 and 4.1 only, so the cluster runs Kafka 4.1.1, the same version as Compose.
  Strimzi is held at 0.49.1 because the pinned CRDs catalog carries exactly its `Kafka` and `KafkaNodePool` schemas
  (see `versions.env`).
- **Resources.** Each node has a fixed 384m heap and requests 768Mi (limit 1280Mi), sized for kind. The admin tools
  run in a node's container with a 128m heap.
- **Pod Security.** The operator runs with Strimzi's `restricted` pod security provider, so the Kafka pods it creates
  in `polaris` pass `restricted`. The operator's own pod meets it too. Strimzi's per-cluster NetworkPolicies are off;
  K11 adds one allow list for the namespace.
- **Smoke.** `make k8s-smoke` checks the operator, the cluster, the node pool and the broker configuration. It creates
  a topic with RF 3 and min ISR 2 and writes 50 records with `acks=all`. Then it deletes the node that leads
  partition 0, checks that every partition is under-replicated, and writes 50 more records with `acks=all` while the
  node is down. It waits for the node to come back ready and in sync (`KAFKA_SMOKE_TIMEOUT`, default 240s), reads all
  100 records back exactly once, and deletes the topic.
- **Validation.** kubeconform validates the `Kafka` and `KafkaNodePool` (API `kafka.strimzi.io/v1`) against the
  pinned CRDs catalog, whose schemas are those of Strimzi 0.49.1. Bump `STRIMZI_VERSION` together with
  `CRDS_CATALOG_REF` once the catalog carries a newer Strimzi.

## Identity: Keycloak

`make k8s-up` installs the Keycloak Operator (`KEYCLOAK_VERSION`) from its upstream release manifests: the
`Keycloak` and `KeycloakRealmImport` CRDs and `kubernetes.yml`, downloaded and verified against the SHA-256s in
`versions.env`. `platform/keycloak-operator` holds only the kustomization that patches them. The upstream install is
single-namespace, because the operator watches the namespace it runs in. So the operator runs in `polaris`, next to
the `keycloak-db` Secret and the Gateway, with a `restricted` security context added. `make k8s-up` then applies the
`Keycloak` and the realm imports with the overlay, and waits until the server is ready, the import is done and the
operator's post-import restart has finished.

| Compose (`keycloak`) | Kubernetes |
|:--|:--|
| `quay.io/keycloak/keycloak:26.2`, `start-dev` | `Keycloak` `keycloak` (`base/keycloak/keycloak.yaml`): 26.2.5 pinned by digest, production mode (`start`), StatefulSet `keycloak`, Service `keycloak-service` (8080 HTTP, 9000 management) |
| `postgres` service, `keycloak`/`keycloak` | CNPG `keycloak-db`, credentials from the operator-generated Secret `keycloak-db-app` |
| `KC_HOSTNAME`, `KC_PROXY_HEADERS=xforwarded` behind nginx | `hostname: https://id.polaris.local`, `proxy.headers: xforwarded`, HTTP on 8080 behind the Gateway, which terminates TLS |
| `KC_TRACING_*` to `otel-collector:4317` | `tracing` to `otel-collector.polaris:4317`, sampler `traceidratio` 1.0, service name `keycloak` |
| `--import-realm` of `docker/keycloak/*.json` | `polaris-realm.json` as a `KeycloakRealmImport`; `master-realm.json` imported by the server at its first start (see below) |
| `KEYCLOAK_ADMIN=admin` / `admin` | The operator's bootstrap admin, generated into the Secret `keycloak-initial-admin` (`username`, `password`) |
| nginx `id.polaris.local` server | `HTTPRoute` `keycloak` (`base/keycloak/httproute.yaml`) on listener `https-id`, traced by `edge-tracing` |

- **Realms.** `docker/keycloak/` stays the single source for Compose and Kubernetes. A `KeycloakRealmImport` takes
  the realm inline, so `make k8s-up` and `make k8s-validate` generate the untracked component `overlays/local/realms`
  from the JSON files (`write_realm_imports` in `scripts/k8s/lib.sh`, with `jq`). The operator imports
  `polaris-realm.json` with a Job, once. The master realm can't be imported that way: the Job runs only when the server
  is ready, and by then the server has created its built-in master realm. The Job never overrides an existing realm,
  so it would skip `master-realm.json`. So the server imports `master-realm.json` itself at its first start, as Compose
  does: the file comes from the ConfigMap `keycloak-master-realm` in `/opt/keycloak/data/import`, with
  `start --import-realm`. That brings in Grafana's SSO client, its roles and `grafana-admin`. As on Compose, imports
  only happen in an empty database. Changing a realm file later needs a fresh database (`make k8s-down`), or a change
  made in the admin console.
- **Placeholders.** `${DEFAULT_PASSWORD}` and `${POLARIS_FULFILMENT_EMULATOR_SECRET}` stay in the files. Both
  importers resolve them from the Secret `keycloak-realm-placeholders`: `spec.placeholders` for the import Job, and `envFrom`
  on the server container for its master import. `up.sh` writes the Secret from the untracked env file (same keys as
  `.env.template`). A key that isn't set (as in CI) keeps the value the Secret already has, or is generated at random
  once. Read it with
  `kubectl -n polaris get secret keycloak-realm-placeholders -o jsonpath='{.data.DEFAULT_PASSWORD}' | base64 -d`.
- **Admin.** The operator's bootstrap admin (`keycloak-initial-admin`) replaces Compose's `admin`/`admin`. Pass it
  as `KC_ADMIN_USER` / `KC_ADMIN_PASSWORD` to tooling such as `make seed-shoppers`.
- **Pod Security.** The operator's pod and the Keycloak pods (UID 1000, no capabilities, seccomp `RuntimeDefault`)
  pass `restricted`. So does the import Job, which copies the server's pod template. Keycloak's root filesystem stays
  writable because an unoptimized start rebuilds `/opt/keycloak/lib`. The operator's Ingress and NetworkPolicy are off:
  routing is the HTTPRoute, and K11 brings the namespace's allow list.
- **Smoke.** `make k8s-smoke` checks the operator, the `Keycloak` and the import. From a `restricted` pod it fetches
  the polaris realm's OIDC discovery through the Gateway (issuer `https://id.polaris.local/realms/polaris`). It also
  checks these grants:
  - a password grant for `testuser` (client `polaris-app`)
  - a client-credentials grant for `polaris-fulfilment-emulator`
  - a master-realm grant for `grafana-admin`
  - an admin-API read of master's `grafana` client with the bootstrap admin

  It then checks health and metrics on port 9000, and repeats the discovery check from the host through kind's port
  mapping (`curl --resolve`, cluster root CA). Last, it waits for Keycloak spans in the Collector.
- **Validation.** kubeconform validates the `Keycloak` and `KeycloakRealmImport` against the pinned CRDs catalog.
  Its `v2alpha1` schemas come from a newer Keycloak, and their spec is a strict superset of 26.2.5's. The API server
  validates the CRs strictly against the installed 26.2.5 CRDs on every apply (see `versions.env`).

## Order & Catalog (`polaris`) and Swagger UI

`base/polaris/` runs Compose's `polaris` service, and `base/swagger-ui/` its `swagger-ui`, behind the Gateway on
`https://polaris.local`.

| Compose | Kubernetes |
|:--|:--|
| `polaris` container, `environment:` | Deployment `polaris` (2 replicas), ConfigMap `polaris` (`envFrom`), Service `polaris:8080` |
| `SPRING_DATASOURCE_USERNAME`/`PASSWORD` `polaris`/`polaris` | `secretKeyRef` to the CNPG Secret `polaris-db-app` (operator-generated, TR-K3); URL `jdbc:postgresql://polaris-db-rw:5432/polaris` |
| `SPRING_JPA_SHOW-SQL: true`, `LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND: DEBUG` | Dropped (dev-only). `SPRING_JPA_SHOW_SQL=false`, because `application.yml` defaults it to true |
| `kafka-1..3:9092`, `redis`, `otel-collector:4318` | `kafka-kafka-bootstrap:9092`, `redis:6379`, `otel-collector:4318` |
| `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/certs/truststore.jks` (hand-copied JKS) | The same property on `/etc/polaris-trust/truststore.jks`, the trust-manager bundle `polaris-ca-bundle` mounted read-only |
| `user: root` | UID/GID 10001, read-only root filesystem, `emptyDir` on `/tmp`, no capabilities, seccomp `RuntimeDefault` |
| nginx `location /` and `location /mcp/` (`proxy_buffering off`) | HTTPRoutes `polaris` (`/`) and `polaris-mcp` (`/mcp/`), plus the NGF `ProxySettingsPolicy` `polaris-mcp-sse` (buffering off, 1h read and send timeouts) |
| `swagger-ui` (root), nginx `location /swagger-ui` and `= /swagger-ui.html` | Deployment, Service and ConfigMap `swagger-ui` (same image and `URLS`/`BASE_URL`/`OAUTH_*`), as UID 101 with a read-only root filesystem (an init container copies what the entrypoint rewrites into `emptyDir`s); HTTPRoute `swagger-ui` with `/swagger-ui` and a 301 for `/swagger-ui.html` |

- **Image.** The Deployment uses `image: polaris`, pinned by the generated `images` component. On a fresh cluster
  the image isn't on the node yet, so the pods wait in an image-pull back-off and `make k8s-up` only logs a hint. Run
  `make k8s-images APPS=polaris` (it builds the image, loads it with `kind load`, and replaces the waiting pods so
  both replicas start together), then `make k8s-up` again, which waits for the rollout. CI does the same.
- **Lifecycle (TR-K4).** A `startupProbe` on `/actuator/health/liveness` allows up to 5 minutes for Flyway and the
  context. Then liveness and readiness (`readinessState` + `db`) take over. On termination a 10s `preStop` sleep lets the Gateway
  drop the endpoint before SIGTERM starts the graceful shutdown (20s phase timeout, K2).
  `terminationGracePeriodSeconds` is 40 (10 + 20 + 10s margin). Requests 250m CPU and 384Mi, limits 2 CPUs and 1Gi; the
  heap is 70% of the limit.
- **Multiple replicas (TR-K5).** Rolling updates keep both replicas (`maxUnavailable: 0`, `maxSurge: 1`), and the
  PodDisruptionBudget `polaris` (`minAvailable: 1`) lets a drain evict one at a time. Flyway's advisory lock and the
  outbox relay's per-key lease keep migrations and event hand-off correct with both running.
- **Telemetry (TR-K9).** OTLP traces, metrics and logs go to the Collector, whose `k8s_attributes` processor adds
  `k8s.namespace.name`, `k8s.pod.name` and `k8s.deployment.name`. The pod's UID is set as `OTEL_RESOURCE_ATTRIBUTES`
  (downward API), so the Collector matches the pod by UID. All three routes are in `base/edge/tracing-policy.yaml`,
  so the Gateway's span is the parent of polaris's server span.
- **Smoke.** `make k8s-smoke` checks the following:
  - 2/2 replicas are ready, the PDB is in place, and the routes and the SSE policy are accepted.
  - Flyway history: each of the repo's migrations is applied once and successfully.
  - The E3 two-instance check on the cluster. It inserts 60 outbox events for 6 keys in one transaction, with a fresh
    test topic as their destination. Both replicas' relays then compete to deliver them, and the check expects all
    60 marked delivered, each on Kafka exactly once, and each key's events in commit order.
  - From a `restricted` pod, through the Gateway:
    - a token for `testuser`, then a catalogue call whose `X-Trace-Id` matches its `traceparent`,
    - 401 without a token on `/api` and `/mcp/`,
    - `/v3/api-docs`, Swagger UI with Compose's spec URLs, and the `/swagger-ui.html` redirect.
  - The Collector's debug exporter holds the Gateway span and a polaris child span of that trace, with polaris's
    Kubernetes attributes.
  - `tests/perf/api-test.js` passes, unchanged, from a k6 pod (`SMOKE_K6_IMAGE`, `restricted`, as `testuser` with
    `DEFAULT_PASSWORD`), with its thresholds.
  - A second, open-ended k6 run continues while `kubectl rollout restart deployment/polaris` replaces both replicas.
    k6 records every request to CSV, and no request may get a 5xx or lose its connection.

## Assistant (`polaris-assistant`)

`base/polaris-assistant/` runs Compose's `polaris-assistant` service behind the Gateway on `https://polaris.local`.

| Compose | Kubernetes |
|:--|:--|
| `polaris-assistant` container, `environment:` | Deployment `polaris-assistant` (2 replicas), ConfigMap `polaris-assistant` (`envFrom`), Service `polaris-assistant:8081` |
| `SPRING_DATASOURCE_USERNAME`/`PASSWORD` `polaris`/`polaris` | `secretKeyRef` to the CNPG Secret `polaris-db-app` (the assistant shares the polaris database, as on Compose) |
| `GEMINI_API_KEY`, `TYPESAFE_API_KEY`, `AGENTO11Y_*` from `.env` | Secret `polaris-assistant` (`envFrom`), written by `make k8s-up` from the same keys in the untracked `deploy/k8s/.env.local` |
| `POLARIS_MCP_CORE_URL`, `POLARIS_CORE_API_BASE_URL` on `http://polaris:8080` | The same, on the `polaris` Service |
| `depends_on: polaris: service_healthy` | Init container `wait-for-polaris`: waits until the `polaris` Service answers its readiness group, so Flyway has migrated before the assistant's Hibernate `ddl-auto: update` creates its tables |
| healthcheck on liveness + readiness (`db`, `polarisMcp`) | `startupProbe`/`livenessProbe` on `/actuator/health/liveness`, `readinessProbe` on `/actuator/health/readiness`: still `readinessState` + `db` + `polarisMcp` only, so a Gemini or TypeSafe outage never takes a pod out of the Service |
| `user: root` | UID/GID 10001, read-only root filesystem, `emptyDir` on `/tmp`, no capabilities, seccomp `RuntimeDefault` |
| nginx `location /api/v1/assistant` (`proxy_buffering off`) and `location /v3/api-docs/assistant` | HTTPRoutes `polaris-assistant` (`/api/v1/assistant`, NGF `ProxySettingsPolicy` `polaris-assistant-sse`: buffering off, 5m read and send timeouts) and `polaris-assistant-api-docs` (`/v3/api-docs/assistant`, rewritten to `/v3/api-docs`) |

- **Secret.** `make k8s-up` writes Secret `polaris-assistant` from the non-empty `GEMINI_API_KEY`, `TYPESAFE_API_KEY`
  and `AGENTO11Y_*` keys of `deploy/k8s/.env.local` (`K8S_ENV_FILE`) on every run. A key left empty is absent, so
  `application.yml`'s default applies (`AGENTO11Y_PROTOCOL=none`; without `GEMINI_API_KEY` the pods stay ready but chat
  answers 503). When the Secret changes, `make k8s-up` restarts the Deployment. CI writes the env file from the
  repository secrets `GEMINI_API_KEY` and `TYPESAFE_API_KEY`.
- **Image.** As for polaris: `make k8s-images APPS=polaris-assistant`, then `make k8s-up` again.
- **Lifecycle (TR-K4).** The 30s graceful-shutdown phase covers a whole chat turn (25s turn deadline). With the 10s
  `preStop` sleep, `terminationGracePeriodSeconds` is 50 (10 + 30 + 10s margin), so a rolling restart doesn't cut an
  in-flight turn. Requests 250m CPU and 384Mi, limits 2 CPUs and 1Gi.
- **Multiple replicas (TR-K5).** Sessions, messages and order drafts are in PostgreSQL (JPA session store), the intent
  taxonomy in Redis, so any replica serves any turn. `maxUnavailable: 0` and PodDisruptionBudget `polaris-assistant`
  (`minAvailable: 1`), as for polaris. Hibernate `ddl-auto: update` on the shared, Flyway-owned database stays a known
  risk (a follow-up in the plan).
- **Telemetry (TR-K9).** As for polaris. Both routes are in `base/edge/tracing-policy.yaml`, and the assistant forwards
  `traceparent` on its MCP and REST calls, so one trace spans the Gateway, the assistant and polaris.
- **Smoke.** `make k8s-smoke` checks the following:
  - 2/2 replicas are ready, the PDB is in place, the routes and the SSE policy are accepted, and the Secret has both
    model keys.
  - `make seed-shoppers` (as the operator's bootstrap admin from Secret `keycloak-initial-admin`) and
    `make chat-scenarios` pass from the host through the Gateway, with the realm users' `DEFAULT_PASSWORD`.
  - `tests/e2e/k6/chat-scenarios.js` passes again from a k6 pod with `ASSISTANT_BASES` set to the two pods' addresses,
    so consecutive requests of one session alternate between replicas: the order draft staged on one is confirmed on
    the other. The intent taxonomy is in Redis.
  - With a token, the readiness group lists `readinessState`, `db` and `polarisMcp` only, and `/actuator/health` also
    lists `gemini` and `typeSafe`. `/v3/api-docs/assistant` through the Gateway is the assistant's OpenAPI document.
  - A chat turn through the Gateway with a fresh `traceparent`: the Collector holds the Gateway's span, an assistant
    child span with the assistant's Kubernetes attributes, and a polaris MCP span that is the child of an assistant span.
  - A chat turn sent straight to a replica that is already terminating completes with 200, and the Deployment is back
    to 2 ready replicas.

## Fulfilment (`polaris-fulfilment-emulator`)

`base/polaris-fulfilment-emulator/` runs Compose's `polaris-fulfilment-emulator` service. It has no HTTPRoute: its
only API is actuator, reachable in-cluster on Service `polaris-fulfilment-emulator:8082`.

| Compose | Kubernetes |
|:--|:--|
| `polaris-fulfilment-emulator` container, `environment:` | Deployment `polaris-fulfilment-emulator` (1 replica, as on Compose: one consumer group `fulfilment.<partner>` per partner), ConfigMap `polaris-fulfilment-emulator` (`envFrom`), Service `polaris-fulfilment-emulator:8082` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` `kafka-1:9092,...` | The Strimzi bootstrap Service `kafka-kafka-bootstrap:9092` |
| `POLARIS_FULFILMENT_TOKEN_URI` on `https://id.polaris.local` | The same public issuer, which CoreDNS rewrites to the Gateway (TR-K8) |
| `POLARIS_FULFILMENT_EMULATOR_SECRET` (default `fulfilment-emulator-dev-secret`) | `secretKeyRef` to Secret `keycloak-realm-placeholders`, the value the realm import put into the emulator's Keycloak client (K7). No default: `make k8s-up` takes it from `deploy/k8s/.env.local` or generates it at random |
| `depends_on: polaris: service_healthy` | Init container `wait-for-polaris`: polaris owns the order lifecycle topic the emulator consumes (D10) and serves its claims |
| healthcheck on liveness + readiness | `startupProbe`/`livenessProbe` on `/actuator/health/liveness`, `readinessProbe` on `/actuator/health/readiness` |
| `user: root` | UID/GID 10001, read-only root filesystem, `emptyDir` on `/tmp`, no capabilities, seccomp `RuntimeDefault` |

- **Image.** As for polaris: `make k8s-images APPS=polaris-fulfilment-emulator`, then `make k8s-up` again.
- **Lifecycle (TR-K4).** The 20s graceful-shutdown phase also stops the Kafka listeners. With the 5s `preStop` sleep,
  `terminationGracePeriodSeconds` is 30 (5 + 20 + 5s margin). Requests 100m CPU and 256Mi, limits 1 CPU and 512Mi.
- **Telemetry (TR-K9).** OTLP to the Collector, with the pod's Kubernetes attributes. Each order event carries the
  trace context of the request that placed the order, so the emulator's consumer span continues that trace across
  Kafka, and its claim call carries it back to polaris.
- **Smoke.** `make k8s-smoke` checks the following:
  - 1/1 replica is ready, no HTTPRoute routes to the emulator, its secret comes from `keycloak-realm-placeholders` and
    isn't Compose's default, it bootstraps from the Strimzi Service, and liveness and readiness answer in-cluster.
  - The three partner consumer groups are `Stable`.
  - `make k8s-e2e-fulfilment` passes from the host through the Gateway: an order and a batch of 10 placed as
    `alice.tran` all reach `DELIVERED` with an assigned partner, more than one partner wins, and each order's five
    `order.*.v1` events are on `polaris.order.lifecycle` in order.
  - An order placed through the Gateway with a fresh `traceparent`: the Collector holds an emulator span that is the
    child of a polaris span of that trace (across Kafka), and a polaris span that is the child of an emulator span (the
    claim).

## Hardening: NetworkPolicies, autoscaling, disruption, backups

**NetworkPolicies (TR-K10).** `base/network-policies/` denies all ingress and egress in the `polaris` namespace
(`default-deny`), allows DNS to CoreDNS for every pod, and then allows exactly the flows of the plan's §Topology, each
on both ends (the caller's egress, the callee's ingress, on the callee's container port), one file per component:

| Policy | Ingress from | Egress to |
|:--|:--|:--|
| `gateway` (NGF data plane) | anywhere, 80/443 (north–south entry, NodePort and in-cluster `*.polaris.local`) | polaris and Swagger UI 8080, the assistant 8081, Keycloak 8080, the Collector 4317, the NGF control plane (`nginx-gateway`) 8443 |
| `polaris` | the Gateway, the assistant, the emulator: 8080 | polaris-db 5432, Redis 6379, Kafka 9092, the Collector 4318, the Gateway 443 (JWKS) |
| `polaris-assistant` | the Gateway: 8081 | polaris 8080, polaris-db 5432, Redis 6379, the Collector 4318, the Gateway 443, public HTTPS (Gemini, TypeSafe) |
| `polaris-fulfilment-emulator` | nothing | Kafka 9092, polaris 8080, the Gateway 443 (token), the Collector 4318 |
| `swagger-ui` | the Gateway: 8080 | nothing |
| `keycloak`, `keycloak-operator`, `keycloak-realm-import` | the Gateway: 8080 (server only) | keycloak-db 5432 and the Collector 4317 (server); the API server (operator); keycloak-db 5432 (import Job) |
| `cnpg-pods`, `polaris-db`, `keycloak-db` | the apps (polaris-db: polaris, assistant; keycloak-db: Keycloak, import Job) 5432; own instances 5432/8000; the CNPG operator 8000 | own instances; the API server; the object store's S3 port 8333 |
| `redis` | polaris, the assistant: 6379 | nothing |
| `kafka` | polaris, the emulator: 9092; the nodes: 9090-9092; the Strimzi operator: 9090, 9091, 8443 | the nodes: 9090-9092 |
| `otel-collector` | the apps 4318; Keycloak, the Gateway, the log agent (`observability`) 4317 | the API server; public HTTPS (Grafana Cloud) |

The API server and the external APIs have no pods to select: the API server is TCP 6443 on any address (a cloud overlay
narrows it to the control plane's CIDR), an external API is TCP 443 on any public address (NetworkPolicy has no host
names). kind enforces NetworkPolicies with kindnet; kubelet probes come from the node and aren't policed.

**Pod Security.** Every pod of `polaris` passes `restricted`, which the namespace has enforced since K1; the object
store's namespace enforces it too. The smoke test re-evaluates the running pods with a server-side dry run.

**Autoscaling (TR-K5).** `make k8s-up` installs metrics-server (`platform/metrics-server`, `--kubelet-insecure-tls`
for kind's self-signed kubelet certificates). `polaris` and `polaris-assistant` each have an HPA (`hpa.yaml`): 2 to 4
replicas at 80% of the CPU request, a scale-up only after 3 minutes of sustained load (a JVM's start-up burst doesn't
count), one replica a minute.

**Disruption.** The kind cluster has two nodes (`kind/cluster.yaml`): the control plane runs every workload, and a
worker, tainted `polaris.local/pool=stateless-apps:NoSchedule`, runs only polaris and the assistant
(`overlays/local` adds the toleration). Their `topologySpreadConstraints` put one replica on each node, and ignore a
cordoned node, so `kubectl drain` of the worker evicts one replica of each within the PodDisruptionBudgets and they
reschedule onto the control plane. The stateful pods stay on the control plane: their volumes are node-local. A cluster
created before K11 has one node; recreate it (`make k8s-down k8s-up`) for the drain check.

**Backups (D2).** Both Clusters list the CloudNativePG Barman Cloud plugin (`platform/barman-cloud`, next to the CNPG
operator) as their WAL archiver, and `base/data/backup.yaml` declares the shared ObjectStore `cnpg-backups` (7-day
retention) and a nightly ScheduledBackup per Cluster, which also takes one backup as soon as it is created. The bucket
is on an in-cluster S3-compatible store, SeaweedFS all-in-one (`platform/object-store`, namespace `object-store`, on a
PersistentVolumeClaim). `make k8s-up` generates its S3 credentials once and keeps them: Secret
`object-store/object-store-s3-config` (the identity, limited to the bucket) and `polaris/object-store-credentials` (what
the ObjectStore signs with). To restore, create a Cluster with `bootstrap.recovery.source` naming an external cluster
whose `plugin` is `barman-cloud.cloudnative-pg.io` with `barmanObjectName: cnpg-backups` and `serverName` the source
Cluster (the smoke test's `polaris-db-restore` is an example).

**Smoke.** The probe pods of `make k8s-smoke` carry `polaris.local/smoke-probe=true`, and for the run only a fixture
policy lets them reach any pod of the namespace (removed on exit). On top of that, the K11 checks:
- A probe without that label resolves `polaris-db-rw` and `kafka-kafka-bootstrap` but can't connect; with it, it can.
- A dry run of the namespace's `restricted` label reports no violating pod (in `polaris` and `object-store`), while the
  same dry run flags the privileged log agent in `observability`.
- Both HPAs target their Deployment, 2-4 replicas on CPU, and read their pods' CPU from metrics-server.
- Both Clusters report `ContinuousArchiving` and have a completed scheduled backup. A row written to polaris-db is in
  an on-demand backup, and a new Cluster recovered from the object store contains it.
- `api-test.js` runs while the worker is drained: its polaris and assistant replicas move to the control plane, and
  no request gets a 5xx or loses its connection.
