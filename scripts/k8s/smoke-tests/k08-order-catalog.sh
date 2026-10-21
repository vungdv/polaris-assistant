# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K8: Order & Catalog (polaris) and Swagger UI. Sourced by smoke.sh.

# --- K8: Order & Catalog (polaris) and Swagger UI -------------------------------------------------------------
# The polaris and Swagger UI pods run in the namespace that enforces `restricted`, so their being ready shows they
# pass it. Every request goes through the Gateway, as clients send it (TR-K11).
check "deployment $POLARIS has all of its 2-4 replicas ready" all_ready "$POLARIS"
check "PodDisruptionBudget $POLARIS (minAvailable 1) allows evicting all but one ready replica" pdb_allows "$POLARIS"
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
register_probes flyway-smoke outbox-smoke polaris-smoke k6-api-test k6-rolling-restart
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
cleanup_k08() {
  if [ -n "${ob_topic_created:-}" ]; then
    for node in 1 2 3; do
      kafka_cli "$node" kafka-topics --delete --if-exists --topic "$ob_topic" </dev/null >/dev/null 2>&1 && break
    done
  fi
  kctl -n "$K8S_NAMESPACE" delete configmap k8-perf-scripts --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
on_cleanup cleanup_k08
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
