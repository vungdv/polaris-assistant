#!/usr/bin/env bash
# Waits for the edge, the data stores, Kafka, Keycloak, the observability stack and the apps to be ready. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

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

# Keycloak (K7): the server is ready, the polaris realm import Job is done, and the rolling restart the operator starts
# after an import has finished. On the first run the server also builds itself and imports the master realm.
log "waiting for Keycloak and the realm imports (${KEYCLOAK_REALM_IMPORTS[*]})"
kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "keycloak.k8s.keycloak.org/$KEYCLOAK" \
  --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
for realm in "${KEYCLOAK_REALM_IMPORTS[@]}"; do
  kctl -n "$K8S_NAMESPACE" wait --for=condition=Done "keycloakrealmimport.k8s.keycloak.org/$realm" \
    --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
done
kctl -n "$K8S_NAMESPACE" rollout status "statefulset/$KEYCLOAK" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "keycloak.k8s.keycloak.org/$KEYCLOAK" \
  --timeout="$K8S_WAIT_TIMEOUT" >/dev/null

# Local LGTM stack (K12): Helm already waited for each release (--wait); this confirms they are still available.
log "waiting for Tempo, Loki, Prometheus and Grafana"
kctl -n "$OBSERVABILITY_NAMESPACE" rollout status "statefulset/$TEMPO_RELEASE" "statefulset/$LOKI_RELEASE" \
  --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
kctl -n "$OBSERVABILITY_NAMESPACE" rollout status "deployment/$PROMETHEUS_RELEASE" "deployment/$GRAFANA_RELEASE" \
  --timeout="$K8S_WAIT_TIMEOUT" >/dev/null

# Order & Catalog (K8): Swagger UI is available, and polaris has its two replicas ready (Flyway done) once its image
# is on the node. On a fresh cluster it isn't yet: `make k8s-images` builds and loads it, and the next `make k8s-up`
# waits here.
log "waiting for $SWAGGER_UI"
kctl -n "$K8S_NAMESPACE" rollout status "deployment/$SWAGGER_UI" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
# Assistant (K9) and fulfilment emulator (K10): likewise, once their images are loaded; their pods start after polaris
# is ready (init container).
for app in "$POLARIS" "$ASSISTANT" "$EMULATOR"; do
  if image_loaded "$(app_image "$app")"; then
    log "waiting for $app ($(app_image "$app"))"
    kctl -n "$K8S_NAMESPACE" rollout status "deployment/$app" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
  else
    log "image $(app_image "$app") is not loaded yet: run 'make k8s-images APPS=$app', then 'make k8s-up' again"
  fi
done
