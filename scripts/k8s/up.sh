#!/usr/bin/env bash
# Creates the local kind cluster (if it doesn't exist yet), installs the platform components (platform.sh) and
# applies the local overlay, then waits for the edge, the data stores, Kafka, Keycloak and the apps to be ready. Safe to
# re-run.
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

# Realm placeholders (K7): DEFAULT_PASSWORD and POLARIS_FULFILMENT_EMULATOR_SECRET, the keys Compose substitutes into
# docker/keycloak/*.json. Each comes from the untracked env file when it sets it. Otherwise (CI, or a key left empty)
# the value already in the Secret is kept, and only a missing one is generated at random, so re-runs never change it:
# the realms are imported once, into an empty database, like Compose's --import-realm, and keep their first values.
# printf is a shell builtin, so the values never appear in a process's arguments.
apply_realm_secret() {
  local key value values="" generated=()
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  for key in DEFAULT_PASSWORD POLARIS_FULFILMENT_EMULATOR_SECRET; do
    value="$(env_value "$key")"
    [ -n "$value" ] || value="$(kctl -n "$K8S_NAMESPACE" get secret "$KEYCLOAK_REALM_SECRET" \
      -o jsonpath="{.data.$key}" 2>/dev/null | base64 -d 2>/dev/null || true)"
    if [ -z "$value" ]; then
      value="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
      generated+=("$key")
    fi
    values+="$key=$value"$'\n'
  done
  [ "${#generated[@]}" -eq 0 ] ||
    log "generated ${generated[*]} at random (not set in ${K8S_ENV_FILE#"$REPO_ROOT"/}); read it from Secret $KEYCLOAK_REALM_SECRET"
  kctl -n "$K8S_NAMESPACE" create secret generic "$KEYCLOAK_REALM_SECRET" --from-env-file=<(printf '%s' "$values") \
    --dry-run=client -o yaml | kctl apply -f - >/dev/null
}
apply_realm_secret

# Assistant credentials and settings (K9): GEMINI_API_KEY, TYPESAFE_API_KEY and AGENTO11Y_*, the keys Compose passes to
# polaris-assistant from .env. Unlike the realm placeholders these aren't imported once, so the Secret mirrors the env
# file on every run: a key it leaves empty is left out, and application.yml's default applies (no API key: the
# assistant still starts and stays ready, but chat turns answer 503). Pods read the Secret at start, so a changed Secret
# restarts the Deployment (rolling, as any restart). CI writes the env file from repository secrets.
apply_assistant_secret() {
  local key value values="" result
  for key in "${ASSISTANT_SECRET_KEYS[@]}"; do
    value="$(env_value "$key")"
    [ -z "$value" ] || values+="$key=$value"$'\n'
  done
  [ -n "$values" ] || log "no assistant keys in ${K8S_ENV_FILE#"$REPO_ROOT"/}: chat turns need GEMINI_API_KEY"
  result="$(kctl -n "$K8S_NAMESPACE" create secret generic "$ASSISTANT_SECRET" --from-env-file=<(printf '%s' "$values") \
    --dry-run=client -o yaml | kctl apply -f -)"
  if [[ "$result" == *" configured" ]] && kctl -n "$K8S_NAMESPACE" get "deployment/$ASSISTANT" >/dev/null 2>&1; then
    log "Secret $ASSISTANT_SECRET changed: restarting deployment/$ASSISTANT"
    kctl -n "$K8S_NAMESPACE" rollout restart "deployment/$ASSISTANT" >/dev/null
  fi
}
apply_assistant_secret

write_image_pins
write_realm_imports
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

# Order & Catalog (K8): Swagger UI is available, and polaris has its two replicas ready (Flyway done) once its image
# is on the node. On a fresh cluster it isn't yet: `make k8s-images` builds and loads it, and the next `make k8s-up`
# waits here.
log "waiting for $SWAGGER_UI"
kctl -n "$K8S_NAMESPACE" rollout status "deployment/$SWAGGER_UI" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
# Assistant (K9): likewise, once its image is loaded; its pods start after polaris is ready (init container).
for app in "$POLARIS" "$ASSISTANT"; do
  if image_loaded "$(app_image "$app")"; then
    log "waiting for $app ($(app_image "$app"))"
    kctl -n "$K8S_NAMESPACE" rollout status "deployment/$app" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
  else
    log "image $(app_image "$app") is not loaded yet: run 'make k8s-images APPS=$app', then 'make k8s-up' again"
  fi
done
log "cluster '$KIND_CLUSTER_NAME' is up (context $KUBE_CONTEXT)"
