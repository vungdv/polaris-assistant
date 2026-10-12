#!/usr/bin/env bash
# Installs cert-manager, trust-manager and the cluster PKI (root CA, CA ClusterIssuer, trust Bundle) (K3). Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/../lib.sh
source "$(dirname "$0")/../lib.sh"

ensure_tools_cluster

install_pki() {
  log "applying the cluster PKI (root CA, CA ClusterIssuer, trust Bundle)"
  # trust-manager's validating webhook can refuse the Bundle for a few seconds after its Pod is Ready, until
  # cert-manager's cainjector has written the webhook's CA bundle.
  retry 6 kctl apply -k "$PLATFORM_DIR/pki"
  kctl -n cert-manager wait --for=condition=Ready certificate/polaris-root-ca --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
  kctl wait --for=condition=Ready clusterissuer/polaris-ca --timeout="$K8S_WAIT_TIMEOUT" >/dev/null
}

helm_install cert-manager cert-manager "$CERT_MANAGER_CHART" "$CERT_MANAGER_VERSION" \
  --values "$PLATFORM_DIR/cert-manager/values.yaml"
helm_install trust-manager cert-manager "$TRUST_MANAGER_CHART" "$TRUST_MANAGER_VERSION" \
  --values "$PLATFORM_DIR/trust-manager/values.yaml"
install_pki
