#!/usr/bin/env bash
# Creates the local kind cluster (if it doesn't exist yet), installs the platform components (platform.sh) and
# applies the local overlay, then waits for the edge, the data stores and Kafka to be ready. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind kubectl helm
command -v docker >/dev/null || die "docker is required by kind"

if cluster_exists; then
  log "kind cluster '$KIND_CLUSTER_NAME' already exists, reusing it"
  ensure_kubeconfig
else
  log "creating kind cluster '$KIND_CLUSTER_NAME' ($KIND_NODE_IMAGE) from $KIND_CONFIG"
  kind create cluster --name "$KIND_CLUSTER_NAME" --image "$KIND_NODE_IMAGE" --config "$KIND_CONFIG" \
    --kubeconfig "$K8S_KUBECONFIG" --wait 120s ||
    die "cluster creation failed. If host ports 80/443 are taken (Compose's nginx), run 'make down' first or set KIND_CONFIG"
fi

"$REPO_ROOT/scripts/k8s/platform.sh"

write_image_pins
log "applying ${K8S_OVERLAY#"$REPO_ROOT"/} (app images pinned to $IMAGE_TAG)"
# CloudNativePG's admission webhooks can refuse the Clusters for a few seconds after the operator is Ready, until its
# self-generated serving certificate is in the webhook configurations.
retry 6 kctl apply -k "$K8S_OVERLAY"
kctl wait --for=jsonpath='{.status.phase}'=Active "namespace/$K8S_NAMESPACE" --timeout=60s >/dev/null

# Edge (K3): the certificate is issued, the Gateway is programmed with a running data plane, and the trust bundle
# has reached the namespace.
log "waiting for the edge certificate, the Gateway and the trust bundle"
kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready certificate/polaris-edge-tls --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" wait --for=condition=Programmed "gateway/$GATEWAY_NAME" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" rollout status "deployment/$GATEWAY_SERVICE" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl wait --for=condition=Synced "bundle/$CA_BUNDLE" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" wait --for=create "configmap/$CA_BUNDLE" --timeout=60s >/dev/null

# Data stores (K5): every PostgreSQL Cluster has its instances ready (initdb included on the first run) and Redis is
# available.
log "waiting for the PostgreSQL clusters (${PG_CLUSTERS[*]}) and Redis"
for cluster in "${PG_CLUSTERS[@]}"; do
  kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "cluster.postgresql.cnpg.io/$cluster" \
    --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
done
kctl -n "$K8S_NAMESPACE" rollout status "deployment/$REDIS" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null

# Kafka (K6): the operator reports the Kafka Ready once every node is running and the listeners are up.
log "waiting for the Kafka cluster $KAFKA_CLUSTER"
kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "kafka.kafka.strimzi.io/$KAFKA_CLUSTER" \
  --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
log "cluster '$KIND_CLUSTER_NAME' is up (context $KUBE_CONTEXT)"
