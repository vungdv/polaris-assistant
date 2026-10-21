# shellcheck shell=bash
# shellcheck disable=SC2034 # used by the sections
# Handles on the data stores that several sections use: the PostgreSQL and Redis images and the Cluster credentials.
# Source it; don't run it.

pg_image="$(kctl -n "$K8S_NAMESPACE" get cluster.postgresql.cnpg.io polaris-db -o jsonpath='{.spec.imageName}' 2>/dev/null || true)"
pg_env="$(for cluster in "${PG_CLUSTERS[@]}"; do
  var="$(tr 'a-z-' 'A-Z_' <<<"$cluster")_URI"
  printf '        - name: %s\n          valueFrom:\n            secretKeyRef:\n              name: %s-app\n              key: uri\n' \
    "$var" "$cluster"
done)"

# wait_for <uri-variable>: the probe first waits (up to 60s) for the `-rw` Service to accept connections, which can lag
# a just-restarted primary's readiness by a few seconds.
wait_for() {
  # shellcheck disable=SC2016 # expanded by the probe's shell, not here
  printf 'for i in $(seq 30); do pg_isready -q -d "$%s" && break; sleep 2; done\n' "$1"
}

redis_image="$(kctl -n "$K8S_NAMESPACE" get deployment "$REDIS" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
