# shellcheck shell=bash
# Shared settings for scripts/k8s/*.sh. Source it; don't run it.
# Every kubectl call targets the kind context explicitly, so other clusters in the kubeconfig are never touched.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
K8S_DIR="$REPO_ROOT/deploy/k8s"
# shellcheck source=SCRIPTDIR/../../deploy/k8s/versions.env
source "$K8S_DIR/versions.env"

KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-polaris}"
KIND_CONFIG="${KIND_CONFIG:-$K8S_DIR/kind/cluster.yaml}"
K8S_OVERLAY="${K8S_OVERLAY:-$K8S_DIR/overlays/local}"
K8S_NAMESPACE="${K8S_NAMESPACE:-polaris}"
KUBE_CONTEXT="kind-$KIND_CLUSTER_NAME"

# Pinned tools are installed here by scripts/k8s/tools.sh and take precedence over anything on PATH.
TOOLS_BIN="${TOOLS_BIN:-$REPO_ROOT/.tools/bin}"
export PATH="$TOOLS_BIN:$PATH"

log() { printf '[k8s] %s\n' "$*" >&2; }
die() {
  log "ERROR: $*"
  exit 1
}

ensure_tools() { "$REPO_ROOT/scripts/k8s/tools.sh" "$@"; }

kctl() { kubectl --context "$KUBE_CONTEXT" "$@"; }

cluster_exists() { kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER_NAME"; }
