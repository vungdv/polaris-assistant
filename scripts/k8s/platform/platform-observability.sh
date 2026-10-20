#!/usr/bin/env bash
# Installs the OpenTelemetry Collector and its log agent (K4). Safe to re-run. The backends the Collector exports to are
# installed by platform-lgtm.sh.
set -euo pipefail
# shellcheck source=SCRIPTDIR/../lib.sh
source "$(dirname "$0")/../lib.sh"

ensure_tools_cluster

# The Collector and the log agent run in the `otel` namespace, which must exist with its Pod Security labels and
# NetworkPolicies before their pods are admitted (platform/otel). The Collector exports to the in-cluster Tempo, Loki and
# Prometheus (K12) and to its debug exporter, never off the cluster.
install_otel_collector() {
  kctl apply -k "$PLATFORM_DIR/otel" >/dev/null
  # Earlier versions exported to Grafana Cloud from a Secret of this name; a cluster upgraded from one drops it.
  kctl -n "$K8S_NAMESPACE" delete secret grafana-cloud --ignore-not-found >/dev/null
  helm_install "$OTEL_COLLECTOR" "$OTEL_NAMESPACE" "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" \
    --values "$PLATFORM_DIR/otel-collector/values.yaml"
}

# The log agent tails the Gateway pod's stdout on the node (platform/otel-agent).
install_otel_agent() {
  helm_install "$OTEL_AGENT" "$OTEL_NAMESPACE" "$OTEL_COLLECTOR_CHART" "$OTEL_COLLECTOR_VERSION" \
    --values "$PLATFORM_DIR/otel-agent/values.yaml"
}

# node-exporter exposes each node's host metrics to Prometheus for the Kubernetes dashboard (platform/node-exporter).
install_node_exporter() {
  helm_install "$NODE_EXPORTER_RELEASE" "$OTEL_NAMESPACE" "$NODE_EXPORTER_CHART" "$NODE_EXPORTER_VERSION" \
    --repo "$PROMETHEUS_REPO" --values "$PLATFORM_DIR/node-exporter/values.yaml"
}

install_otel_collector
install_otel_agent
install_node_exporter
