#!/usr/bin/env bash
# Installs the edge platform components (K3) into the kind cluster from their upstream charts and release manifests
# at the versions pinned in deploy/k8s/versions.env (TR-K1), with values and CRs from deploy/k8s/platform/:
#   cert-manager, trust-manager, the cluster PKI (root CA, CA ClusterIssuer, trust Bundle),
#   the Gateway API CRDs, NGINX Gateway Fabric, and the CoreDNS rewrite of *.polaris.local.
# Each step waits for readiness with a bounded timeout (K8S_WAIT_TIMEOUT). Safe to re-run. Called by up.sh.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kubectl helm
cluster_exists || die "kind cluster '$KIND_CLUSTER_NAME' doesn't exist; run 'make k8s-up'"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# helm_install <release> <namespace> <chart> <version> <values-file>
# `upgrade --install` converges an existing release to the pinned version and values; --wait bounds readiness.
helm_install() {
  log "installing $1 $4 into namespace $2"
  helm --kube-context "$KUBE_CONTEXT" upgrade --install "$1" "$3" --version "$4" \
    --namespace "$2" --create-namespace --values "$5" --wait --timeout "$K8S_WAIT_TIMEOUT" >/dev/null
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

helm_install cert-manager cert-manager "$CERT_MANAGER_CHART" "$CERT_MANAGER_VERSION" \
  "$PLATFORM_DIR/cert-manager/values.yaml"
helm_install trust-manager cert-manager "$TRUST_MANAGER_CHART" "$TRUST_MANAGER_VERSION" \
  "$PLATFORM_DIR/trust-manager/values.yaml"
install_pki
install_gateway_api_crds
helm_install nginx-gateway-fabric nginx-gateway "$NGF_CHART" "$NGF_VERSION" \
  "$PLATFORM_DIR/nginx-gateway-fabric/values.yaml"
install_coredns_rewrite
log "platform components ready"
