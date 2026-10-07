#!/usr/bin/env bash
# Smoke test for the cluster brought up by `make k8s-up`. Each slice adds its checks below.
# Runs every check, then exits non-zero if any failed.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind kubectl
cluster_exists && ensure_kubeconfig

failures=0
pass() { log "PASS: $1"; }
fail() {
  log "FAIL: $1"
  failures=$((failures + 1))
}

ns_label() { kctl get namespace "$K8S_NAMESPACE" -o jsonpath="{.metadata.labels.pod-security\.kubernetes\.io/$1}"; }

# --- K1: namespace with Pod Security restricted ---------------------------------------------------------------
if kctl get namespace "$K8S_NAMESPACE" >/dev/null 2>&1; then
  pass "namespace $K8S_NAMESPACE exists"
  for mode in enforce warn audit; do
    level="$(ns_label "$mode")"
    if [ "$level" = restricted ]; then
      pass "namespace $K8S_NAMESPACE has Pod Security $mode=restricted"
    else
      fail "namespace $K8S_NAMESPACE has Pod Security $mode='$level', expected restricted"
    fi
  done
  # Admission really enforces it: a privileged pod is rejected (server-side dry run, nothing is created).
  if out="$(kctl -n "$K8S_NAMESPACE" run psa-smoke --image=busybox --restart=Never --dry-run=server \
    --overrides='{"spec":{"containers":[{"name":"psa-smoke","image":"busybox","securityContext":{"privileged":true}}]}}' 2>&1)"; then
    fail "a privileged pod was admitted to $K8S_NAMESPACE"
  elif [[ "$out" == *"violates PodSecurity"* ]]; then
    pass "Pod Security admission rejects a privileged pod in $K8S_NAMESPACE"
  else
    fail "privileged pod dry run failed for another reason: $out"
  fi
else
  fail "namespace $K8S_NAMESPACE does not exist"
fi

# --- K3: edge (TLS, Gateway, DNS) -----------------------------------------------------------------------------
cond() { kctl get "$@" -o jsonpath="{.status.conditions[?(@.type=='${COND:-Ready}')].status}" 2>/dev/null; }

for deploy in cert-manager/cert-manager cert-manager/trust-manager nginx-gateway/nginx-gateway-fabric; do
  if kctl -n "${deploy%%/*}" rollout status "deployment/${deploy#*/}" --timeout=60s >/dev/null 2>&1; then
    pass "deployment $deploy is available"
  else
    fail "deployment $deploy is not available"
  fi
done

# check <description> <command...>: pass when the command succeeds.
check() {
  local what="$1"
  shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}
is_true() { [ "$1" = True ]; }

check "ClusterIssuer polaris-ca is ready" is_true "$(cond clusterissuer polaris-ca)"
check "certificate polaris-edge-tls is ready" is_true "$(cond -n "$K8S_NAMESPACE" certificate polaris-edge-tls)"
check "Gateway $GATEWAY_NAME is programmed" is_true "$(COND=Programmed cond -n "$K8S_NAMESPACE" gateway "$GATEWAY_NAME")"

# The trust bundle reached the namespace as PEM and as a JKS store (JKS magic number 0xFEEDFEED, base64 /u3+7Q).
pem="$(kctl -n "$K8S_NAMESPACE" get configmap "$CA_BUNDLE" -o jsonpath='{.data.ca\.crt}' 2>/dev/null || true)"
jks="$(kctl -n "$K8S_NAMESPACE" get configmap "$CA_BUNDLE" -o jsonpath='{.binaryData.truststore\.jks}' 2>/dev/null || true)"
check "configmap $CA_BUNDLE has the PEM bundle ca.crt" grep -q "BEGIN CERTIFICATE" <<<"$pem"
check "configmap $CA_BUNDLE has the JKS trust store truststore.jks" grep -q "^/u3+7Q" <<<"$jks"

# From a pod that meets Pod Security `restricted`, with the bundle mounted: each public host resolves to the Gateway
# Service (CoreDNS rewrite), its certificate verifies against the bundle, and a path no HTTPRoute serves answers 404
# (the placeholder: NGINX Gateway Fabric serves every HTTPS listener host and answers 404 until a route matches; the
# 404 stays true once K7-K12 attach app routes, whose apps 404 on this path too). Plain HTTP redirects to HTTPS. The probe prints one line per request: <url> <http_code> <remote_ip> <redirect_url>.
# Last, it sends one request with a fresh W3C traceparent through a traced route (K4, checked below), and prints
# `traced <url> <http_code>`.
hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }
trace_id="$(hex 16)"
parent_span_id="$(hex 8)"
traceparent="00-$trace_id-$parent_span_id-01"
traced_url="http://polaris.local/k4-trace-smoke/$trace_id"
gateway_ip="$(kctl -n "$K8S_NAMESPACE" get service "$GATEWAY_SERVICE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
probe=edge-smoke
kctl -n "$K8S_NAMESPACE" delete pod "$probe" --ignore-not-found --wait=true >/dev/null
trap 'kctl -n "$K8S_NAMESPACE" delete pod "$probe" --ignore-not-found --wait=false >/dev/null 2>&1 || true' EXIT
urls=()
unrouted=/k3-edge-smoke/unrouted
for host in "${EDGE_HOSTS[@]}"; do urls+=("https://$host$unrouted"); done
urls+=("http://polaris.local/some/path")
kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $probe
  labels:
    app.kubernetes.io/name: $probe
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: curl
      image: $SMOKE_CURL_IMAGE
      command: ["sh", "-c"]
      args:
        - |
          for url in ${urls[*]}; do
            curl -sS -o /dev/null --max-time 10 --cacert /etc/polaris-trust/ca.crt \\
              -w "\$url %{http_code} %{remote_ip} %{redirect_url}\\n" "\$url" || echo "\$url curl-exit-\$?"
          done
          curl -sS -o /dev/null --max-time 10 -H "traceparent: $traceparent" \\
            -w "traced $traced_url %{http_code}\\n" "$traced_url" || echo "traced $traced_url curl-exit-\$?"
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      volumeMounts:
        - name: trust
          mountPath: /etc/polaris-trust
          readOnly: true
  volumes:
    - name: trust
      configMap:
        name: $CA_BUNDLE
EOF
if kctl -n "$K8S_NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$probe" --timeout=180s >/dev/null; then
  results="$(kctl -n "$K8S_NAMESPACE" logs "$probe")"
  log "edge probe results:"$'\n'"$results"
  for host in "${EDGE_HOSTS[@]}"; do
    read -r _ code ip _ <<<"$(grep "^https://$host$unrouted " <<<"$results" || true)"
    if [ -n "$gateway_ip" ] && [ "$ip" = "$gateway_ip" ]; then
      pass "https://$host resolves to the Gateway Service $GATEWAY_SERVICE ($gateway_ip) from a pod"
    else
      fail "https://$host resolved to '$ip', expected the Gateway Service $GATEWAY_SERVICE ('$gateway_ip')"
    fi
    if [ "$code" = 404 ]; then
      pass "https://$host certificate verifies against $CA_BUNDLE and an unrouted path returns 404"
    else
      fail "https://$host$unrouted returned '$code' (curl verifies the certificate against $CA_BUNDLE), expected 404"
    fi
  done
  read -r _ code _ location <<<"$(grep "^http://polaris.local/" <<<"$results" || true)"
  if [ "$code" = 301 ] && [ "$location" = "https://polaris.local/some/path" ]; then
    pass "http://polaris.local redirects to https with 301"
  else
    fail "http://polaris.local/some/path returned '$code' to '$location', expected 301 to https://polaris.local/some/path"
  fi
  read -r _ _ traced_code <<<"$(grep "^traced " <<<"$results" || true)"
  check "the traced request $traced_url went through the Gateway (301)" [ "$traced_code" = 301 ]
else
  fail "edge probe pod did not succeed: $(kctl -n "$K8S_NAMESPACE" logs "$probe" 2>&1 | tail -5)"
fi

# --- K4: observability pipeline -------------------------------------------------------------------------------
quiet() { "$@" >/dev/null 2>&1; }
check "deployment $K8S_NAMESPACE/$OTEL_COLLECTOR is available" \
  quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$OTEL_COLLECTOR" --timeout=60s
check "daemonset $OTEL_AGENT_NAMESPACE/$OTEL_AGENT is ready" \
  quiet kctl -n "$OTEL_AGENT_NAMESPACE" rollout status "daemonset/$OTEL_AGENT" --timeout=60s
check "Service $OTEL_COLLECTOR exposes OTLP gRPC 4317 and OTLP HTTP 4318" [ \
  "$(kctl -n "$K8S_NAMESPACE" get service "$OTEL_COLLECTOR" -o jsonpath='{.spec.ports[*].port}' 2>/dev/null)" = "4317 4318" ]
check "ObservabilityPolicy edge-tracing is accepted" is_true "$(kctl -n "$K8S_NAMESPACE" get observabilitypolicy \
  edge-tracing -o jsonpath="{.status.ancestors[0].conditions[?(@.type=='Accepted')].status}" 2>/dev/null)"

# The traced request above must reach the Collector's debug exporter twice, each carrying the Kubernetes resource
# attributes of the Gateway's data-plane pod (TR-K9):
#   - as a span of the trace in the request's traceparent, whose parent is the request's span: the Gateway continues
#     the incoming W3C trace context (OTLP from nginx, attributes from k8s_attributes),
#   - as the JSON access log line with that trace_id (stdout -> log agent -> Collector).
# The debug exporter prints each batch as `ResourceSpans #n` / `ResourceLog #n` blocks; telemetry_block prints the
# blocks of one kind that contain the trace ID. nginx exports spans every 5s and both hops batch for 2s, so the checks
# poll the Collector's log for up to OTEL_SMOKE_TIMEOUT seconds.
telemetry_block() {
  kctl -n "$K8S_NAMESPACE" logs "deployment/$OTEL_COLLECTOR" --since=15m 2>/dev/null |
    awk -v kind="$1" -v tid="$trace_id" '
      function flush() { if (block ~ tid) printf "%s", block; block = "" }
      /Resource(Spans|Log) #[0-9]+$/ { flush(); keep = ($0 ~ kind " #") }
      keep { block = block $0 "\n" }
      END { flush() }'
}
has_k8s_attributes() {
  grep -q "k8s.namespace.name: Str($K8S_NAMESPACE)" <<<"$1" &&
    grep -q "k8s.pod.name: Str($GATEWAY_SERVICE-" <<<"$1" &&
    grep -q "k8s.deployment.name: Str($GATEWAY_SERVICE)" <<<"$1"
}
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90}))
span="" access_log=""
while [ "$SECONDS" -lt "$deadline" ]; do
  [ -n "$span" ] || span="$(telemetry_block ResourceSpans)"
  [ -n "$access_log" ] || access_log="$(telemetry_block ResourceLog)"
  if [ -n "$span" ] && [ -n "$access_log" ]; then break; fi
  sleep 3
done
if [ -n "$span" ]; then
  pass "the Collector received the Gateway span of trace $trace_id"
  check "the Gateway span continues the incoming traceparent (parent span $parent_span_id)" \
    grep -Eq "Parent ID +: $parent_span_id" <<<"$span"
  check "the Gateway span carries k8s.namespace.name, k8s.pod.name and k8s.deployment.name of $GATEWAY_SERVICE" \
    has_k8s_attributes "$span"
  check "the Gateway span has service.name nginx-gateway" grep -q "service.name: Str(nginx-gateway)" <<<"$span"
else
  fail "no span of trace $trace_id reached the Collector's debug exporter within ${OTEL_SMOKE_TIMEOUT:-90}s"
fi
if [ -n "$access_log" ]; then
  pass "the Collector received the Gateway access log line of trace $trace_id from stdout"
  check "the access log record carries trace_id $trace_id" grep -Eq "Trace ID *: *$trace_id" <<<"$access_log"
  check "the access log record carries k8s.namespace.name, k8s.pod.name and k8s.deployment.name of $GATEWAY_SERVICE" \
    has_k8s_attributes "$access_log"
else
  fail "no access log line of trace $trace_id reached the Collector's debug exporter within ${OTEL_SMOKE_TIMEOUT:-90}s"
fi

# --- K5: data stores (PostgreSQL, Redis) ----------------------------------------------------------------------
# Every check goes through the interfaces the apps use from K7 on: the CNPG `-app` Secrets and `-rw` Services, and the
# `redis` Service, from probe pods that meet Pod Security `restricted`. The PostgreSQL and Redis pods themselves run in
# the namespace that enforces `restricted`, so their being ready shows they pass it.
check "deployment $CNPG_NAMESPACE/$CNPG_RELEASE (CloudNativePG operator) is available" \
  quiet kctl -n "$CNPG_NAMESPACE" rollout status "deployment/$CNPG_RELEASE" --timeout=60s
for cluster in "${PG_CLUSTERS[@]}"; do
  check "PostgreSQL Cluster $cluster is ready" is_true "$(cond -n "$K8S_NAMESPACE" cluster.postgresql.cnpg.io "$cluster")"
  owner="${cluster%-db}"
  check "Secret $cluster-app holds the operator-generated credentials of owner $owner" [ "$(kctl -n "$K8S_NAMESPACE" \
    get secret "$cluster-app" -o jsonpath='{.data.username}' 2>/dev/null | base64 -d 2>/dev/null)" = "$owner" ]
done
check "deployment $REDIS is available" quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$REDIS" --timeout=60s

# run_probe <name> <image> <uid> <script> [<env>]: runs <script> with `sh -c` in a pod that meets Pod Security
# `restricted` (as <uid>, read-only root filesystem, no capabilities, no service account token) and prints its output.
# <env> is an optional container `env:` list, already indented. Returns non-zero when the pod doesn't succeed in 180s.
k5_probes=(pg-smoke-write pg-smoke-read redis-smoke)
trap 'kctl -n "$K8S_NAMESPACE" delete pod "$probe" "${k5_probes[@]}" --ignore-not-found --wait=false >/dev/null 2>&1 || true' EXIT
run_probe() {
  local name="$1" image="$2" uid="$3" script="$4" env="${5:-}" rc=0 nl=$'\n'
  local body="          ${script//$nl/$nl          }" # the script, indented into the YAML block scalar below
  kctl -n "$K8S_NAMESPACE" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF || return
apiVersion: v1
kind: Pod
metadata:
  name: $name
  labels:
    app.kubernetes.io/name: $name
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  enableServiceLinks: false
  securityContext:
    runAsNonRoot: true
    runAsUser: $uid
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: $image
      command: ["sh", "-c"]
      args:
        - |
$body
${env:+      env:
$env}
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
EOF
  local phase="" deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    phase="$(kctl -n "$K8S_NAMESPACE" get pod "$name" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in Succeeded | Failed) break ;; esac
    sleep 2
  done
  [ "$phase" = Succeeded ] || rc=1
  kctl -n "$K8S_NAMESPACE" logs "$name" 2>&1 || true
  kctl -n "$K8S_NAMESPACE" delete pod "$name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  return "$rc"
}

# PostgreSQL: a probe connects to both Clusters' `-rw` Services with the owners' credentials from the `-app` Secrets
# and prints `<user> <database> <collation> <server version>`, then writes a row with a fresh token to polaris-db. The
# row is in its own schema, dropped at the end, so the `public` schema that Flyway will own (K8) stays empty.
pg_image="$(kctl -n "$K8S_NAMESPACE" get cluster.postgresql.cnpg.io polaris-db -o jsonpath='{.spec.imageName}' 2>/dev/null || true)"
pg_env="$(for cluster in "${PG_CLUSTERS[@]}"; do
  var="$(tr 'a-z-' 'A-Z_' <<<"$cluster")_URI"
  printf '        - name: %s\n          valueFrom:\n            secretKeyRef:\n              name: %s-app\n              key: uri\n' \
    "$var" "$cluster"
done)"
pg_token="k5-$(hex 8)"
sql="SELECT current_user, current_database(), datcollate, current_setting('server_version')
  FROM pg_database WHERE datname = current_database()"
# wait_for <uri-variable>: the probe first waits (up to 60s) for the `-rw` Service to accept connections, which can lag
# a just-restarted primary's readiness by a few seconds.
wait_for() {
  # shellcheck disable=SC2016 # expanded by the probe's shell, not here
  printf 'for i in $(seq 30); do pg_isready -q -d "$%s" && break; sleep 2; done\n' "$1"
}
if out="$(run_probe pg-smoke-write "$pg_image" 26 "set -e
$(wait_for KEYCLOAK_DB_URI)
$(wait_for POLARIS_DB_URI)
psql \"\$KEYCLOAK_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' -c \"$sql\"
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' -c \"$sql\" \\
  -c 'CREATE SCHEMA IF NOT EXISTS k5_smoke' \\
  -c 'CREATE TABLE IF NOT EXISTS k5_smoke.rows (token text PRIMARY KEY)' \\
  -c \"INSERT INTO k5_smoke.rows VALUES ('$pg_token') RETURNING 'written', token\"" "$pg_env")"; then
  log "PostgreSQL probe results:"$'\n'"$out"
  for cluster in "${PG_CLUSTERS[@]}"; do
    owner="${cluster%-db}"
    check "$cluster answers on $cluster-rw as owner $owner of database $owner, collation en_US.utf8, PostgreSQL 16" \
      grep -Eq "^$owner $owner en_US.utf8 16\." <<<"$out"
  done
  check "a row was written to polaris-db" grep -qx "written $pg_token" <<<"$out"
else
  fail "PostgreSQL write probe did not succeed: $(tail -5 <<<"$out")"
fi

# Delete the primary's pod. CNPG recreates it on the same PersistentVolumeClaim (one instance locally), and the row
# must still be there once the Cluster is ready again.
primary="$(kctl -n "$K8S_NAMESPACE" get cluster.postgresql.cnpg.io polaris-db -o jsonpath='{.status.currentPrimary}' 2>/dev/null || true)"
old_uid="$(kctl -n "$K8S_NAMESPACE" get pod "$primary" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
pg_timeout="${PG_SMOKE_TIMEOUT:-180}"
if [ -n "$primary" ] && quiet kctl -n "$K8S_NAMESPACE" delete pod "$primary" --wait=true --timeout=60s; then
  log "deleted the polaris-db primary pod $primary, waiting up to ${pg_timeout}s for it to come back"
  deadline=$((SECONDS + pg_timeout))
  new_uid=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    new_uid="$(kctl -n "$K8S_NAMESPACE" get pod "$primary" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
    if [ -n "$new_uid" ] && [ "$new_uid" != "$old_uid" ]; then break; fi
    sleep 2
  done
  remaining=$((deadline - SECONDS > 1 ? deadline - SECONDS : 1))
  if [ -n "$new_uid" ] && [ "$new_uid" != "$old_uid" ] &&
    quiet kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "pod/$primary" --timeout="${remaining}s" &&
    quiet kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready cluster.postgresql.cnpg.io/polaris-db --timeout=60s; then
    pass "the polaris-db primary pod $primary was recreated and the Cluster is ready again"
  else
    fail "the polaris-db primary pod $primary did not come back ready within ${pg_timeout}s"
  fi
else
  fail "could not delete the polaris-db primary pod '$primary'"
fi
if out="$(run_probe pg-smoke-read "$pg_image" 26 "set -e
$(wait_for POLARIS_DB_URI)
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' \\
  -c \"SELECT 'read', token FROM k5_smoke.rows WHERE token = '$pg_token'\" -c 'DROP SCHEMA k5_smoke CASCADE'" "$pg_env")"; then
  check "the row written before the primary pod was deleted is still in polaris-db" grep -qx "read $pg_token" <<<"$out"
else
  fail "PostgreSQL read probe did not succeed: $(tail -5 <<<"$out")"
fi

# Redis: `redis-cli ping` answers through the Service, and persistence is off as in Compose.
redis_image="$(kctl -n "$K8S_NAMESPACE" get deployment "$REDIS" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
if out="$(run_probe redis-smoke "$redis_image" 999 "set -e
redis-cli -h $REDIS -p 6379 ping
echo \"save=\$(redis-cli -h $REDIS -p 6379 config get save | tail -n 1)\"
echo \"appendonly=\$(redis-cli -h $REDIS -p 6379 config get appendonly | tail -n 1)\"")"; then
  log "Redis probe results:"$'\n'"$out"
  check "redis-cli ping through Service $REDIS answers PONG" grep -qx PONG <<<"$out"
  persistence_off() { grep -qx "save=" <<<"$1" && grep -qx "appendonly=no" <<<"$1"; }
  check "Redis runs with persistence off (save \"\", appendonly no), as in Compose" persistence_off "$out"
else
  fail "Redis probe did not succeed: $(tail -5 <<<"$out")"
fi

if [ "$failures" -gt 0 ]; then
  log "$failures smoke check(s) failed"
  exit 1
fi
log "all smoke checks passed"
