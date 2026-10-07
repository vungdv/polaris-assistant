#!/usr/bin/env bash
# Renders every overlay and every platform kustomization (deploy/k8s/platform/*/) with `kubectl kustomize` and
# validates the output with kubeconform in strict mode, against the schemas of the pinned Kubernetes version plus the
# pinned CRDs catalog for custom resources (cert-manager, trust-manager, Gateway API).
# The Helm values of each platform component are rendered against their pinned chart. Needs no cluster.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kubectl kubeconform helm

k8s_schemas="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/$K8S_SCHEMAS_REF/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
crds_catalog="https://raw.githubusercontent.com/datreeio/CRDs-catalog/$CRDS_CATALOG_REF/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

write_image_pins
for dir in "$K8S_DIR"/overlays/*/ "$K8S_DIR"/platform/*/; do
  [ -f "${dir}kustomization.yaml" ] || continue # Helm values directories have no kustomization
  log "validating ${dir#"$REPO_ROOT"/}"
  kubectl kustomize "$dir" |
    kubeconform -strict -summary -kubernetes-version "$KUBERNETES_VERSION" \
      -schema-location "$k8s_schemas" -schema-location "$crds_catalog"
done

# Helm values (platform/<component>/values.yaml) render against their pinned upstream chart and its values schema.
# render_chart <chart> <version> <values-file>
render_chart() {
  log "rendering $(basename "$1") $2 with ${3#"$REPO_ROOT"/}"
  helm template "$(basename "$1")" "$1" --version "$2" --values "$3" >/dev/null
}
render_chart "$CERT_MANAGER_CHART" "$CERT_MANAGER_VERSION" "$K8S_DIR/platform/cert-manager/values.yaml"
render_chart "$TRUST_MANAGER_CHART" "$TRUST_MANAGER_VERSION" "$K8S_DIR/platform/trust-manager/values.yaml"
render_chart "$NGF_CHART" "$NGF_VERSION" "$K8S_DIR/platform/nginx-gateway-fabric/values.yaml"
