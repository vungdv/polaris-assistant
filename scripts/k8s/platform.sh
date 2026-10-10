#!/usr/bin/env bash
# Installs the edge platform components (K3) into the kind cluster from their upstream charts and release manifests
# at the versions pinned in deploy/k8s/versions.env (TR-K1), with values and CRs from deploy/k8s/platform/:
#   cert-manager, trust-manager, the cluster PKI (root CA, CA ClusterIssuer, trust Bundle),
#   the Gateway API CRDs, NGINX Gateway Fabric and the CoreDNS rewrite of *.polaris.local (K3);
#   the OpenTelemetry Collector and its log agent (K4);
#   the CloudNativePG operator (K5);
#   the Strimzi cluster operator (K6);
#   the Keycloak Operator and its CRDs (K7);
#   metrics-server, the CNPG Barman Cloud plugin and the in-cluster S3 object store with its credentials (K11).
# Each step waits for readiness with a bounded timeout (K8S_WAIT_TIMEOUT). Safe to re-run. Called by up.sh.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kubectl helm
cluster_exists || die "kind cluster '$KIND_CLUSTER_NAME' doesn't exist; run 'make k8s-up'"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# helm_install <release> <namespace> <chart> <version> [helm args, e.g. --values <file>]...
# `upgrade --install` converges an existing release to the pinned version and values; --wait bounds readiness.
helm_install() {
  local release="$1" namespace="$2" chart="$3" version="$4"
  shift 4
  log "installing $release $version into namespace $namespace"
  helm --kube-context "$KUBE_CONTEXT" upgrade --install "$release" "$chart" --version "$version" \
    --namespace "$namespace" --create-namespace "$@" --wait --timeout "$K8S_WAIT_TIMEOUT" >/dev/null
}

install_gateway_api_crds() {
  local file="$tmp/gateway-api-$GATEWAY_API_VERSION-standard-install.yaml"
  log "installing Gateway API CRDs $GATEWAY_API_VERSION (standard channel)"
  curl -fsSLo "$file" \
    "https://github.com/kubernetes-sigs/gateway-api/releases/download/$GATEWAY_API_VERSION/standard-install.yaml"
  [ "$(sha256 "$file")" = "$GATEWAY_API_CRDS_SHA256" ] ||
    die "checksum mismatch for Gateway API $GATEWAY_API_VERSION standard-install.yaml"
  kctl apply --server-side --force-conflicts -f "$file" >/dev/null
  kctl wait --for=condition=Established --timeout="$K8S_WAIT_TIMEOUT" \
    crd/gatewayclasses.gateway.networking.k8s.io crd/gateways.gateway.networking.k8s.io \
    crd/httproutes.gateway.networking.k8s.io crd/referencegrants.gateway.networking.k8s.io >/dev/null
}

install_pki() {
  log "applying the cluster PKI (root CA, CA ClusterIssuer, trust Bundle)"
  # trust-manager's validating webhook can refuse the Bundle for a few seconds after its Pod is Ready, until
  # cert-manager's cainjector has written the webhook's CA bundle.
  retry 6 kctl apply -k "$PLATFORM_DIR/pki"
  kctl -n cert-manager wait --for=condition=Ready certificate/polaris-root-ca --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
  kctl wait --for=condition=Ready clusterissuer/polaris-ca --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
}

# The Corefile is declared in platform/coredns. CoreDNS is restarted only when the applied Corefile changes, so a
# re-run doesn't bounce cluster DNS. `kubectl diff` exits 0 when there's no difference and 1 when there is.
install_coredns_rewrite() {
  local rc=0
  kctl diff --server-side --force-conflicts --field-manager=polaris-k8s -k "$PLATFORM_DIR/coredns" >/dev/null || rc=$?
  case "$rc" in
    0) log "CoreDNS rewrite for *.polaris.local already in place" ;;
    1)
      log "applying the CoreDNS rewrite of *.polaris.local to $GATEWAY_SERVICE.$K8S_NAMESPACE"
      kctl apply --server-side --force-conflicts --field-manager=polaris-k8s -k "$PLATFORM_DIR/coredns" >/dev/null
      kctl -n kube-system rollout restart deployment/coredns >/dev/null
      kctl -n kube-system rollout status deployment/coredns --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
      ;;
    *) die "kubectl diff of the CoreDNS config failed (exit $rc)" ;;
  esac
}

# The Collector runs in the app namespace, which must exist with its Pod Security labels before the Collector's pod is
# admitted. Grafana Cloud export (D8) is on only when the env file has all three GRAFANA_CLOUD_* credentials: they go
# to the Secret `grafana-cloud` and grafana-cloud.values.yaml adds the exporter. Without them (CI, or an unedited copy
# of .env.template) the Secret is removed and the Collector exports to its debug exporter only.
install_otel_collector() {
  local endpoint id token creds args=(--values "$PLATFORM_DIR/otel-collector/values.yaml")
  endpoint="$(env_value GRAFANA_CLOUD_OTLP_ENDPOINT)"
  id="$(env_value GRAFANA_CLOUD_INSTANCE_ID)"
  token="$(env_value GRAFANA_CLOUD_API_TOKEN)"
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  if [ -n "$endpoint" ] && [ -n "$id" ] && [ -n "$token" ] && [[ "$endpoint" != *"<"* ]]; then
    log "Grafana Cloud export on: Secret grafana-cloud from ${K8S_ENV_FILE#"$REPO_ROOT"/}"
    # printf is a shell builtin, so the credentials never appear in a process's arguments.
    creds="$(printf 'GRAFANA_CLOUD_OTLP_ENDPOINT=%s\nGRAFANA_CLOUD_INSTANCE_ID=%s\nGRAFANA_CLOUD_API_TOKEN=%s\n' \
      "$endpoint" "$id" "$token")"
    kctl -n "$K8S_NAMESPACE" create secret generic grafana-cloud --from-env-file=<(printf '%s\n' "$creds") \
      --dry-run=client -o yaml | kctl apply -f - >/dev/null
    # The checksum rolls the Collector when the credentials change, as the chart's own checksum does for its config.
    args+=(--values "$PLATFORM_DIR/otel-collector/grafana-cloud.values.yaml"
      --set-string "podAnnotations.checksum/grafana-cloud=$(sha256 <(printf '%s\n' "$creds"))")
  else
    log "Grafana Cloud export off: no GRAFANA_CLOUD_* credentials in ${K8S_ENV_FILE#"$REPO_ROOT"/} (debug exporter only)"
    kctl -n "$K8S_NAMESPACE" delete secret grafana-cloud --ignore-not-found >/dev/null
  fi
  helm_install "$OTEL_COLLECTOR" "$K8S_NAMESPACE" "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" "${args[@]}"
}

# The log agent tails the Gateway pod's stdout on the node (platform/otel-agent), in its own namespace.
install_otel_agent() {
  kctl apply -k "$PLATFORM_DIR/otel-agent" >/dev/null
  helm_install "$OTEL_AGENT" "$OTEL_AGENT_NAMESPACE" "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" \
    --values "$PLATFORM_DIR/otel-agent/values.yaml"
}

# The Strimzi chart ships its CRDs in crds/, which Helm installs on the first install and never upgrades. Applying them
# from the pinned chart first keeps them at the operator's version on every run (a no-op when unchanged). The operator
# watches the app namespace, where the chart creates its RoleBindings, so the namespace is applied first.
install_strimzi() {
  log "applying the Strimzi $STRIMZI_VERSION CRDs"
  # helm reports the pull (Pulled:, Digest:) on stderr; show it only when the pull fails.
  helm show crds "$STRIMZI_CHART" --version "$STRIMZI_VERSION" >"$tmp/strimzi-crds.yaml" 2>"$tmp/helm.err" ||
    die "could not read the CRDs of $STRIMZI_CHART $STRIMZI_VERSION: $(cat "$tmp/helm.err")"
  kctl apply --server-side --force-conflicts -f "$tmp/strimzi-crds.yaml" >/dev/null
  kctl wait --for=condition=Established --timeout="$K8S_WAIT_TIMEOUT" \
    crd/kafkas.kafka.strimzi.io crd/kafkanodepools.kafka.strimzi.io >/dev/null
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  helm_install "$STRIMZI_RELEASE" "$STRIMZI_NAMESPACE" "$STRIMZI_CHART" "$STRIMZI_VERSION" \
    --values "$PLATFORM_DIR/strimzi/values.yaml"
}

# The Keycloak Operator from its upstream release manifests (platform/keycloak-operator, staged with the downloaded
# and checksum-verified files). Server-side apply: the Keycloak CRD is too large for client-side apply's annotation.
# The operator runs in the app namespace (see the kustomization), which must exist with its Pod Security labels first.
install_keycloak_operator() {
  log "installing the Keycloak Operator $KEYCLOAK_VERSION into namespace $K8S_NAMESPACE"
  stage_keycloak_operator "$tmp/keycloak-operator"
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  kctl apply --server-side --force-conflicts -k "$tmp/keycloak-operator" >/dev/null
  kctl wait --for=condition=Established --timeout="$K8S_WAIT_TIMEOUT" \
    crd/keycloaks.k8s.keycloak.org crd/keycloakrealmimports.k8s.keycloak.org >/dev/null
  kctl -n "$K8S_NAMESPACE" rollout status "deployment/$KEYCLOAK_OPERATOR" --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
}

# The object store for the PostgreSQL backups (K11, platform/object-store), in its own namespace. Its S3 credentials are
# generated at random on the first run and kept on later runs (read back from the Secret in the app namespace), so
# re-runs never invalidate the backups' access. They go to two Secrets, never to a process's arguments or the repo:
#   object-store/object-store-s3-config   the identity SeaweedFS's S3 gateway accepts, limited to the bucket
#   polaris/object-store-credentials      the keys the ObjectStore (base/data/backup.yaml) signs its requests with
# Then the bucket is created inside the server's pod (`weed shell`), unless it exists.
install_object_store() {
  local key secret config buckets
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  kctl apply -k "$PLATFORM_DIR/object-store" >/dev/null
  key="$(secret_key "$K8S_NAMESPACE" "$OBJECT_STORE_CREDENTIALS" ACCESS_KEY_ID)"
  secret="$(secret_key "$K8S_NAMESPACE" "$OBJECT_STORE_CREDENTIALS" ACCESS_SECRET_KEY)"
  if [ -z "$key" ] || [ -z "$secret" ]; then
    log "generating the object store's S3 credentials (Secret $K8S_NAMESPACE/$OBJECT_STORE_CREDENTIALS)"
    key="cnpg-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    secret="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
  fi
  kctl -n "$K8S_NAMESPACE" create secret generic "$OBJECT_STORE_CREDENTIALS" \
    --from-env-file=<(printf 'ACCESS_KEY_ID=%s\nACCESS_SECRET_KEY=%s\n' "$key" "$secret") \
    --dry-run=client -o yaml | kctl apply -f - >/dev/null
  config="$(printf '{"identities":[{"name":"%s","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Read:%s","Write:%s","List:%s","Tagging:%s"]}]}' \
    "$BACKUP_BUCKET" "$key" "$secret" "$BACKUP_BUCKET" "$BACKUP_BUCKET" "$BACKUP_BUCKET" "$BACKUP_BUCKET")"
  kctl -n "$OBJECT_STORE_NAMESPACE" create secret generic object-store-s3-config \
    --from-file=seaweedfs_s3_config=<(printf '%s' "$config") --dry-run=client -o yaml | kctl apply -f - >/dev/null
  helm_install "$OBJECT_STORE_RELEASE" "$OBJECT_STORE_NAMESPACE" "$SEAWEEDFS_CHART" "$SEAWEEDFS_VERSION" \
    --repo "$SEAWEEDFS_REPO" --values "$PLATFORM_DIR/object-store/values.yaml"
  # `weed shell` talks to the server's master and filer (WEED_CLUSTER_* in the pod) and keeps its history in $HOME.
  buckets="$(weed_shell s3.bucket.list)" || die "could not list the object store's buckets: $buckets"
  if awk '{ print $1 }' <<<"$buckets" | grep -qx "$BACKUP_BUCKET"; then
    log "bucket $BACKUP_BUCKET already exists"
  else
    log "creating bucket $BACKUP_BUCKET"
    weed_shell "s3.bucket.create -name $BACKUP_BUCKET" >/dev/null
  fi
}
weed_shell() {
  kctl -n "$OBJECT_STORE_NAMESPACE" exec "deployment/$OBJECT_STORE_DEPLOYMENT" -c seaweedfs -- \
    sh -c "echo '$1' | HOME=/tmp weed shell" 2>&1
}

helm_install cert-manager cert-manager "$CERT_MANAGER_CHART" "$CERT_MANAGER_VERSION" \
  --values "$PLATFORM_DIR/cert-manager/values.yaml"
helm_install trust-manager cert-manager "$TRUST_MANAGER_CHART" "$TRUST_MANAGER_VERSION" \
  --values "$PLATFORM_DIR/trust-manager/values.yaml"
install_pki
install_gateway_api_crds
helm_install nginx-gateway-fabric nginx-gateway "$NGF_CHART" "$NGF_VERSION" \
  --values "$PLATFORM_DIR/nginx-gateway-fabric/values.yaml"
install_coredns_rewrite
install_otel_collector
install_otel_agent
helm_install "$CNPG_RELEASE" "$CNPG_NAMESPACE" "$CNPG_CHART" "$CNPG_VERSION" \
  --values "$PLATFORM_DIR/cloudnative-pg/values.yaml"
install_strimzi
install_keycloak_operator
# K11: the resource metrics API for the HorizontalPodAutoscalers, and the PostgreSQL backups (the Barman Cloud plugin
# next to the CNPG operator, the object store it writes to).
helm_install metrics-server kube-system "$METRICS_SERVER_CHART" "$METRICS_SERVER_VERSION" \
  --repo "$METRICS_SERVER_REPO" --values "$PLATFORM_DIR/metrics-server/values.yaml"
helm_install "$BARMAN_CLOUD_RELEASE" "$CNPG_NAMESPACE" "$BARMAN_CLOUD_CHART" "$BARMAN_CLOUD_VERSION" \
  --values "$PLATFORM_DIR/barman-cloud/values.yaml"
install_object_store
log "platform components ready"
