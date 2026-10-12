#!/usr/bin/env bash
# Installs the edge (K3): the Gateway API CRDs, NGINX Gateway Fabric and the CoreDNS rewrite of *.polaris.local.
# Needs the PKI of platform-cert-manager.sh. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools_cluster

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

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

install_gateway_api_crds
helm_install nginx-gateway-fabric nginx-gateway "$NGF_CHART" "$NGF_VERSION" \
  --values "$PLATFORM_DIR/nginx-gateway-fabric/values.yaml"
install_coredns_rewrite
