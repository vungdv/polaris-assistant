#!/usr/bin/env bash
# Renders every overlay and every platform kustomization (deploy/k8s/platform/*/) with `kubectl kustomize` and
# validates the output with kubeconform in strict mode, against the schemas of the pinned Kubernetes version plus the
# pinned CRDs catalog for custom resources (cert-manager, trust-manager, Gateway API, NGINX Gateway Fabric, CloudNativePG
# and its Barman Cloud plugin, Strimzi, Keycloak).
# The Helm values of each platform component are rendered against their pinned chart. Needs no cluster.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kubectl kubeconform helm

k8s_schemas="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/$K8S_SCHEMAS_REF/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
crds_catalog="https://raw.githubusercontent.com/datreeio/CRDs-catalog/$CRDS_CATALOG_REF/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
write_image_pins
write_realm_imports
for dir in "$K8S_DIR"/overlays/*/ "$K8S_DIR"/platform/*/; do
  [ -f "${dir}kustomization.yaml" ] || continue # Helm values directories have no kustomization
  log "validating ${dir#"$REPO_ROOT"/}"
  src="$dir"
  # The Keycloak Operator's kustomization needs its upstream manifests next to it (downloaded and verified).
  if [ "${dir%/}" = "$PLATFORM_DIR/keycloak-operator" ]; then
    src="$tmp/keycloak-operator"
    stage_keycloak_operator "$src"
  fi
  # CustomResourceDefinitions (the Keycloak Operator's) come unchanged from upstream and have no strict schema.
  kubectl kustomize "$src" |
    kubeconform -strict -summary -skip CustomResourceDefinition -kubernetes-version "$KUBERNETES_VERSION" \
      -schema-location "$k8s_schemas" -schema-location "$crds_catalog"
done

# Helm values (platform/<component>/values.yaml, plus optional layers) render against their pinned upstream chart and its values schema,
# and the rendered manifests (including the custom resources the values produce, such as NGINX Gateway Fabric's
# NginxProxy) are validated with kubeconform like the kustomizations above. CustomResourceDefinitions are skipped: they
# come unchanged from the upstream chart, and the Kubernetes schemas catalog has no strict schema for them.
# render_chart [--repo <url>] <chart> <version> <values-file>...   (--repo: a chart from a classic chart repository)
render_chart() {
  local args=() file
  if [ "$1" = --repo ]; then
    args+=(--repo "$2")
    shift 2
  fi
  local chart="$1" version="$2"
  shift 2
  for file in "$@"; do args+=(--values "$file"); done
  log "rendering $(basename "$chart") $version with $(printf '%s ' "${@#"$REPO_ROOT"/}")"
  helm template "$(basename "$chart")" "$chart" --version "$version" "${args[@]}" |
    kubeconform -strict -summary -skip CustomResourceDefinition -kubernetes-version "$KUBERNETES_VERSION" \
      -schema-location "$k8s_schemas" -schema-location "$crds_catalog"
}
render_chart "$CERT_MANAGER_CHART" "$CERT_MANAGER_VERSION" "$K8S_DIR/platform/cert-manager/values.yaml"
render_chart "$TRUST_MANAGER_CHART" "$TRUST_MANAGER_VERSION" "$K8S_DIR/platform/trust-manager/values.yaml"
render_chart "$NGF_CHART" "$NGF_VERSION" "$K8S_DIR/platform/nginx-gateway-fabric/values.yaml"
# The Collector with and without the Grafana Cloud layer (platform.sh adds it only when credentials exist), and the
# log agent.
render_chart "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" "$K8S_DIR/platform/otel-collector/values.yaml"
render_chart "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" "$K8S_DIR/platform/otel-collector/values.yaml" \
  "$K8S_DIR/platform/otel-collector/grafana-cloud.values.yaml"
render_chart "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" "$K8S_DIR/platform/otel-agent/values.yaml"
render_chart "$CNPG_CHART" "$CNPG_VERSION" "$K8S_DIR/platform/cloudnative-pg/values.yaml"
render_chart "$STRIMZI_CHART" "$STRIMZI_VERSION" "$K8S_DIR/platform/strimzi/values.yaml"
# K11: metrics-server, the Barman Cloud plugin and the object store.
render_chart --repo "$METRICS_SERVER_REPO" "$METRICS_SERVER_CHART" "$METRICS_SERVER_VERSION" \
  "$K8S_DIR/platform/metrics-server/values.yaml"
render_chart "$BARMAN_CLOUD_CHART" "$BARMAN_CLOUD_VERSION" "$K8S_DIR/platform/barman-cloud/values.yaml"
render_chart --repo "$SEAWEEDFS_REPO" "$SEAWEEDFS_CHART" "$SEAWEEDFS_VERSION" "$K8S_DIR/platform/object-store/values.yaml"
