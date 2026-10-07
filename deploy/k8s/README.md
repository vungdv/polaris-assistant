# Kubernetes deployment

Manifests and tooling for running Polaris on Kubernetes ([ADR-0021](../../docs/technical/decisions/0021-kubernetes-deployment-platform.md), [plan](../../docs/development/plan/k8s-deployment.md)). Compose stays the inner-loop environment.

| Path | Contents |
|:--|:--|
| `versions.env` | Pinned versions of kind, its node image, kubectl, kubeconform and the schema catalogs |
| `kind/cluster.yaml` | Local kind cluster: host ports 80/443 → NodePorts 30080/30443 |
| `platform/` | Pinned Helm values and operator CRs for third-party components (added by later slices) |
| `base/` | Cluster-agnostic manifests: the `polaris` namespace (Pod Security `restricted`), then one directory per component |
| `overlays/local/` | The kind environment: image tags, replica counts, Secrets from the untracked `deploy/k8s/.env.local` |
| `overlays/local/images/` | Generated and untracked: the Kustomize component that pins each app image to `ghcr.io/<owner>/<app>:<git-sha>` of the current checkout |

| Command | What it does |
|:--|:--|
| `make k8s-up` | Installs the pinned tools into `.tools/bin`, creates the kind cluster `polaris` if missing, applies `overlays/local` |
| `make k8s-images` | Builds the three app images as `ghcr.io/vungdv/<app>:<git-sha>` and loads them into the cluster with `kind load docker-image` (`APPS=polaris` for a subset) |
| `make k8s-smoke` | Runs `scripts/k8s/smoke.sh` against the cluster |
| `make k8s-down` | Deletes the cluster |
| `make k8s-validate` | Renders every overlay and validates it with kubeconform (no cluster needed) |

Compose's nginx also binds ports 80/443: run `make down` first, or point `KIND_CONFIG` at a copy of `kind/cluster.yaml` with other host ports.

## Application images

CI (`.github/workflows/images.yml`) builds `polaris`, `polaris-assistant` and `polaris-fulfilment-emulator` from their
Dockerfiles on every PR and pushes `ghcr.io/<owner>/<app>:<git-sha>` on a push to `main`. The images run as UID/GID
10001, so `runAsNonRoot` can verify them.

Base manifests name an app image by its bare app name (`image: polaris`). The local overlay maps that name to
`$IMAGE_REGISTRY/<app>:$IMAGE_TAG` through the generated `overlays/local/images` component. `make k8s-up`,
`make k8s-validate` and `make k8s-images` rewrite it from the current commit SHA, so no tag is ever committed.
`IMAGE_REGISTRY` (default `ghcr.io/vungdv`) and `IMAGE_TAG` can be overridden, for example to run a fork's images or
an image CI already pushed. After a new commit, run `make k8s-images` again: the pin moves to the new SHA.
