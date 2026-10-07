#!/usr/bin/env bash
# Deletes the local kind cluster. Safe to re-run: does nothing when the cluster doesn't exist.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind

if cluster_exists; then
  kind delete cluster --name "$KIND_CLUSTER_NAME" --kubeconfig "$K8S_KUBECONFIG"
else
  log "kind cluster '$KIND_CLUSTER_NAME' does not exist, nothing to delete"
fi
