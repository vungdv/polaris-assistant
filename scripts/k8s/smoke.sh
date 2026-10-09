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
# Service (CoreDNS rewrite), its certificate verifies against the bundle, and a path no app serves answers 404
# (NGINX Gateway Fabric serves every HTTPS listener host and answers 404 until a route matches; Keycloak 404s on this
# path too). polaris.local is the exception since K8: its `/` route sends every path to polaris, which answers an
# unauthenticated request with 401 before routing it. Plain HTTP redirects to HTTPS. The probe prints one line per
# request: <url> <http_code> <remote_ip> <redirect_url>.
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
    expected=404
    [ "$host" != polaris.local ] || expected=401
    if [ "$code" = "$expected" ]; then
      pass "https://$host certificate verifies against $CA_BUNDLE and an unrouted path returns $expected"
    else
      fail "https://$host$unrouted returned '$code' (curl verifies the certificate against $CA_BUNDLE), expected $expected"
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
telemetry_block() { # <ResourceSpans|ResourceLog> [<trace id>, default: $trace_id]
  kctl -n "$K8S_NAMESPACE" logs "deployment/$OTEL_COLLECTOR" --since=15m 2>/dev/null |
    awk -v kind="$1" -v tid="${2:-$trace_id}" '
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
# `restricted` (as <uid>, read-only root filesystem, no capabilities, no service account token), with the trust bundle
# $CA_BUNDLE at /etc/polaris-trust, and prints its output.
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
      volumeMounts:
        - name: trust
          mountPath: /etc/polaris-trust
          readOnly: true
  volumes:
    - name: trust
      configMap:
        name: $CA_BUNDLE
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

# Redis: `redis-cli ping` answers through the Service, persistence is off and memory is bounded, as in Compose.
redis_image="$(kctl -n "$K8S_NAMESPACE" get deployment "$REDIS" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
if out="$(run_probe redis-smoke "$redis_image" 999 "set -e
redis-cli -h $REDIS -p 6379 ping
echo \"save=\$(redis-cli -h $REDIS -p 6379 config get save | tail -n 1)\"
echo \"appendonly=\$(redis-cli -h $REDIS -p 6379 config get appendonly | tail -n 1)\"
echo \"maxmemory=\$(redis-cli -h $REDIS -p 6379 config get maxmemory | tail -n 1)\"
echo \"maxmemory-policy=\$(redis-cli -h $REDIS -p 6379 config get maxmemory-policy | tail -n 1)\"")"; then
  log "Redis probe results:"$'\n'"$out"
  check "redis-cli ping through Service $REDIS answers PONG" grep -qx PONG <<<"$out"
  persistence_off() { grep -qx "save=" <<<"$1" && grep -qx "appendonly=no" <<<"$1"; }
  check "Redis runs with persistence off (save \"\", appendonly no), as in Compose" persistence_off "$out"
  # 200mb = 209715200 bytes, below the 256Mi container limit; evicts only keys with a TTL (the product cache).
  memory_bounded() { grep -qx "maxmemory=209715200" <<<"$1" && grep -qx "maxmemory-policy=volatile-lru" <<<"$1"; }
  check "Redis memory is bounded (maxmemory 200mb, volatile-lru), as in Compose" memory_bounded "$out"
else
  fail "Redis probe did not succeed: $(tail -5 <<<"$out")"
fi

# --- K6: Kafka ------------------------------------------------------------------------------------------------
# The Kafka CLI runs inside a Kafka node (`kubectl exec`, as `make k8s-kafka-*` does) and talks to the cluster through
# the bootstrap Service the apps use from K8 on. The Kafka pods run in the namespace that enforces `restricted`, so
# their being ready shows they pass it.
check "deployment $STRIMZI_NAMESPACE/strimzi-cluster-operator (Strimzi operator) is available" \
  quiet kctl -n "$STRIMZI_NAMESPACE" rollout status "deployment/strimzi-cluster-operator" --timeout=60s
check "Kafka $KAFKA_CLUSTER is ready" is_true "$(cond -n "$K8S_NAMESPACE" kafka.kafka.strimzi.io "$KAFKA_CLUSTER")"
check "KafkaNodePool $KAFKA_NODE_POOL runs nodes 1, 2 and 3 as controller and broker" [ "$(kctl -n "$K8S_NAMESPACE" \
  get kafkanodepool.kafka.strimzi.io "$KAFKA_NODE_POOL" -o jsonpath='{.status.nodeIds} {.status.roles}' 2>/dev/null)" \
  = '[1,2,3] ["controller","broker"]' ]
check "the Topic and User Operators are not deployed (D10)" [ -z "$(kctl -n "$K8S_NAMESPACE" get deployments \
  -l "strimzi.io/cluster=$KAFKA_CLUSTER" -o name 2>/dev/null)" ]

# kafka_cli <node id> <tool> [args...]: runs /opt/kafka/bin/<tool>.sh against the bootstrap Service in that node's
# container, with a small heap so it fits next to the broker. stdin is passed to the tool.
kafka_cli() {
  local node="$1" tool="$2"
  shift 2
  kctl -n "$K8S_NAMESPACE" exec -i "$(kafka_pod "$node")" -c kafka -- env KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" \
    "/opt/kafka/bin/$tool.sh" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"
}
# end_offset_sum <node> <topic>: the sum of the topic's end offsets, i.e. the number of records written to it.
end_offset_sum() {
  kafka_cli "$1" kafka-get-offsets --topic "$2" </dev/null 2>/dev/null |
    awk -F: 'NF == 3 { sum += $3; n++ } END { if (n) print sum }'
}
# under_replicated <node> <topic>: the number of the topic's partitions whose ISR is short of their replicas.
under_replicated() {
  kafka_cli "$1" kafka-topics --describe --under-replicated-partitions --topic "$2" </dev/null 2>/dev/null |
    grep -c "Partition:" || true
}
# produce <node> <topic> <first> <last>: writes the records <token>-<first>..<token>-<last> with acks=all.
produce() {
  local i
  for ((i = $3; i <= $4; i++)); do printf '%s-%d\n' "$kafka_token" "$i"; done |
    kafka_cli "$1" kafka-console-producer --topic "$2" --producer-property acks=all \
      --producer-property enable.idempotence=true >/dev/null
}

# The cluster runs Compose's broker configuration (TR-K6), as the brokers report it.
if broker_config="$(kafka_cli 1 kafka-configs --describe --all --entity-type brokers --entity-name 1 </dev/null 2>&1)"; then
  for setting in auto.create.topics.enable=false default.replication.factor=3 min.insync.replicas=2 \
    offsets.topic.replication.factor=3 transaction.state.log.replication.factor=3 transaction.state.log.min.isr=2; do
    check "broker 1 runs with $setting" grep -Eq "^ *$setting( |$)" <<<"$broker_config"
  done
else
  fail "could not read the broker configuration: $(tail -3 <<<"$broker_config")"
fi
check "the KRaft controller quorum has 3 voters" [ "$(kafka_cli 1 kafka-metadata-quorum describe --status </dev/null \
  2>/dev/null | sed -n 's/^CurrentVoters: *//p' | grep -o '"id":' | wc -l | tr -d ' ')" = 3 ]

# Losing one node loses no acks=all write (TR-K6): a topic with RF 3 and min ISR 2 gets a first batch of records, the
# node that leads its partition 0 is deleted, a second acks=all batch still succeeds while that node is down, and once
# it is back in sync every record of both batches is there exactly once. The topic is deleted at the end (and on exit).
kafka_token="$(hex 4)"
kafka_topic="k6-smoke-$kafka_token"
kafka_records=100
kafka_timeout="${KAFKA_SMOKE_TIMEOUT:-240}"
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup() {
  kctl -n "$K8S_NAMESPACE" delete pod "$probe" "${k5_probes[@]}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  if [ -n "${kafka_topic_created:-}" ]; then
    for node in 1 2 3; do
      kafka_cli "$node" kafka-topics --delete --if-exists --topic "$kafka_topic" </dev/null >/dev/null 2>&1 && break
    done
  fi
}
trap cleanup EXIT
if kafka_cli 1 kafka-topics --create --topic "$kafka_topic" --partitions 3 --replication-factor 3 \
  --config min.insync.replicas=2 </dev/null >/dev/null; then
  kafka_topic_created=1
  description="$(kafka_cli 1 kafka-topics --describe --topic "$kafka_topic" </dev/null 2>/dev/null)"
  log "test topic:"$'\n'"$description"
  check "test topic $kafka_topic has 3 partitions, replication factor 3 and min.insync.replicas=2" \
    grep -Eq "PartitionCount: 3.*ReplicationFactor: 3.*min.insync.replicas=2" <<<"$description"
  victim="$(sed -nE 's/.*Partition: 0[[:space:]]+Leader: ([0-9]+).*/\1/p' <<<"$description")"
  client=1
  [ "$victim" != 1 ] || client=2

  produce "$client" "$kafka_topic" 1 $((kafka_records / 2)) || true
  check "the first $((kafka_records / 2)) records were written with acks=all" \
    [ "$(end_offset_sum "$client" "$kafka_topic")" = $((kafka_records / 2)) ]

  victim_pod="$(kafka_pod "$victim")"
  if [ -n "$victim" ] && quiet kctl -n "$K8S_NAMESPACE" delete pod "$victim_pod" --wait=true --timeout=120s; then
    log "deleted $victim_pod (leader of partition 0), producing from node $client while it is down"
    down_before="$(under_replicated "$client" "$kafka_topic")"
    produce "$client" "$kafka_topic" $((kafka_records / 2 + 1)) "$kafka_records" || true
    victim_ready="$(kctl -n "$K8S_NAMESPACE" get pod "$victim_pod" \
      -o jsonpath="{.status.conditions[?(@.type=='Ready')].status}" 2>/dev/null || true)"
    check "node $victim was out of every partition's ISR before the second batch (under-replicated: '$down_before' of 3)" \
      [ "$down_before" = 3 ]
    check "node $victim was still not ready after the second batch (Ready='$victim_ready')" [ "$victim_ready" != True ]
    check "the second $((kafka_records / 2)) records were written with acks=all while node $victim was down" \
      [ "$(end_offset_sum "$client" "$kafka_topic")" = "$kafka_records" ]

    # The pod comes back on its PersistentVolumeClaim and catches up: ready, and back in every partition's ISR.
    deadline=$((SECONDS + kafka_timeout))
    back=""
    while [ "$SECONDS" -lt "$deadline" ]; do
      if is_true "$(kctl -n "$K8S_NAMESPACE" get pod "$victim_pod" \
        -o jsonpath="{.status.conditions[?(@.type=='Ready')].status}" 2>/dev/null)" &&
        [ "$(under_replicated "$client" "$kafka_topic")" = 0 ]; then
        back=1
        break
      fi
      sleep 5
    done
    check "node $victim came back ready and in sync within ${kafka_timeout}s" [ -n "$back" ]
  else
    fail "could not delete the leader of partition 0 of $kafka_topic ('$victim_pod')"
  fi

  # Every record of both batches, read back from the returned node: the expected values exactly, none lost, none twice.
  read_node="${victim:-1}"
  records="$(kafka_cli "$read_node" kafka-console-consumer --topic "$kafka_topic" --from-beginning \
    --group "$kafka_topic" --max-messages "$kafka_records" --timeout-ms 30000 </dev/null 2>/dev/null | sort || true)"
  expected="$(for ((i = 1; i <= kafka_records; i++)); do printf '%s-%d\n' "$kafka_token" "$i"; done | sort)"
  check "all $kafka_records acks=all records are in $kafka_topic after node $victim returned (no data loss)" \
    [ "$records" = "$expected" ]
  check "$kafka_topic holds exactly $kafka_records records (end offsets)" \
    [ "$(end_offset_sum "$read_node" "$kafka_topic")" = "$kafka_records" ]

  # The reader's consumer group (named after the topic) goes with it.
  kafka_cli 1 kafka-consumer-groups --delete --group "$kafka_topic" </dev/null >/dev/null 2>&1 || true
  if kafka_cli 1 kafka-topics --delete --topic "$kafka_topic" </dev/null >/dev/null 2>&1; then
    kafka_topic_created=""
    pass "test topic $kafka_topic deleted"
  else
    fail "could not delete test topic $kafka_topic"
  fi
else
  fail "could not create test topic $kafka_topic"
fi

# --- K7: identity (Keycloak) ----------------------------------------------------------------------------------
# The operator, the server and the realm import are ready. The Keycloak pods run in the namespace that enforces
# `restricted`, so their being ready shows they pass it.
check "deployment $K8S_NAMESPACE/$KEYCLOAK_OPERATOR (Keycloak Operator) is available" \
  quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$KEYCLOAK_OPERATOR" --timeout=60s
check "Keycloak $KEYCLOAK is ready" is_true "$(cond -n "$K8S_NAMESPACE" keycloak.k8s.keycloak.org "$KEYCLOAK")"
for realm in "${KEYCLOAK_REALM_IMPORTS[@]}"; do
  check "KeycloakRealmImport $realm is done" \
    is_true "$(COND=Done cond -n "$K8S_NAMESPACE" keycloakrealmimport.k8s.keycloak.org "$realm")"
done

# From a `restricted` probe pod, through the CoreDNS rewrite and the Gateway, verifying TLS against the bundle, the
# same way the apps will (K8-K10):
#   issuer        the polaris realm's OIDC discovery document names the public issuer (TR-K8)
#   password      a password grant for the seeded `testuser` (public client polaris-app, direct access grants on)
#                 returns a token with that issuer: DEFAULT_PASSWORD was substituted into the KeycloakRealmImport
#   emulator      a client_credentials grant for polaris-fulfilment-emulator with POLARIS_FULFILMENT_EMULATOR_SECRET
#   grafana       a password grant in the master realm (admin-cli) for `grafana-admin`: master-realm.json was imported,
#                 with DEFAULT_PASSWORD substituted
#   admin         the operator's bootstrap admin gets an admin token and finds master's `grafana` client
#   health/metrics the management port (9000) answers in-cluster
# Each line is `<check> <values...>`. The script is single-quoted, so its variables are the pod's own.
kc_issuer="https://id.polaris.local/realms/polaris"
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
kc_script='kc=https://id.polaris.local
c() { curl -sS --max-time 15 --cacert /etc/polaris-trust/ca.crt "$@"; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
claims() { p=$(printf %s "$1" | cut -d. -f2 | tr "_-" "/+"); while [ $((${#p} % 4)) -ne 0 ]; do p="$p="; done; printf %s "$p" | base64 -d; }
token() { realm=$1; shift; c "$@" "$kc/realms/$realm/protocol/openid-connect/token" | field access_token; }
echo "issuer $(c "$kc/realms/polaris/.well-known/openid-configuration" | field issuer)"
t=$(token polaris -d grant_type=password -d client_id=polaris-app -d username=testuser --data-urlencode "password=$DEFAULT_PASSWORD")
echo "password $(claims "$t" | field iss) $(claims "$t" | field preferred_username)"
t=$(token polaris -d grant_type=client_credentials -d client_id=polaris-fulfilment-emulator --data-urlencode "client_secret=$EMULATOR_SECRET")
echo "emulator $(claims "$t" | field azp)"
t=$(token master -d grant_type=password -d client_id=admin-cli -d username=grafana-admin --data-urlencode "password=$DEFAULT_PASSWORD")
echo "grafana $(claims "$t" | field iss) $(claims "$t" | field azp)"
t=$(token master -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$KC_ADMIN_USER" --data-urlencode "password=$KC_ADMIN_PASSWORD")
echo "admin $(c -H "Authorization: Bearer $t" "$kc/admin/realms/master/clients?clientId=grafana" | field clientId)"
echo "health $(curl -sS -o /dev/null --max-time 10 -w "%{http_code}" http://keycloak-service:9000/health/ready)"
echo "metrics $(curl -sS --max-time 10 http://keycloak-service:9000/metrics | grep -c "^jvm_")"'
secret_env() { # <variable> <secret> <key>
  printf '        - name: %s\n          valueFrom:\n            secretKeyRef:\n              name: %s\n              key: %s\n' "$@"
}
kc_env="$(secret_env DEFAULT_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD
secret_env EMULATOR_SECRET "$KEYCLOAK_REALM_SECRET" POLARIS_FULFILMENT_EMULATOR_SECRET
secret_env KC_ADMIN_USER "$KEYCLOAK-initial-admin" username
secret_env KC_ADMIN_PASSWORD "$KEYCLOAK-initial-admin" password)"
k5_probes+=(keycloak-smoke) # removed on exit too
if out="$(run_probe keycloak-smoke "$SMOKE_CURL_IMAGE" 100 "$kc_script" "$kc_env")"; then
  log "Keycloak probe results:"$'\n'"$out"
  check "OIDC discovery from a pod names the issuer $kc_issuer" grep -qx "issuer $kc_issuer" <<<"$out"
  check "a password grant for seeded user testuser (client polaris-app) issues a token from $kc_issuer" \
    grep -qx "password $kc_issuer testuser" <<<"$out"
  check "client polaris-fulfilment-emulator gets a token with the secret from $KEYCLOAK_REALM_SECRET" \
    grep -qx "emulator polaris-fulfilment-emulator" <<<"$out"
  # The imported master realm has no `basic` scope on admin-cli, so the token carries no preferred_username; that it
  # is issued at all proves grafana-admin exists with DEFAULT_PASSWORD.
  check "the master realm was imported: grafana-admin gets a token with DEFAULT_PASSWORD" \
    grep -qx "grafana https://id.polaris.local/realms/master admin-cli" <<<"$out"
  check "the operator's bootstrap admin ($KEYCLOAK-initial-admin) reads master's grafana client" \
    grep -qx "admin grafana" <<<"$out"
  check "Keycloak health/ready answers 200 on the management port" grep -qx "health 200" <<<"$out"
  check "Keycloak serves Prometheus metrics on the management port" grep -Eq "^metrics [1-9]" <<<"$out"
else
  fail "Keycloak probe did not succeed: $(tail -5 <<<"$out")"
fi

# From the host, through kind's port mapping of the Gateway's HTTPS NodePort (443 by default, another port with
# KIND_CONFIG), as a browser reaches it: curl pins id.polaris.local to 127.0.0.1 (no /etc/hosts needed) and verifies
# the certificate against the cluster root CA.
host_port="$(docker port "$KIND_CLUSTER_NAME-control-plane" 30443/tcp 2>/dev/null | sed -n '1s/.*://p')"
root_ca="$(mktemp)"
kctl -n cert-manager get secret polaris-root-ca -o jsonpath='{.data.tls\.crt}' 2>/dev/null | base64 -d >"$root_ca" || true
host_issuer="$(curl -sS --max-time 15 --cacert "$root_ca" --resolve "id.polaris.local:${host_port:-443}:127.0.0.1" \
  "https://id.polaris.local:${host_port:-443}/realms/polaris/.well-known/openid-configuration" 2>&1 |
  sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p')"
rm -f "$root_ca"
check "OIDC discovery from the host (127.0.0.1:${host_port:-443}) names the issuer $kc_issuer" \
  [ "$host_issuer" = "$kc_issuer" ]

# Keycloak exports its spans to the Collector (TR-K9): the token requests above show up in the debug exporter as
# spans of service.name keycloak.
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90}))
kc_spans=""
while [ "$SECONDS" -lt "$deadline" ]; do
  kc_spans="$(kctl -n "$K8S_NAMESPACE" logs "deployment/$OTEL_COLLECTOR" --since=5m 2>/dev/null |
    grep -c "service.name: Str(keycloak)" || true)"
  [ "${kc_spans:-0}" -gt 0 ] && break
  sleep 3
done
check "the Collector received Keycloak spans (service.name keycloak)" [ "${kc_spans:-0}" -gt 0 ]

# --- K8: Order & Catalog (polaris) and Swagger UI -------------------------------------------------------------
# The polaris and Swagger UI pods run in the namespace that enforces `restricted`, so their being ready shows they
# pass it. Every request goes through the Gateway, as clients send it (TR-K11).
check "deployment $POLARIS has 2 of 2 replicas ready" [ "$(kctl -n "$K8S_NAMESPACE" get deployment "$POLARIS" \
  -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null)" = 2/2 ]
check "PodDisruptionBudget $POLARIS (minAvailable 1) allows 1 disruption" [ "$(kctl -n "$K8S_NAMESPACE" get pdb \
  "$POLARIS" -o jsonpath='{.spec.minAvailable} {.status.disruptionsAllowed}' 2>/dev/null)" = "1 1" ]
check "deployment $SWAGGER_UI is available" \
  quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$SWAGGER_UI" --timeout=60s
for route in "$POLARIS" "$POLARIS-mcp" "$SWAGGER_UI"; do
  check "HTTPRoute $route is accepted by the Gateway and its backends resolve" [ "$(kctl -n "$K8S_NAMESPACE" get httproute \
    "$route" -o jsonpath="{.status.parents[0].conditions[?(@.type=='Accepted')].status} {.status.parents[0].conditions[?(@.type=='ResolvedRefs')].status}" \
    2>/dev/null)" = "True True" ]
done
check "ProxySettingsPolicy $POLARIS-mcp-sse (SSE: no buffering, long read timeout) is accepted" is_true "$(kctl -n \
  "$K8S_NAMESPACE" get proxysettingspolicy "$POLARIS-mcp-sse" \
  -o jsonpath="{.status.ancestors[0].conditions[?(@.type=='Accepted')].status}" 2>/dev/null)"

# Flyway with two replicas (TR-K5): both replicas start together on an empty database (the first start, once
# `make k8s-images` has loaded the image), and Flyway's advisory lock lets exactly one apply each migration. The
# history then holds each versioned migration of the repo exactly once, all successful.
migrations="$(find "$REPO_ROOT/libs" "$REPO_ROOT/apps/polaris" -path '*/src/main/resources/db/migration/V*.sql' | wc -l | tr -d ' ')"
flyway_sql="SELECT 'flyway', count(*), count(DISTINCT version), count(*) FILTER (WHERE NOT success)
  FROM flyway_schema_history WHERE version IS NOT NULL"
k5_probes+=(flyway-smoke outbox-smoke polaris-smoke k6-api-test k6-rolling-restart) # removed on exit too
if out="$(run_probe flyway-smoke "$pg_image" 26 "set -e
$(wait_for POLARIS_DB_URI)
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' -c \"$flyway_sql\"" "$pg_env")"; then
  log "Flyway history: $out"
  check "flyway_schema_history holds each of the $migrations migrations exactly once, all successful" \
    grep -qx "flyway $migrations $migrations 0" <<<"$out"
else
  fail "Flyway probe did not succeed: $(tail -5 <<<"$out")"
fi

# Outbox relay with two replicas (TR-K5), the E3 two-instance guarantee (OutboxRelayIntegrationTest
# twoInstances_neitherReorderNorDoubleHandOff) on the cluster: events recorded in one transaction for a few keys are
# handed off by the relays of both running replicas, competing, to a test topic. Each must reach Kafka exactly once,
# and each key's events in commit order. The events carry their sequence number as payload; they are removed, with the
# topic, at the end (and on exit).
ob_token="$(hex 4)"
ob_topic="k8-outbox-smoke-$ob_token"
ob_keys=6
ob_events=60
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup_k8() {
  if [ -n "${ob_topic_created:-}" ]; then
    for node in 1 2 3; do
      kafka_cli "$node" kafka-topics --delete --if-exists --topic "$ob_topic" </dev/null >/dev/null 2>&1 && break
    done
  fi
  kctl -n "$K8S_NAMESPACE" delete configmap k8-perf-scripts --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
trap 'cleanup; cleanup_k8' EXIT
if kafka_cli 1 kafka-topics --create --topic "$ob_topic" --partitions 3 --replication-factor 3 </dev/null >/dev/null; then
  ob_topic_created=1
  # The SQL is quoted by the probe's here-documents, so the payload's JSON quotes need no escaping.
  if out="$(run_probe outbox-smoke "$pg_image" 26 "set -e
$(wait_for POLARIS_DB_URI)
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -q <<'SQL'
INSERT INTO outbox_events (event_id, event_type, event_source, destination, event_key, payload, occurred_at)
SELECT gen_random_uuid(), 'vn.danang.polaris.k8s-smoke.v1', '/k8s/smoke', '$ob_topic',
       'k8-$ob_token-' || (i % $ob_keys), '{\"seq\":' || i || '}', now()
FROM generate_series(1, $ob_events) AS i ORDER BY i;
SQL
delivered=0
for i in \$(seq 90); do
  delivered=\$(psql \"\$POLARIS_DB_URI\" -qAt -c \"SELECT count(*) FROM outbox_events WHERE destination = '$ob_topic' AND status = 'DELIVERED'\")
  [ \"\$delivered\" = $ob_events ] && break
  sleep 1
done
echo \"delivered \$delivered\"
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -qAt -c \"DELETE FROM outbox_events WHERE destination = '$ob_topic'\"" "$pg_env")"; then
    check "the relays marked all $ob_events recorded events delivered" grep -qx "delivered $ob_events" <<<"$out"
  else
    fail "outbox probe did not succeed: $(tail -5 <<<"$out")"
  fi
  records="$(kafka_cli 1 kafka-console-consumer --topic "$ob_topic" --from-beginning --group "$ob_topic" \
    --max-messages "$ob_events" --timeout-ms 30000 --property print.key=true --property key.separator=' ' \
    </dev/null 2>/dev/null | sed -n 's/^\([^ ]*\) {"seq":\([0-9]*\)}$/\1 \2/p' || true)"
  check "$ob_topic holds exactly $ob_events records (end offsets): no event was handed off twice" \
    [ "$(end_offset_sum 1 "$ob_topic")" = "$ob_events" ]
  check "every recorded event reached Kafka exactly once" \
    [ "$(cut -d' ' -f2 <<<"$records" | sort -n | uniq | tr '\n' ' ')" = "$(seq 1 "$ob_events" | tr '\n' ' ')" ]
  in_key_order() { awk '{ if (($1 in last) && $2 <= last[$1]) bad++; last[$1] = $2 } END { exit bad > 0 }' <<<"$1"; }
  check "each of the $ob_keys keys' events reached Kafka in commit order" in_key_order "$records"
  kafka_cli 1 kafka-consumer-groups --delete --group "$ob_topic" </dev/null >/dev/null 2>&1 || true
  if kafka_cli 1 kafka-topics --delete --topic "$ob_topic" </dev/null >/dev/null 2>&1; then ob_topic_created=""; fi
else
  fail "could not create test topic $ob_topic"
fi

# One trace from the Gateway into polaris (TR-K9): a probe gets a token for `testuser` (password DEFAULT_PASSWORD, as
# the KeycloakRealmImport set it) and calls the catalogue through the Gateway with a fresh traceparent. polaris
# answers with the trace in X-Trace-Id, and the Collector receives the Gateway's span and polaris's server span of
# that trace, the latter a child of the former and carrying polaris's Kubernetes resource attributes. The probe also
# checks MCP's SSE route and Swagger UI's routes.
trace_id="$(hex 16)"
traceparent="00-$trace_id-$(hex 8)-01"
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
polaris_script='c() { curl -sS --max-time 15 --cacert /etc/polaris-trust/ca.crt "$@"; }
t=$(c -d grant_type=password -d client_id=polaris-local -d username=testuser --data-urlencode "password=$DEFAULT_PASSWORD" \
  https://id.polaris.local/realms/polaris/protocol/openid-connect/token | sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
echo "product $(c -o /dev/null -D - -w "%{http_code}" -H "Authorization: Bearer $t" -H "traceparent: $TRACEPARENT" \
  https://polaris.local/api/v1/products/sku/NG-EARBUD-01 | tr -d "\r" | sed -n "s/^[Xx]-[Tt]race-[Ii]d: //p;\$p" | tr "\n" " ")"
echo "anonymous $(c -o /dev/null -w "%{http_code}" https://polaris.local/api/v1/products)"
echo "api-docs $(c -o /dev/null -w "%{http_code} %{content_type}" https://polaris.local/v3/api-docs)"
echo "swagger-ui $(c https://polaris.local/swagger-ui/swagger-initializer.js | grep -q "/v3/api-docs/assistant" && echo listed)"
echo "swagger-ui.html $(c -o /dev/null -w "%{http_code} %{redirect_url}" https://polaris.local/swagger-ui.html)"
echo "mcp $(c -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "{}" https://polaris.local/mcp/)"'
polaris_env="$(secret_env DEFAULT_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)
        - name: TRACEPARENT
          value: \"$traceparent\""
if out="$(run_probe polaris-smoke "$SMOKE_CURL_IMAGE" 100 "$polaris_script" "$polaris_env")"; then
  log "polaris probe results:"$'\n'"$out"
  check "GET /api/v1/products/sku/NG-EARBUD-01 through the Gateway answers 200 with X-Trace-Id $trace_id" \
    grep -qx "product $trace_id 200 " <<<"$out"
  check "an anonymous API call through the Gateway answers 401" grep -qx "anonymous 401" <<<"$out"
  check "GET /v3/api-docs through the Gateway answers the OpenAPI document" grep -q "^api-docs 200 application/json" <<<"$out"
  check "Swagger UI at /swagger-ui serves its initializer with Compose's spec URLs" grep -qx "swagger-ui listed" <<<"$out"
  check "/swagger-ui.html redirects (301) to /swagger-ui/index.html" \
    grep -Eqx "swagger-ui.html 301 https://polaris.local(:443)?/swagger-ui/index.html" <<<"$out"
  # An unauthenticated MCP request reaches polaris through the /mcp/ route (401 from polaris, not 404 from the Gateway).
  check "/mcp/ reaches polaris through the Gateway (401 without a token)" grep -qx "mcp 401" <<<"$out"
else
  fail "polaris probe did not succeed: $(tail -5 <<<"$out")"
fi
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90}))
gw_span="" app_span=""
while [ "$SECONDS" -lt "$deadline" ]; do
  spans="$(telemetry_block ResourceSpans "$trace_id")"
  gw_span="$(awk '/ResourceSpans #/ { keep = 0 } /service.name: Str\(nginx-gateway\)/ { keep = 1 } keep' <<<"$spans")"
  app_span="$(awk -v d="$POLARIS" '/ResourceSpans #/ { keep = 0 } $0 ~ "k8s.deployment.name: Str\\(" d "\\)" { keep = 1 } keep' <<<"$spans")"
  if [ -n "$gw_span" ] && [ -n "$app_span" ]; then break; fi
  sleep 3
done
# The resource attributes precede the spans in a block, so the filters above keep a block from its service line on.
# The debug exporter prints a span as `Trace ID : ...`, `Parent ID : ...`, `ID : ...`.
gw_span_id="$(sed -nE "/Trace ID +: $trace_id/,/^ +ID +:/ s/^ +ID +: ([0-9a-f]+).*/\1/p" <<<"$gw_span" | head -n 1)"
check "the Collector received the Gateway span of trace $trace_id" [ -n "$gw_span_id" ]
if [ -n "$app_span" ]; then
  pass "the Collector received polaris spans of trace $trace_id"
  check "a polaris span is a child of the Gateway span ($gw_span_id): one trace from the Gateway into polaris" \
    grep -Eq "Parent ID +: ${gw_span_id:-none}$" <<<"$app_span"
  polaris_attributes() {
    grep -q "k8s.namespace.name: Str($K8S_NAMESPACE)" <<<"$1" && grep -q "k8s.pod.name: Str($POLARIS-" <<<"$1"
  }
  check "the polaris spans carry k8s.namespace.name, k8s.pod.name and k8s.deployment.name of $POLARIS" \
    polaris_attributes "$app_span"
else
  fail "no polaris span of trace $trace_id reached the Collector's debug exporter within ${OTEL_SMOKE_TIMEOUT:-90}s"
fi

# tests/perf/api-test.js (k6) against https://polaris.local from a `restricted` pod, as `make test-perf` runs it
# against Compose: same script, same thresholds, the token from Keycloak through the Gateway. k6 also writes every
# request to a CSV file, from which the pod prints `server-errors <n>`: responses with a 5xx status or none at all.
# Runs in the background with start_k6 <name> <extra k6 args>; wait_k6 <name> <timeout> waits and prints the output.
kctl -n "$K8S_NAMESPACE" create configmap k8-perf-scripts --from-file="api-test.js=$REPO_ROOT/tests/perf/api-test.js" \
  --from-file="auth.js=$REPO_ROOT/tests/perf/common/auth.js" --from-file="index.js=$REPO_ROOT/tests/perf/common/index.js" \
  --dry-run=client -o yaml | kctl apply -f - >/dev/null
start_k6() {
  local name="$1" args="$2"
  kctl -n "$K8S_NAMESPACE" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
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
    runAsUser: 12345
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: k6
      image: $SMOKE_K6_IMAGE
      command: ["sh", "-c"]
      # k6 runs in the background so that \`touch /tmp/stop\` (kubectl exec) can end an open-ended run gracefully.
      args:
        - |
          k6 run $args --out csv=/tmp/k6.csv /scripts/api-test.js &
          pid=\$!
          while kill -0 \$pid 2>/dev/null; do
            if [ -f /tmp/stop ]; then kill -INT \$pid; break; fi
            sleep 1
          done
          wait \$pid
          rc=\$?
          awk -F, 'NR == 1 { for (i = 1; i <= NF; i++) if (\$i == "status") s = i; next }
            \$1 == "http_reqs" && (\$s >= 500 || \$s == 0) { n++ } END { print "server-errors " n + 0 }' /tmp/k6.csv
          exit \$rc
      env:
        - name: K6_NO_USAGE_REPORT
          value: "true"
        - name: BASE_URL
          value: https://polaris.local
        - name: CLIENT_ID
          value: polaris-local
        - name: USERNAME
          value: testuser
        - name: PASSWORD
          valueFrom:
            secretKeyRef:
              name: $KEYCLOAK_REALM_SECRET
              key: DEFAULT_PASSWORD
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      resources:
        requests:
          cpu: 100m
          memory: 64Mi
        limits:
          memory: 512Mi
      volumeMounts:
        - name: scripts
          mountPath: /scripts
          readOnly: true
        - name: tmp
          mountPath: /tmp
  volumes:
    - name: scripts
      configMap:
        name: k8-perf-scripts
        items:
          - key: api-test.js
            path: api-test.js
          - key: auth.js
            path: common/auth.js
          - key: index.js
            path: common/index.js
    - name: tmp
      emptyDir:
        sizeLimit: 256Mi
EOF
}
wait_k6() {
  local name="$1" phase="" deadline=$((SECONDS + $2))
  while [ "$SECONDS" -lt "$deadline" ]; do
    phase="$(kctl -n "$K8S_NAMESPACE" get pod "$name" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in Succeeded | Failed) break ;; esac
    sleep 3
  done
  echo "k6 pod phase ${phase:-(none)}"
  kctl -n "$K8S_NAMESPACE" logs "$name" 2>&1 | grep -v '^time=' || true
  kctl -n "$K8S_NAMESPACE" delete pod "$name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  [ "$phase" = Succeeded ]
}

# The smoke run: api-test.js with its own defaults (5 VUs for 10s), thresholds included (exit code).
start_k6 k6-api-test ""
if out="$(wait_k6 k6-api-test 300)"; then
  pass "tests/perf/api-test.js passes against https://polaris.local (checks and thresholds)"
else
  fail "tests/perf/api-test.js failed against https://polaris.local"
fi
log "api-test.js results:"$'\n'"$(grep -E "phase|checks|http_req_duration|http_reqs|✗|server-errors|THRESHOLDS" <<<"$out" || true)"
check "api-test.js got no 5xx and no failed connection" grep -qx "server-errors 0" <<<"$out"

# A rolling restart under load (TR-K4, TR-K5): api-test.js runs open-ended while `kubectl rollout restart` replaces
# both replicas one by one (maxUnavailable 0, readiness-gated, preStop drain before the graceful shutdown). It goes
# on for a few seconds on the new pods, then stops gracefully. No request may get a 5xx or lose its connection.
# Thresholds are left out: latency is measured against freshly started JVMs here, not the steady state.
start_k6 k6-rolling-restart "--no-thresholds -e DURATION=20m"
if kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready pod/k6-rolling-restart --timeout=120s >/dev/null 2>&1; then
  sleep 10
  old_pods="$(kctl -n "$K8S_NAMESPACE" get pods -l "app.kubernetes.io/name=$POLARIS" -o jsonpath='{.items[*].metadata.name}')"
  log "rolling restart of deployment/$POLARIS under load (pods $old_pods)"
  restarted=""
  if quiet kctl -n "$K8S_NAMESPACE" rollout restart "deployment/$POLARIS" &&
    quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$POLARIS" --timeout="${ROLLOUT_SMOKE_TIMEOUT:-600}s"; then
    restarted=1
  fi
  sleep 15
  quiet kctl -n "$K8S_NAMESPACE" exec k6-rolling-restart -- touch /tmp/stop || true
  out="$(wait_k6 k6-rolling-restart 120 || true)"
  log "api-test.js under the rolling restart:"$'\n'"$(grep -E "checks|http_reqs|✗|server-errors" <<<"$out" || true)"
  new_pods="$(kctl -n "$K8S_NAMESPACE" get pods -l "app.kubernetes.io/name=$POLARIS" -o jsonpath='{.items[*].metadata.name}')"
  replaced() {
    [ -n "$restarted" ] || return 1
    for pod in $old_pods; do [[ " $new_pods " != *" $pod "* ]] || return 1; done
  }
  check "the rolling restart replaced both replicas while api-test.js ran (now $new_pods)" replaced
  check "api-test.js ran during the rolling restart" grep -Eq "http_reqs[ .:]+[1-9]" <<<"$out"
  check "no request got a 5xx or lost its connection during the rolling restart" grep -qx "server-errors 0" <<<"$out"
else
  fail "the k6 pod for the rolling restart did not start: $(kctl -n "$K8S_NAMESPACE" describe pod k6-rolling-restart 2>&1 | tail -5)"
fi

# --- K9: Assistant (polaris-assistant) ------------------------------------------------------------------------
# The assistant pods run in the namespace that enforces `restricted`, so their being ready shows they pass it. Chat
# turns call Gemini (and TypeSafe for intents), so these checks need GEMINI_API_KEY and TYPESAFE_API_KEY in the
# untracked env file (CI writes it from repository secrets).
check "deployment $ASSISTANT has 2 of 2 replicas ready" [ "$(kctl -n "$K8S_NAMESPACE" get deployment "$ASSISTANT" \
  -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null)" = 2/2 ]
check "PodDisruptionBudget $ASSISTANT (minAvailable 1) allows 1 disruption" [ "$(kctl -n "$K8S_NAMESPACE" get pdb \
  "$ASSISTANT" -o jsonpath='{.spec.minAvailable} {.status.disruptionsAllowed}' 2>/dev/null)" = "1 1" ]
for route in "$ASSISTANT" "$ASSISTANT-api-docs"; do
  check "HTTPRoute $route is accepted by the Gateway and its backends resolve" [ "$(kctl -n "$K8S_NAMESPACE" get httproute \
    "$route" -o jsonpath="{.status.parents[0].conditions[?(@.type=='Accepted')].status} {.status.parents[0].conditions[?(@.type=='ResolvedRefs')].status}" \
    2>/dev/null)" = "True True" ]
done
check "ProxySettingsPolicy $ASSISTANT-sse (SSE: no buffering, long read timeout) is accepted" is_true "$(kctl -n \
  "$K8S_NAMESPACE" get proxysettingspolicy "$ASSISTANT-sse" \
  -o jsonpath="{.status.ancestors[0].conditions[?(@.type=='Accepted')].status}" 2>/dev/null)"
# Key names only: the values never leave the Secret.
# shellcheck disable=SC2016 # a Go template, not shell
assistant_keys=" $(kctl -n "$K8S_NAMESPACE" get secret "$ASSISTANT_SECRET" \
  -o go-template='{{range $k, $v := .data}}{{$k}} {{end}}' 2>/dev/null || true)"
has_model_keys() { [[ "$assistant_keys" == *" GEMINI_API_KEY "* && "$assistant_keys" == *" TYPESAFE_API_KEY "* ]]; }
check "Secret $ASSISTANT_SECRET carries GEMINI_API_KEY and TYPESAFE_API_KEY from the env file" has_model_keys

# `make seed-shoppers` and `make chat-scenarios` from the host, through kind's port mapping of the Gateway (host_port,
# K7), as on Compose: the k6 container reaches id.polaris.local and polaris.local on the host gateway. The Keycloak
# admin is the operator's bootstrap admin (Secret keycloak-initial-admin), not Compose's admin/admin, and the realm's
# users (shopper.0-9, testuser) have DEFAULT_PASSWORD. make gets the values as exported variables (builtins), never as
# process arguments, and the Makefile passes them on by name (`docker run -e NAME`).
secret_value() { kctl -n "$K8S_NAMESPACE" get secret "$1" -o jsonpath="{.data.$2}" 2>/dev/null | base64 -d 2>/dev/null || true; }
host_make() { # <target>
  local port_suffix=""
  [ "${host_port:-443}" = 443 ] || port_suffix=":$host_port"
  (
    export KC_BASE="https://id.polaris.local$port_suffix" API_BASE="https://polaris.local$port_suffix"
    export ASSISTANT_BASE="https://polaris.local$port_suffix"
    SHOPPER_PASSWORD="$(secret_value "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)"
    E2E_STAFF_PASSWORD="$SHOPPER_PASSWORD"
    KC_ADMIN_USER="$(secret_value "$KEYCLOAK-initial-admin" username)"
    KC_ADMIN_PASSWORD="$(secret_value "$KEYCLOAK-initial-admin" password)"
    export SHOPPER_PASSWORD E2E_STAFF_PASSWORD KC_ADMIN_USER KC_ADMIN_PASSWORD
    make -s -C "$REPO_ROOT" "$1"
  ) 2>&1
}
if out="$(host_make seed-shoppers)"; then
  pass "make seed-shoppers passes through the Gateway (bootstrap admin from $KEYCLOAK-initial-admin)"
else
  fail "make seed-shoppers failed through the Gateway"
fi
log "seed-shoppers results:"$'\n'"$(grep -E "shoppers_|checks|✗|ERRO|level=error" <<<"$out" || tail -20 <<<"$out")"
if out="$(host_make chat-scenarios)"; then
  pass "make chat-scenarios passes through the Gateway (search, order draft, confirm, status, cancel)"
else
  fail "make chat-scenarios failed through the Gateway"
fi
log "chat-scenarios results:"$'\n'"$(grep -E "✓|✗|checks|chat_turn_duration|http_req_failed|orders_|ERRO|level=error" <<<"$out" || tail -20 <<<"$out")"

# Sessions and order drafts survive a replica switch (TR-K5): chat-scenarios.js runs again from a `restricted` k6 pod,
# with ASSISTANT_BASES set to the two pods' own addresses, so its requests alternate between the replicas: the search
# on one, the order (stages a draft) on the other, the draft's confirmation back on the first, the status on the
# second, and the cancellation request and its confirmation on different pods. The draft and the history are found only
# through the shared JPA session store; the intents come from Redis on both.
assistant_pods="$(kctl -n "$K8S_NAMESPACE" get pods -l "app.kubernetes.io/name=$ASSISTANT" \
  -o jsonpath='{range .items[?(@.status.podIP)]}{.metadata.name} {.status.podIP}{"\n"}{end}' 2>/dev/null || true)"
read -r pod_a ip_a <<<"$(sed -n 1p <<<"$assistant_pods")"
read -r pod_b ip_b <<<"$(sed -n 2p <<<"$assistant_pods")"
k5_probes+=(k9-chat-replicas k9-redis-intents k9-assistant-smoke k9-drain) # removed on exit too
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup_k9() { kctl -n "$K8S_NAMESPACE" delete configmap k9-chat-scripts --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap 'cleanup; cleanup_k8; cleanup_k9' EXIT
if [ -n "$ip_a" ] && [ -n "$ip_b" ]; then
  e2e="$REPO_ROOT/tests/e2e/k6"
  kctl -n "$K8S_NAMESPACE" create configmap k9-chat-scripts --from-file="chat-scenarios.js=$e2e/chat-scenarios.js" \
    --from-file="assistant.js=$e2e/lib/assistant.js" --from-file="catalog.js=$e2e/lib/catalog.js" \
    --from-file="config.js=$e2e/lib/config.js" --from-file="http.js=$e2e/lib/http.js" \
    --from-file="keycloak.js=$e2e/lib/keycloak.js" --dry-run=client -o yaml | kctl apply -f - >/dev/null
  kctl -n "$K8S_NAMESPACE" delete pod k9-chat-replicas --ignore-not-found --wait=true >/dev/null
  kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: k9-chat-replicas
  labels:
    app.kubernetes.io/name: k9-chat-replicas
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  enableServiceLinks: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 12345
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: k6
      image: $SMOKE_K6_IMAGE
      command: ["k6", "run", "/scripts/chat-scenarios.js"]
      env:
        - name: K6_NO_USAGE_REPORT
          value: "true"
        - name: API_BASE
          value: https://polaris.local
        - name: KC_BASE
          value: https://id.polaris.local
        - name: ASSISTANT_BASES
          value: http://$ip_a:8081,http://$ip_b:8081
$(secret_env SHOPPER_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)
$(secret_env E2E_STAFF_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      resources:
        requests:
          cpu: 100m
          memory: 64Mi
        limits:
          memory: 512Mi
      volumeMounts:
        - name: scripts
          mountPath: /scripts
          readOnly: true
        - name: tmp
          mountPath: /tmp
  volumes:
    - name: scripts
      configMap:
        name: k9-chat-scripts
        items:
          - key: chat-scenarios.js
            path: chat-scenarios.js
          - key: assistant.js
            path: lib/assistant.js
          - key: catalog.js
            path: lib/catalog.js
          - key: config.js
            path: lib/config.js
          - key: http.js
            path: lib/http.js
          - key: keycloak.js
            path: lib/keycloak.js
    - name: tmp
      emptyDir:
        sizeLimit: 64Mi
EOF
  if out="$(wait_k6 k9-chat-replicas 600)"; then
    pass "chat-scenarios.js passes with consecutive turns pinned to different replicas ($pod_a, $pod_b)"
  else
    fail "chat-scenarios.js failed with consecutive turns pinned to different replicas ($pod_a, $pod_b)"
  fi
  log "chat-scenarios.js across replicas:"$'\n'"$(grep -E "phase|✓|✗|checks|http_req_failed|orders_|ERRO|level=error" <<<"$out" || tail -20 <<<"$out")"
  check "the order draft staged on one replica was confirmed on the other (ORDER_CONFIRMED card)" \
    grep -q "✓ confirm: 201 with ORDER_CONFIRMED card" <<<"$out"
else
  fail "deployment $ASSISTANT has no two pods with an address for the replica-switch check: $assistant_pods"
fi
if out="$(run_probe k9-redis-intents "$redis_image" 999 "redis-cli -h $REDIS -p 6379 exists polaris:assistant:intents")"; then
  check "the intent taxonomy the replicas share is in Redis (polaris:assistant:intents)" grep -qx 1 <<<"$out"
else
  fail "Redis intents probe did not succeed: $(tail -5 <<<"$out")"
fi

# From a `restricted` probe, as shopper.1 (DEFAULT_PASSWORD):
#   readiness  the readiness group, read with a token so that its components show: readinessState, db and polarisMcp
#              only, so a Gemini or TypeSafe outage never takes a pod out of the Service (Compose's semantics)
#   health     the full health endpoint lists gemini and typeSafe, outside readiness
#   api-docs   /v3/api-docs/assistant through the Gateway is the assistant's OpenAPI document
#   chat       one chat turn through the Gateway with a fresh traceparent, for the trace check below
trace_id="$(hex 16)"
traceparent="00-$trace_id-$(hex 8)-01"
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
assistant_script='c() { curl -sS --max-time 15 --cacert /etc/polaris-trust/ca.crt "$@"; }
t=$(c -d grant_type=password -d client_id=polaris-app -d username=shopper.1 --data-urlencode "password=$DEFAULT_PASSWORD" \
  https://id.polaris.local/realms/polaris/protocol/openid-connect/token | sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
components() { tr "," "\n" | sed -n "s/.*\"\([A-Za-z]*\)\":{\"status\".*/\1/p" | sort | tr "\n" " "; }
echo "readiness $(c -H "Authorization: Bearer $t" http://polaris-assistant:8081/actuator/health/readiness | components)"
echo "health $(c --max-time 30 -H "Authorization: Bearer $t" http://polaris-assistant:8081/actuator/health | components)"
echo "api-docs $(c -o /dev/null -w "%{http_code} %{content_type}" https://polaris.local/v3/api-docs/assistant)"
echo "api-docs-paths $(c https://polaris.local/v3/api-docs/assistant | grep -c "/api/v1/assistant/chat")"
echo "chat $(c --max-time 90 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $t" -H "traceparent: $TRACEPARENT" \
  -H "Content-Type: application/json" -d "{\"sessionId\":\"k9-trace-$TRACE_ID\",\"message\":\"Find products matching E2E\"}" \
  https://polaris.local/api/v1/assistant/chat)"'
assistant_env="$(secret_env DEFAULT_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)
        - name: TRACEPARENT
          value: \"$traceparent\"
        - name: TRACE_ID
          value: \"$trace_id\""
if out="$(run_probe k9-assistant-smoke "$SMOKE_CURL_IMAGE" 100 "$assistant_script" "$assistant_env")"; then
  log "assistant probe results:"$'\n'"$out"
  check "the readiness group holds readinessState, db and polarisMcp only (no gemini, no typeSafe)" \
    grep -qx "readiness db polarisMcp readinessState " <<<"$out"
  models_outside_readiness() { grep "^health " <<<"$1" | grep -q " gemini " && grep "^health " <<<"$1" | grep -q " typeSafe "; }
  check "gemini and typeSafe are on /actuator/health, outside readiness" models_outside_readiness "$out"
  check "GET /v3/api-docs/assistant through the Gateway answers the assistant's OpenAPI document" \
    grep -q "^api-docs 200 application/json" <<<"$out"
  check "the assistant's OpenAPI document lists /api/v1/assistant/chat" grep -Eq "^api-docs-paths [1-9]" <<<"$out"
  check "a chat turn through the Gateway answers 200" grep -qx "chat 200" <<<"$out"
else
  fail "assistant probe did not succeed: $(tail -5 <<<"$out")"
fi

# One trace Gateway -> assistant -> polaris (MCP) (TR-K9): the chat turn's trace reaches the Collector with the Gateway's
# span, an assistant span that is its child (with the assistant's Kubernetes attributes), and a polaris MCP span that is
# the child of an assistant span (the assistant forwards traceparent on its MCP calls).
# span_rows <blocks>: one line per span of the trace, `<trace id> <parent id, or -> <span id> <name>`, from the debug
# exporter's `Trace ID`, `Parent ID`, `ID` and `Name` lines.
span_rows() {
  awk '/^ +Trace ID +:/ { t = $NF } /^ +Parent ID +:/ { p = ($NF == ":" ? "-" : $NF) } /^ +ID +:/ { i = $NF }
    /^ +Name +:/ { n = $0; sub(/^ +Name +: */, "", n); print t, p, i, n }' <<<"$1" | awk -v t="$trace_id" '$1 == t'
}
# resource_blocks <regex>: the span blocks of $spans whose resource has a line matching <regex>, from that line on.
resource_blocks() { awk -v r="$1" '/ResourceSpans #/ { keep = 0 } $0 ~ r { keep = 1 } keep' <<<"$spans"; }
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90}))
gw_ids="" as_block="" as_rows="" pl_rows="" as_child="" mcp_child=""
while [ "$SECONDS" -lt "$deadline" ]; do
  spans="$(telemetry_block ResourceSpans "$trace_id")"
  gw_ids=" $(span_rows "$(resource_blocks 'service.name: Str\\(nginx-gateway\\)')" | awk '{ printf "%s ", $3 }')"
  as_block="$(resource_blocks "k8s.deployment.name: Str\\\\($ASSISTANT\\\\)")"
  as_rows="$(span_rows "$as_block")"
  pl_rows="$(span_rows "$(resource_blocks "k8s.deployment.name: Str\\\\($POLARIS\\\\)")")"
  as_ids=" $(awk '{ printf "%s ", $3 }' <<<"$as_rows")"
  as_child="$(awk -v ids="$gw_ids" 'index(ids, " " $2 " ")' <<<"$as_rows")"
  mcp_child="$(awk -v ids="$as_ids" 'index(ids, " " $2 " ") && tolower($0) ~ /mcp/' <<<"$pl_rows")"
  if [ -n "$as_child" ] && [ -n "$mcp_child" ]; then break; fi
  sleep 3
done
log "spans of trace $trace_id (trace, parent, id, name):"$'\n'"gateway:$gw_ids"$'\n'"assistant:"$'\n'"$as_rows"$'\n'"polaris:"$'\n'"$pl_rows"
check "the Collector received the Gateway span of trace $trace_id" [ -n "${gw_ids// /}" ]
check "an assistant span is a child of the Gateway span: one trace from the Gateway into $ASSISTANT" [ -n "$as_child" ]
assistant_attributes() {
  grep -q "k8s.namespace.name: Str($K8S_NAMESPACE)" <<<"$1" && grep -q "k8s.pod.name: Str($ASSISTANT-" <<<"$1"
}
check "the assistant spans carry k8s.namespace.name, k8s.pod.name and k8s.deployment.name of $ASSISTANT" \
  assistant_attributes "$as_block"
check "a polaris MCP span is a child of an assistant span: one trace Gateway -> $ASSISTANT -> $POLARIS (MCP)" \
  [ -n "$mcp_child" ]

# A rolling restart doesn't cut an in-flight turn (TR-K4): a probe sends a chat turn straight to one replica a few
# seconds after that pod starts terminating, the way a rolling restart terminates it. The 10s preStop sleep keeps it
# serving, then SIGTERM's graceful shutdown waits up to 30s for the turn (at most 25s) to complete. The turn must answer
# 200, and the Deployment must be back to two ready replicas.
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
drain_script='t=$(curl -sS --max-time 15 --cacert /etc/polaris-trust/ca.crt -d grant_type=password -d client_id=polaris-app \
  -d username=shopper.2 --data-urlencode "password=$DEFAULT_PASSWORD" \
  https://id.polaris.local/realms/polaris/protocol/openid-connect/token | sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
echo ready
sleep 5
echo "drain $(curl -sS --max-time 90 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $t" \
  -H "Content-Type: application/json" -d "{\"sessionId\":\"k9-drain-$(date +%s)\",\"message\":\"Find products matching E2E\"}" \
  "http://$TARGET:8081/api/v1/assistant/chat")"'
drain_env="$(secret_env DEFAULT_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)
        - name: TARGET
          value: \"$ip_a\""
if [ -n "$ip_a" ]; then
  drain_out="$(mktemp)"
  run_probe k9-drain "$SMOKE_CURL_IMAGE" 100 "$drain_script" "$drain_env" >"$drain_out" 2>&1 &
  drain_pid=$!
  deadline=$((SECONDS + 120))
  until kctl -n "$K8S_NAMESPACE" logs k9-drain 2>/dev/null | grep -qx ready || [ "$SECONDS" -ge "$deadline" ]; do sleep 1; done
  log "terminating $pod_a; the probe sends it a chat turn in 5s"
  quiet kctl -n "$K8S_NAMESPACE" delete pod "$pod_a" --wait=false || true
  wait "$drain_pid" || true
  out="$(cat "$drain_out")"
  rm -f "$drain_out"
  log "drain probe results:"$'\n'"$out"
  check "a chat turn sent to a terminating replica ($pod_a) completes with 200" grep -qx "drain 200" <<<"$out"
  check "deployment $ASSISTANT is back to 2 ready replicas" quiet kctl -n "$K8S_NAMESPACE" rollout status \
    "deployment/$ASSISTANT" --timeout="${ROLLOUT_SMOKE_TIMEOUT:-600}s"
fi

if [ "$failures" -gt 0 ]; then
  log "$failures smoke check(s) failed"
  exit 1
fi
log "all smoke checks passed"
