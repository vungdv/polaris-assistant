#!/usr/bin/env bash
# Brings up the local kind environment, one script per step (each safe to re-run on its own, and so is this one).
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

K8S_SCRIPTS="$REPO_ROOT/scripts/k8s"

log "step 1/11: ensure-tools (kind, kubectl, helm, docker)"
"$K8S_SCRIPTS/ensure-tools.sh"

log "step 2/11: up-cluster (kind cluster)"
"$K8S_SCRIPTS/up-cluster.sh"

log "step 3/11: platform-cert-manager (cert-manager, trust-manager, cluster PKI)"
"$K8S_SCRIPTS/platform/platform-cert-manager.sh"

log "step 4/11: platform-gateway (Gateway API, NGINX Gateway Fabric, CoreDNS rewrite)"
"$K8S_SCRIPTS/platform/platform-gateway.sh"

log "step 5/11: platform-observability (OpenTelemetry Collector and log agent)"
"$K8S_SCRIPTS/platform/platform-observability.sh"

log "step 6/11: platform-operators (CloudNativePG, Strimzi, Keycloak, metrics-server, Barman Cloud)"
"$K8S_SCRIPTS/platform/platform-operators.sh"

log "step 7/11: platform-object-store (S3 object store and backup bucket)"
"$K8S_SCRIPTS/platform/platform-object-store.sh"

log "step 8/11: apply-secrets (Keycloak realm placeholders, assistant credentials)"
"$K8S_SCRIPTS/apply-secrets.sh"

log "step 9/11: platform-lgtm (Tempo, Loki, Prometheus, Grafana)"
"$K8S_SCRIPTS/platform/platform-lgtm.sh"

log "step 10/11: deploy-apps (image pins, realm imports, local overlay)"
"$K8S_SCRIPTS/deploy-apps.sh"

log "step 11/11: wait-ready (edge, data stores, Kafka, Keycloak, apps)"
"$K8S_SCRIPTS/wait-ready.sh"

log "cluster '$KIND_CLUSTER_NAME' is up (context $KUBE_CONTEXT)"
