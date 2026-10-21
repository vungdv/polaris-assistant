# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of lib.sh and the smoke libs (see smoke.sh)
# K9: Assistant (polaris-assistant). Sourced by smoke.sh.

# --- K9: Assistant (polaris-assistant) ------------------------------------------------------------------------
# Runs the k6 chat journey (tests/e2e/k6/chat-scenarios.js: search, order draft, confirm, status, cancel)

# Step 1: the cluster is ready: the workloads the journey goes through (the Gateway, Keycloak, polaris and the assistant)
# have rolled out.
cluster_ready=true
for workload in "deployment/$GATEWAY_SERVICE" "statefulset/$KEYCLOAK" "deployment/$POLARIS" "deployment/$ASSISTANT"; do
  if quiet kctl -n "$K8S_NAMESPACE" rollout status "$workload" --timeout="${ROLLOUT_SMOKE_TIMEOUT:-600}s"; then
    pass "$workload is ready"
  else
    fail "$workload is not ready"
    cluster_ready=false
  fi
done

# Step 2: only on a ready cluster, run the scenarios and report the result.
if [ "$cluster_ready" = true ]; then
  if out="$(host_make chat-scenarios)"; then
    pass "make chat-scenarios passes through the Gateway (search, order draft, confirm, status, cancel)"
  else
    fail "make chat-scenarios failed through the Gateway"
  fi
  log "chat-scenarios results:"$'\n'"$(grep -E "✓|✗|checks|chat_turn_duration|http_req_failed|orders_|ERRO|level=error" <<<"$out" || tail -20 <<<"$out")"
else
  log "skipping chat-scenarios: the cluster is not ready"
fi


# TODO: the checks below were dropped to keep the smoke focused on the chat journey; restore as required:
#   - deployment, PodDisruptionBudget, HTTPRoutes and ProxySettingsPolicy (SSE) of the assistant are ready/accepted
#   - Secret carries GEMINI_API_KEY and TYPESAFE_API_KEY
#   - chat turns alternate between the two replicas, so sessions and drafts survive a replica switch (TR-K5)
#   - the intent taxonomy is in Redis (polaris:assistant:intents)
#   - readiness holds readinessState, db and polarisMcp only; gemini and typeSafe are on /actuator/health
#   - /v3/api-docs/assistant through the Gateway lists /api/v1/assistant/chat
#   - one trace Gateway -> assistant -> polaris (MCP) reaches the Collector (TR-K9)
#   - a chat turn sent to a terminating replica completes with 200 (TR-K4)
