# Kubernetes deployment

Manifests and tooling for running Polaris on Kubernetes ([ADR-0021](../../docs/technical/decisions/0021-kubernetes-deployment-platform.md), [plan](../../docs/development/plan/k8s-deployment.md)). Compose stays the inner-loop environment.

| Path | Contents |
|:--|:--|
| `versions.env` | Pinned versions of kind, its node image, kubectl, kubeconform, helm, the schema catalogs and the platform charts |
| `kind/cluster.yaml` | Local kind cluster: host ports 80/443 → NodePorts 30080/30443 |
| `platform/` | Helm values and CRs for third-party components: cert-manager, trust-manager, NGINX Gateway Fabric, the cluster PKI (`pki/`) and the CoreDNS rewrite (`coredns/`) |
| `base/` | Cluster-agnostic manifests: the `polaris` namespace (Pod Security `restricted`), then one directory per component (`edge/`: Gateway, certificate, HTTP redirect) |
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
kubectl --context kind-polaris -n cert-manager get secret polaris-root-ca -o jsonpath='{.data.tls\.crt}' | base64 -d > /tmp/polaris-root-ca.pem
curl --cacert /tmp/polaris-root-ca.pem https://polaris.local/   # 404 until app routes exist
```

With `KIND_CONFIG` on other host ports, add for example `--resolve polaris.local:18443:127.0.0.1` and use port 18443.
