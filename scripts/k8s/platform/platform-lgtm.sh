#!/usr/bin/env bash
# Installs the local observability backends the Collector exports to (K12, D8): Tempo (traces), Loki (logs), Prometheus
# (metrics and the SLO rules) and Grafana, each from its upstream chart, in their own namespace (platform/observability). There is no Grafana Cloud.
# Their configuration is the Compose one under docker/telemetry, the single source for both environments. Needs the
# realm Secret (apply-secrets.sh) and the trust bundle (platform-cert-manager.sh). Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/../lib.sh
source "$(dirname "$0")/../lib.sh"

ensure_tools_cluster

TELEMETRY_DIR="$REPO_ROOT/docker/telemetry"

# configmap_from_files <name> <file>...: applies a ConfigMap with one key per file. Server-side apply
# because the dashboards exceed the size of the last-applied annotation that client-side apply keeps.
configmap_from_files() {
  local name="$1"
  shift
  kctl -n "$OBSERVABILITY_NAMESPACE" create configmap "$name" "${@/#/--from-file=}" --dry-run=client -o yaml |
    kctl -n "$OBSERVABILITY_NAMESPACE" apply --server-side --force-conflicts -f - >/dev/null
}

# digest <file>...: one SHA-256 of the files, to roll a workload when a ConfigMap it mounts changes.
digest() { sha256 <(cat "$@"); }

# Grafana's admin account (docker-compose.otel.yml: GRAFANA_ADMIN_USER, DEFAULT_PASSWORD). The password is the realm
# placeholder DEFAULT_PASSWORD, which apply-secrets.sh keeps in the realm Secret, so the admin login and the Keycloak
# master realm's users share the one value, as in Compose. printf is a shell builtin: it never appears in arguments.
apply_grafana_admin() {
  local user password
  user="$(env_value GRAFANA_ADMIN_USER)"
  user="${user:-admin}"
  password="$(secret_key "$K8S_NAMESPACE" "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)"
  [ -n "$password" ] || die "Secret $K8S_NAMESPACE/$KEYCLOAK_REALM_SECRET has no DEFAULT_PASSWORD; run apply-secrets.sh first"
  kctl -n "$OBSERVABILITY_NAMESPACE" create secret generic "$GRAFANA_ADMIN_SECRET" \
    --from-env-file=<(printf 'admin-user=%s\nadmin-password=%s\n' "$user" "$password") \
    --dry-run=client -o yaml | kctl -n "$OBSERVABILITY_NAMESPACE" apply -f - >/dev/null
}

install_lgtm() {
  local rules=("$TELEMETRY_DIR"/prometheus/rules/*.yml)
  local datasources="$TELEMETRY_DIR/grafana/provisioning/datasources/datasources.yml"
  local dashboards=("$TELEMETRY_DIR"/grafana/provisioning/dashboards/json/*.json)
  kctl apply -k "$PLATFORM_DIR/observability" >/dev/null

  configmap_from_files prometheus-rules "${rules[@]}"
  configmap_from_files grafana-datasources "$datasources"
  configmap_from_files grafana-dashboards "${dashboards[@]}"
  apply_grafana_admin

  helm_install "$TEMPO_RELEASE" "$OBSERVABILITY_NAMESPACE" "$TEMPO_CHART" "$TEMPO_VERSION" \
    --repo "$GRAFANA_COMMUNITY_REPO" --values "$PLATFORM_DIR/tempo/values.yaml"
  helm_install "$LOKI_RELEASE" "$OBSERVABILITY_NAMESPACE" "$LOKI_CHART" "$LOKI_VERSION" \
    --repo "$GRAFANA_COMMUNITY_REPO" --values "$PLATFORM_DIR/loki/values.yaml"
  helm_install "$PROMETHEUS_RELEASE" "$OBSERVABILITY_NAMESPACE" "$PROMETHEUS_CHART" "$PROMETHEUS_VERSION" \
    --repo "$PROMETHEUS_REPO" --values "$PLATFORM_DIR/prometheus/values.yaml" \
    --set-string "server.podAnnotations.checksum/rules=$(digest "${rules[@]}")"
  helm_install "$GRAFANA_RELEASE" "$OBSERVABILITY_NAMESPACE" "$GRAFANA_CHART" "$GRAFANA_VERSION" \
    --repo "$GRAFANA_COMMUNITY_REPO" --values "$PLATFORM_DIR/grafana/values.yaml" \
    --set-string "podAnnotations.checksum/datasources=$(digest "$datasources")" \
    --set-string "podAnnotations.checksum/dashboards=$(digest "${dashboards[@]}")"
}

install_lgtm
