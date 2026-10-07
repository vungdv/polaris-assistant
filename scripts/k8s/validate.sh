#!/usr/bin/env bash
# Renders every overlay with `kubectl kustomize` and validates the output with kubeconform in strict mode,
# against the schemas of the pinned Kubernetes version plus the pinned CRDs catalog for custom resources.
# Needs no cluster.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kubectl kubeconform

k8s_schemas="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/$K8S_SCHEMAS_REF/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
crds_catalog="https://raw.githubusercontent.com/datreeio/CRDs-catalog/$CRDS_CATALOG_REF/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

write_image_pins
for overlay in "$K8S_DIR"/overlays/*/; do
  log "validating ${overlay#"$REPO_ROOT"/}"
  kubectl kustomize "$overlay" |
    kubeconform -strict -summary -kubernetes-version "$KUBERNETES_VERSION" \
      -schema-location "$k8s_schemas" -schema-location "$crds_catalog"
done
