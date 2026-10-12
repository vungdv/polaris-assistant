#!/usr/bin/env bash
# Creates the local kind cluster if it doesn't exist yet, or refreshes its kubeconfig. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

if cluster_exists; then
  log "kind cluster '$KIND_CLUSTER_NAME' already exists, reusing it"
  ensure_kubeconfig
else
  log "creating kind cluster '$KIND_CLUSTER_NAME' ($KIND_NODE_IMAGE) from $KIND_CONFIG"
  kind create cluster --name "$KIND_CLUSTER_NAME" --image "$KIND_NODE_IMAGE" --config "$KIND_CONFIG" \
    --kubeconfig "$K8S_KUBECONFIG" --wait 120s ||
    die "cluster creation failed. If host ports 80/443 are taken (Compose's nginx), run 'make down' first or set KIND_CONFIG"
fi
