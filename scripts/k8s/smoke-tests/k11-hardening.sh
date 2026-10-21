# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K11: hardening (NetworkPolicies, Pod Security, autoscaling, backups, node drain). Sourced by smoke.sh.

# --- K11: hardening (NetworkPolicies, Pod Security, autoscaling, backups, node drain) --------------------------------
# NetworkPolicies (TR-K10): the namespace denies all traffic by default (base/network-policies); every check above ran
# under it, through the allow list of §Topology (and, for the probes, the smoke fixture). A pod outside the allow list
# whose own egress is open (smoke label `egress-only`) resolves names but can't open a connection to polaris-db, Kafka,
# or the object store's S3 (8333) and unauthenticated filer (8888) ports: the callees' ingress rules refuse it. The
# same probe admitted by the fixture connects to polaris-db and Kafka, so the refusal is the policies'; the object
# store admits only the CNPG pods, whose backups (below) are its control. Each line: `<target> <resolved address>
# <open | blocked-<exit code>>`.
check "NetworkPolicy default-deny selects every pod for ingress and egress" [ "$(kctl -n "$K8S_NAMESPACE" get \
  networkpolicy default-deny -o jsonpath='{.spec.podSelector} {.spec.policyTypes}' 2>/dev/null)" = '{} ["Ingress","Egress"]' ]
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
reach_script='for target in polaris-db-rw:5432 kafka-kafka-bootstrap:9092 $STORE:8333 $STORE:8888; do
  host=${target%:*}; port=${target#*:}
  ip=$(getent hosts "$host" | cut -d" " -f1)
  if timeout 5 bash -c "</dev/tcp/$host/$port" 2>/dev/null; then r=open; else r=blocked-$?; fi
  echo "$target ${ip:-unresolved} $r"
done'
register_probes k11-outsider k11-insider
store="$OBJECT_STORE_DEPLOYMENT.$OBJECT_STORE_NAMESPACE"
store_env="        - name: STORE
          value: $store"
probe_label=egress-only
outsider="$(run_probe k11-outsider "$pg_image" 26 "$reach_script" "$store_env" || true)"
probe_label=true
insider="$(run_probe k11-insider "$pg_image" 26 "$reach_script" "$store_env" || true)"
log "reachability with open egress but no ingress grant:"$'\n'"$outsider"$'\n'"admitted by the fixture:"$'\n'"$insider"
for target in polaris-db-rw:5432 kafka-kafka-bootstrap:9092 "$store:8333" "$store:8888"; do
  blocked() { grep -Eq "^$target [0-9.]+ blocked-" <<<"$outsider"; }
  check "a pod outside the allow list (its own egress open) resolves $target but can't connect to it" blocked
done
for target in polaris-db-rw:5432 kafka-kafka-bootstrap:9092; do
  check "the same probe admitted by the smoke fixture connects to $target (control)" grep -Eq "^$target [0-9.]+ open$" <<<"$insider"
done

# Pod Security `restricted` (TR-K10): the namespace has enforced it since K1, so every pod was admitted under it. A
# server-side dry run that pins the enforce version re-evaluates every running pod of the namespace against the level
# and warns about each one that violates it: there must be none. The same dry run on the observability namespace (the
# accepted exception, its log agent runs as root with a hostPath) must warn, showing the evaluation does report pods.
psa_dry_run() { # <namespace>: the API server's answer to setting enforce=restricted at the cluster's minor version
  kctl label --dry-run=server --overwrite namespace "$1" pod-security.kubernetes.io/enforce=restricted \
    "pod-security.kubernetes.io/enforce-version=v${KUBERNETES_VERSION%.*}" 2>&1
}
pods_checked="$(kctl -n "$K8S_NAMESPACE" get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')"
psa_out="$(psa_dry_run "$K8S_NAMESPACE")"
no_violation() { [[ "$psa_out" == *"labeled"* && "$psa_out" != *"violate"* ]]; }
check "every one of the $pods_checked pods in $K8S_NAMESPACE passes Pod Security restricted" no_violation
[[ "$psa_out" != *"violate"* ]] || log "Pod Security warnings:"$'\n'"$psa_out"
check "the same evaluation flags the privileged otel-agent in $OTEL_NAMESPACE (control)" \
  grep -q "violate" <<<"$(psa_dry_run "$OTEL_NAMESPACE")"
psa_out="$(psa_dry_run "$OBJECT_STORE_NAMESPACE")"
check "every pod in $OBJECT_STORE_NAMESPACE (the object store) passes Pod Security restricted" no_violation
psa_out="$(psa_dry_run "$OBSERVABILITY_NAMESPACE")"
check "every pod in $OBSERVABILITY_NAMESPACE (Tempo, Loki, Prometheus, Grafana) passes Pod Security restricted" no_violation

# Autoscaling (TR-K5): an HPA per replicated app, 2 to 4 replicas on CPU, reading the pods' CPU from metrics-server.
# ScalingActive turns True once the HPA has computed a utilization from the resource metrics API.
check "deployment kube-system/metrics-server is available" \
  quiet kctl -n kube-system rollout status deployment/metrics-server --timeout=60s
for app in "$POLARIS" "$ASSISTANT"; do
  check "HPA $app targets deployment $app with 2-4 replicas on CPU utilization" [ "$(kctl -n "$K8S_NAMESPACE" get hpa \
    "$app" -o jsonpath='{.spec.scaleTargetRef.name} {.spec.minReplicas} {.spec.maxReplicas} {.spec.metrics[0].resource.name} {.spec.metrics[0].resource.target.type}' \
    2>/dev/null)" = "$app 2 4 cpu Utilization" ]
  deadline=$((SECONDS + ${HPA_SMOKE_TIMEOUT:-180}))
  hpa_state=""
  hpa_active() { [[ "$hpa_state" =~ ^True\ [0-9]+\ [2-4]$ ]]; }
  while [ "$SECONDS" -lt "$deadline" ]; do
    hpa_state="$(kctl -n "$K8S_NAMESPACE" get hpa "$app" -o jsonpath="{.status.conditions[?(@.type=='ScalingActive')].status} {.status.currentMetrics[0].resource.current.averageUtilization} {.status.currentReplicas}" 2>/dev/null || true)"
    hpa_active && break
    sleep 5
  done
  read -r _ utilization replicas <<<"$hpa_state"
  check "HPA $app reads its pods' CPU from metrics-server (ScalingActive; now ${utilization:-?}% of request, ${replicas:-?} replicas)" \
    hpa_active
done

# Backups (D2): both Clusters archive their WAL continuously to the ObjectStore and have a base backup from their
# ScheduledBackup (taken when it was created). Then the restore check: a row written to polaris-db is in an on-demand
# backup, and a new Cluster bootstrapped from the object store (recovery) contains it. The rows, the backup's resource
# and the restored Cluster are removed at the end (the backup's files stay, under the retention policy).
check "ObjectStore cnpg-backups points at s3://$BACKUP_BUCKET/" [ "$(kctl -n "$K8S_NAMESPACE" get \
  objectstore.barmancloud.cnpg.io cnpg-backups -o jsonpath='{.spec.configuration.destinationPath}' 2>/dev/null)" = "s3://$BACKUP_BUCKET/" ]
check "deployment $CNPG_NAMESPACE/$BARMAN_CLOUD_RELEASE (Barman Cloud plugin) is available" \
  quiet kctl -n "$CNPG_NAMESPACE" rollout status "deployment/$BARMAN_CLOUD_RELEASE" --timeout=60s
check "deployment $OBJECT_STORE_NAMESPACE/$OBJECT_STORE_DEPLOYMENT (object store) is available" \
  quiet kctl -n "$OBJECT_STORE_NAMESPACE" rollout status "deployment/$OBJECT_STORE_DEPLOYMENT" --timeout=60s
backup_phases() { # <cluster>: the phase of each of its Backups
  kctl -n "$K8S_NAMESPACE" get backups.postgresql.cnpg.io \
    -o jsonpath="{range .items[?(@.spec.cluster.name=='$1')]}{.status.phase}{'\n'}{end}" 2>/dev/null
}
backup_timeout="${BACKUP_SMOKE_TIMEOUT:-300}"
for cluster in "${PG_CLUSTERS[@]}"; do
  check "ScheduledBackup $cluster-nightly uses the Barman Cloud plugin" [ "$(kctl -n "$K8S_NAMESPACE" get \
    scheduledbackups.postgresql.cnpg.io "$cluster-nightly" -o jsonpath='{.spec.cluster.name} {.spec.method}' 2>/dev/null)" = "$cluster plugin" ]
  deadline=$((SECONDS + backup_timeout))
  archiving="" completed=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    archiving="$(COND=ContinuousArchiving cond -n "$K8S_NAMESPACE" cluster.postgresql.cnpg.io "$cluster")"
    completed="$(backup_phases "$cluster" | grep -cx completed || true)"
    if is_true "$archiving" && [ "${completed:-0}" -gt 0 ]; then break; fi
    sleep 5
  done
  check "$cluster archives its WAL to the object store (ContinuousArchiving)" is_true "$archiving"
  check "$cluster has a completed base backup from its ScheduledBackup" [ "${completed:-0}" -gt 0 ]
done

restore_cluster=polaris-db-restore
backup_token="k11-$(hex 8)"
backup_name="polaris-db-k11-$(hex 4)"
pg_primary() { kctl -n "$K8S_NAMESPACE" get cluster.postgresql.cnpg.io polaris-db -o jsonpath='{.status.currentPrimary}' 2>/dev/null; }
pg_exec() { # <pod> <sql...>: psql as the local superuser inside a PostgreSQL pod (no network involved)
  local pod="$1"
  shift
  local args=()
  for sql in "$@"; do args+=(-c "$sql"); done
  kctl -n "$K8S_NAMESPACE" exec "$pod" -c postgres -- psql -d polaris -v ON_ERROR_STOP=1 -qAt "${args[@]}" 2>&1
}
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup_k11() {
  kctl -n "$K8S_NAMESPACE" delete cluster.postgresql.cnpg.io "$restore_cluster" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kctl -n "$K8S_NAMESPACE" delete backup.postgresql.cnpg.io "$backup_name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
on_cleanup cleanup_k11
# A drain interrupted by a failure must not leave the worker cordoned.
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup_k11_uncordon() { [ -z "${drained_node:-}" ] || kctl uncordon "$drained_node" >/dev/null 2>&1 || true; }
on_cleanup cleanup_k11_uncordon
if out="$(pg_exec "$(pg_primary)" 'CREATE SCHEMA IF NOT EXISTS k11_smoke' \
  'CREATE TABLE IF NOT EXISTS k11_smoke.rows (token text PRIMARY KEY)' \
  "INSERT INTO k11_smoke.rows VALUES ('$backup_token') RETURNING 'written', token")" &&
  grep -qx "written|$backup_token" <<<"$out"; then
  pass "a row was written to polaris-db before the backup"
else
  fail "could not write the backup row to polaris-db: $(tail -3 <<<"$out")"
fi
kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: $backup_name
spec:
  cluster:
    name: polaris-db
  method: plugin
  pluginConfiguration:
    name: barman-cloud.cloudnative-pg.io
EOF
deadline=$((SECONDS + backup_timeout))
phase=""
while [ "$SECONDS" -lt "$deadline" ]; do
  phase="$(kctl -n "$K8S_NAMESPACE" get backup.postgresql.cnpg.io "$backup_name" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  case "$phase" in completed | failed) break ;; esac
  sleep 5
done
check "an on-demand backup of polaris-db ($backup_name) completed (phase '$phase')" [ "$phase" = completed ]

# The restored Cluster: one instance, the same image, bootstrapped from polaris-db's backups and WAL in the object
# store (recovery to the end of the archived WAL). Its owner role and database come from the backup.
kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: $restore_cluster
  labels:
    app.kubernetes.io/name: $restore_cluster
spec:
  instances: 1
  imageName: $pg_image
  enablePDB: false
  bootstrap:
    recovery:
      source: polaris-db
      database: polaris
      owner: polaris
  externalClusters:
    - name: polaris-db
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters:
          barmanObjectName: cnpg-backups
          serverName: polaris-db
  storage:
    size: 2Gi
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      memory: 1Gi
EOF
if quiet kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "cluster.postgresql.cnpg.io/$restore_cluster" \
  --timeout="${RESTORE_SMOKE_TIMEOUT:-600}s"; then
  pass "Cluster $restore_cluster bootstrapped from the object store (recovery) is ready"
  restored="$(pg_exec "$restore_cluster-1" "SELECT 'restored', token FROM k11_smoke.rows WHERE token = '$backup_token'")"
  check "the restored Cluster contains the row written before the backup" grep -qx "restored|$backup_token" <<<"$restored"
else
  fail "Cluster $restore_cluster did not become ready from the backup: $(kctl -n "$K8S_NAMESPACE" get \
    cluster.postgresql.cnpg.io "$restore_cluster" -o jsonpath='{.status.phase}: {.status.phaseReason}' 2>&1)"
fi
cleanup_k11
pg_exec "$(pg_primary)" 'DROP SCHEMA IF EXISTS k11_smoke CASCADE' >/dev/null || true

# Node drain (TR-K5): api-test.js runs open-ended while the kind worker, which holds one replica of polaris and of the
# assistant (topology spread), is drained. The Eviction API honours the PodDisruptionBudgets, the evicted replicas
# reschedule onto the control plane (the cordoned worker no longer counts for the spread), and each evicted pod drains
# like a rolling restart (readiness, preStop, graceful shutdown). No request may get a 5xx or lose its connection.
worker="$(kctl get nodes -l "$STATELESS_POOL_LABEL" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
on_worker() { # <app>: its pods on the worker
  kctl -n "$K8S_NAMESPACE" get pods -l "app.kubernetes.io/name=$1" --field-selector "spec.nodeName=$worker" \
    -o jsonpath='{.items[*].metadata.name}' 2>/dev/null
}
if [ -z "$worker" ]; then
  fail "no kind worker labelled $STATELESS_POOL_LABEL to drain: recreate the cluster from deploy/k8s/kind/cluster.yaml (make k8s-down k8s-up)"
else
  evicted="$(on_worker "$POLARIS")"
  check "the worker $worker runs a polaris replica (topology spread): ${evicted:-none}" [ -n "$evicted" ]
  register_probes k11-drain
  start_k6 k11-drain "--no-thresholds -e DURATION=20m"
  if kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready pod/k11-drain --timeout=120s >/dev/null 2>&1; then
    sleep 10
    log "draining $worker under load (polaris pods there: $evicted)"
    drained_node="$worker"
    if drain_out="$(kctl drain "$worker" --ignore-daemonsets --delete-emptydir-data --timeout="${DRAIN_SMOKE_TIMEOUT:-300}s" 2>&1)"; then
      pass "kubectl drain $worker evicted its pods within the PodDisruptionBudgets"
    else
      fail "kubectl drain $worker failed: $(tail -5 <<<"$drain_out")"
    fi
    log "drain:"$'\n'"$(grep -E "evicting|evicted|drained|error" <<<"$drain_out" || true)"
    for app in "$POLARIS" "$ASSISTANT"; do
      check "deployment $app is back to all replicas ready, off the drained worker" quiet kctl -n "$K8S_NAMESPACE" \
        rollout status "deployment/$app" --timeout="${ROLLOUT_SMOKE_TIMEOUT:-600}s"
      check "no $app pod is left on the drained worker" [ -z "$(on_worker "$app")" ]
    done
    sleep 15
    quiet kctl -n "$K8S_NAMESPACE" exec k11-drain -- touch /tmp/stop || true
    out="$(wait_k6 k11-drain 120 || true)"
    log "api-test.js during the drain:"$'\n'"$(grep -E "checks|http_reqs|✗|server-errors" <<<"$out" || true)"
    check "api-test.js ran during the drain" grep -Eq "http_reqs[ .:]+[1-9]" <<<"$out"
    check "no request got a 5xx or lost its connection while $worker was drained" grep -qx "server-errors 0" <<<"$out"
    quiet kctl uncordon "$worker" && drained_node=""
  else
    fail "the k6 pod for the drain did not start: $(kctl -n "$K8S_NAMESPACE" describe pod k11-drain 2>&1 | tail -5)"
  fi
fi
