#!/usr/bin/env bash
# Generates the untracked image pins and realm imports, then applies the local overlay. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

write_image_pins
write_realm_imports
log "applying ${K8S_OVERLAY#"$REPO_ROOT"/} (app images pinned to $IMAGE_TAG)"
# CloudNativePG's admission webhooks can refuse the Clusters for a few seconds after the operator is Ready, until its
# self-generated serving certificate is in the webhook configurations.
retry 6 kctl apply -k "$K8S_OVERLAY"
kctl wait --for=jsonpath='{.status.phase}'=Active "namespace/$K8S_NAMESPACE" --timeout=60s >/dev/null
