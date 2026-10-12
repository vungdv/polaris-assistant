#!/usr/bin/env bash
# Installs the OpenTelemetry Collector and its log agent (K4). Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools_cluster

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

install_otel_collector
install_otel_agent
