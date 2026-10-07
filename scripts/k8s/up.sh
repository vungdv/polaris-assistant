#!/usr/bin/env bash
# Creates the local kind cluster (if it doesn't exist yet), installs the platform components (platform.sh) and
# applies the local overlay, then waits for the edge to be ready. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind kubectl helm
command -v docker >/dev/null || die "docker is required by kind"

if cluster_exists; then
  log "kind cluster '$KIND_CLUSTER_NAME' already exists, reusing it"
else
  log "creating kind cluster '$KIND_CLUSTER_NAME' ($KIND_NODE_IMAGE) from $KIND_CONFIG"
  kind create cluster --name "$KIND_CLUSTER_NAME" --image "$KIND_NODE_IMAGE" --config "$KIND_CONFIG" --wait 120s ||
    die "cluster creation failed. If host ports 80/443 are taken (Compose's nginx), run 'make down' first or set KIND_CONFIG"
fi

"$REPO_ROOT/scripts/k8s/platform.sh"

write_image_pins
log "applying ${K8S_OVERLAY#"$REPO_ROOT"/} (app images pinned to $IMAGE_TAG)"
kctl apply -k "$K8S_OVERLAY"
kctl wait --for=jsonpath='{.status.phase}'=Active "namespace/$K8S_NAMESPACE" --timeout=60s >/dev/null

# Edge (K3): the certificate is issued, the Gateway is programmed with a running data plane, and the trust bundle
# has reached the namespace.
log "waiting for the edge certificate, the Gateway and the trust bundle"
kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready certificate/polaris-edge-tls --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" wait --for=condition=Programmed "gateway/$GATEWAY_NAME" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" rollout status "deployment/$GATEWAY_SERVICE" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl wait --for=condition=Synced "bundle/$CA_BUNDLE" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" wait --for=create "configmap/$CA_BUNDLE" --timeout=60s >/dev/null
log "cluster '$KIND_CLUSTER_NAME' is up (context $KUBE_CONTEXT)"
