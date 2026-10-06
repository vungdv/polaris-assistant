# Kubernetes deployment

Manifests and tooling for running Polaris on Kubernetes ([ADR-0021](../../docs/technical/decisions/0021-kubernetes-deployment-platform.md), [plan](../../docs/development/plan/k8s-deployment.md)). Compose stays the inner-loop environment.

| Path | Contents |
|:--|:--|
| `versions.env` | Pinned versions of kind, its node image, kubectl, kubeconform and the schema catalogs |
| `kind/cluster.yaml` | Local kind cluster: host ports 80/443 → NodePorts 30080/30443 |
| `platform/` | Pinned Helm values and operator CRs for third-party components (added by later slices) |
| `base/` | Cluster-agnostic manifests: the `polaris` namespace (Pod Security `restricted`), then one directory per component |
| `overlays/local/` | The kind environment: image tags, replica counts, Secrets from the untracked `deploy/k8s/.env.local` |

| Command | What it does |
|:--|:--|
| `make k8s-up` | Installs the pinned tools into `.tools/bin`, creates the kind cluster `polaris` if missing, applies `overlays/local` |
| `make k8s-smoke` | Runs `scripts/k8s/smoke.sh` against the cluster |
| `make k8s-down` | Deletes the cluster |
| `make k8s-validate` | Renders every overlay and validates it with kubeconform (no cluster needed) |

Compose's nginx also binds ports 80/443: run `make down` first, or point `KIND_CONFIG` at a copy of `kind/cluster.yaml` with other host ports.
