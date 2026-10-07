#!/usr/bin/env bash
# Installs the edge platform components (K3) into the kind cluster from their upstream charts and release manifests
# at the versions pinned in deploy/k8s/versions.env (TR-K1), with values and CRs from deploy/k8s/platform/:
#   cert-manager, trust-manager, the cluster PKI (root CA, CA ClusterIssuer, trust Bundle),
#   the Gateway API CRDs, NGINX Gateway Fabric and the CoreDNS rewrite of *.polaris.local (K3);
#   the OpenTelemetry Collector and its log agent (K4).
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

# env_value <key>: the value of `<key>=<value>` in the untracked env file (the last one wins), or nothing. The file is
# read as data, never sourced.
env_value() { if [ -f "$K8S_ENV_FILE" ]; then sed -n "s/^$1=//p" "$K8S_ENV_FILE" | tail -n 1; fi; }

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
log "platform components ready"
