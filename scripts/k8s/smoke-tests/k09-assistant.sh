# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K9: Assistant (polaris-assistant). Sourced by smoke.sh.

# --- K9: Assistant (polaris-assistant) ------------------------------------------------------------------------
# The assistant pods run in the namespace that enforces `restricted`, so their being ready shows they pass it. Chat
# turns call Gemini (and TypeSafe for intents), so these checks need GEMINI_API_KEY and TYPESAFE_API_KEY in the
# untracked env file (CI writes it from repository secrets).
check "deployment $ASSISTANT has all of its 2-4 replicas ready" all_ready "$ASSISTANT"
check "PodDisruptionBudget $ASSISTANT (minAvailable 1) allows evicting all but one ready replica" pdb_allows "$ASSISTANT"
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
register_probes k9-chat-replicas k9-redis-intents k9-assistant-smoke k9-drain
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup_k09() { kctl -n "$K8S_NAMESPACE" delete configmap k9-chat-scripts --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
on_cleanup cleanup_k09
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
    $SMOKE_PROBE_KEY: "true"
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
echo "readiness $(c -H "Authorization: Bearer $t" http://polaris-assistant:8081/actuator/health/readiness | tr -d "\n")"
echo "health $(c --max-time 30 -H "Authorization: Bearer $t" http://polaris-assistant:8081/actuator/health | tr -d "\n")"
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
  # has_component <line prefix> <component>: the health JSON on that line has the component (`"<name>":{`).
  has_component() { grep "^$1 " <<<"$out" | grep -q "\"$2\":{"; }
  readiness_members() {
    has_component readiness readinessState && has_component readiness db && has_component readiness polarisMcp &&
      ! has_component readiness gemini && ! has_component readiness typeSafe
  }
  check "the readiness group holds readinessState, db and polarisMcp (no gemini, no typeSafe)" readiness_members
  models_on_health() { has_component health gemini && has_component health typeSafe; }
  check "gemini and typeSafe are on /actuator/health, outside readiness" models_on_health
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
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90}))
gw_ids="" as_block="" as_rows="" pl_rows="" as_child="" mcp_child=""
while [ "$SECONDS" -lt "$deadline" ]; do
  spans="$(telemetry_block ResourceSpans "$trace_id")"
  gw_ids=" $(span_rows "$(resource_blocks 'service.name: Str\\(nginx-gateway\\)')" | awk '{ printf "%s ", $3 }')"
  as_block="$(resource_blocks "k8s.deployment.name: Str\\\\($ASSISTANT\\\\)")"
  as_rows="$(span_rows "$as_block")"
  pl_rows="$(span_rows "$(resource_blocks "k8s.deployment.name: Str\\\\($POLARIS\\\\)")")"
  # The assistant's MCP client spans are named mcp.polaris.* (discovery, execute); polaris's server span for each
  # MCP call is their child.
  mcp_ids=" $(awk '$4 ~ /^mcp\./ { printf "%s ", $3 }' <<<"$as_rows")"
  as_child="$(awk -v ids="$gw_ids" 'index(ids, " " $2 " ")' <<<"$as_rows")"
  mcp_child="$(awk -v ids="$mcp_ids" 'index(ids, " " $2 " ")' <<<"$pl_rows")"
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
check "a polaris server span is a child of an assistant MCP call (mcp.polaris.*): one trace Gateway -> $ASSISTANT -> $POLARIS (MCP)" \
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
  check "deployment $ASSISTANT is back to all replicas ready" quiet kctl -n "$K8S_NAMESPACE" rollout status \
    "deployment/$ASSISTANT" --timeout="${ROLLOUT_SMOKE_TIMEOUT:-600}s"
fi
