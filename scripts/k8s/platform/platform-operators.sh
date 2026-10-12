#!/usr/bin/env bash
# Installs the operators and cluster services the apps' data layer needs: the CloudNativePG operator (K5), the Strimzi
# cluster operator (K6), the Keycloak Operator and its CRDs (K7), metrics-server and the CNPG Barman Cloud plugin (K11).
# Needs cert-manager (platform-cert-manager.sh). Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/../lib.sh
source "$(dirname "$0")/../lib.sh"

ensure_tools_cluster

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

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

helm_install "$CNPG_RELEASE" "$CNPG_NAMESPACE" "$CNPG_CHART" "$CNPG_VERSION" \
  --values "$PLATFORM_DIR/cloudnative-pg/values.yaml"
install_strimzi
install_keycloak_operator
# K11: the resource metrics API for the HorizontalPodAutoscalers, and the Barman Cloud plugin next to the CNPG operator.
helm_install metrics-server kube-system "$METRICS_SERVER_CHART" "$METRICS_SERVER_VERSION" \
  --repo "$METRICS_SERVER_REPO" --values "$PLATFORM_DIR/metrics-server/values.yaml"
helm_install "$BARMAN_CLOUD_RELEASE" "$CNPG_NAMESPACE" "$BARMAN_CLOUD_CHART" "$BARMAN_CLOUD_VERSION" \
  --values "$PLATFORM_DIR/barman-cloud/values.yaml"
