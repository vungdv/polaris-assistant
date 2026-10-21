# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K12: local LGTM stack (Tempo, Loki, Prometheus, Grafana). Sourced by smoke.sh.

# --- K12: local LGTM stack (Tempo, Loki, Prometheus, Grafana) -------------------------------------------------
# The Collector exports to the in-cluster backends only (D8, no Grafana Cloud). A probe sends one request with a fresh
# traceparent through the Gateway (a traced route, as in K4) and then reads the signals back through the backends' own
# HTTP APIs, polling while the pipeline flushes (nginx exports spans every 5s, the batch processors hold 2s, and Tempo's
# metrics generator remote-writes every 15s):
#   tempo       the trace by ID, with the Gateway's span (service.name nginx-gateway)
#   loki        the Gateway's access log line of that trace, found by its trace_id (structured metadata)
#   prometheus  every scrape target is up, the SLO rule groups are loaded, and the span-metrics Tempo generates exist
#   grafana     healthy, with the Compose datasources and dashboards provisioned, and SSO through the Keycloak master realm
#               is the login redirect of https://grafana.polaris.local
for release in "statefulset/$TEMPO_RELEASE" "statefulset/$LOKI_RELEASE" "deployment/$PROMETHEUS_RELEASE" \
  "deployment/$GRAFANA_RELEASE"; do
  check "$OBSERVABILITY_NAMESPACE/$release is available" quiet kctl -n "$OBSERVABILITY_NAMESPACE" rollout status "$release" --timeout=60s
done
check "HTTPRoute grafana resolves its backend in $OBSERVABILITY_NAMESPACE (ReferenceGrant)" is_true "$(kctl -n "$K8S_NAMESPACE" get httproute grafana \
  -o jsonpath="{.status.parents[0].conditions[?(@.type=='ResolvedRefs')].status}" 2>/dev/null)"
check "HTTPRoute grafana is accepted by the Gateway" is_true "$(kctl -n "$K8S_NAMESPACE" get httproute grafana \
  -o jsonpath="{.status.parents[0].conditions[?(@.type=='Accepted')].status}" 2>/dev/null)"
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
lgtm_script='c() { curl -sS --max-time 15 "$@"; }
tid=$(od -An -N16 -tx1 /dev/urandom | tr -d " \n")
sid=$(od -An -N8 -tx1 /dev/urandom | tr -d " \n")
curl -sS -o /dev/null --max-time 15 --cacert /etc/polaris-trust/ca.crt -H "traceparent: 00-$tid-$sid-01" \
  https://id.polaris.local/realms/master/.well-known/openid-configuration
echo "trace $tid"
poll() { # <seconds> <command...>: runs the command until it prints something
  end=$(( $(date +%s) + $1 )); shift
  while [ "$(date +%s)" -lt "$end" ]; do out=$("$@" 2>/dev/null || true); [ -z "$out" ] || { printf %s "$out"; return; }; sleep 3; done
}
tempo_trace() { c http://tempo.observability.svc.cluster.local:3200/api/traces/$tid | grep -o "nginx-gateway" | head -1; }
loki_logs() { c -G http://loki.observability.svc.cluster.local:3100/loki/api/v1/query_range --data-urlencode "query={service_name=\"nginx-gateway\"} | trace_id=\"$tid\"" | grep -o "$tid" | head -1; }
span_metrics() { c -G http://prometheus.observability.svc.cluster.local:9090/api/v1/query --data-urlencode "query=count(traces_spanmetrics_calls_total)" | grep -o "\"value\":\[[0-9.]*,\"[1-9][0-9]*\"\]"; }
echo "tempo $(poll 90 tempo_trace)"
echo "loki $(poll 90 loki_logs)"
echo "spanmetrics $(poll 120 span_metrics | wc -c)"
echo "down $(c http://prometheus.observability.svc.cluster.local:9090/api/v1/targets | grep -o "\"health\":\"[a-z]*\"" | grep -vc "\"up\"")"
echo "targets $(c http://prometheus.observability.svc.cluster.local:9090/api/v1/targets | grep -o "\"health\":\"up\"" | wc -l)"
echo "rules $(c http://prometheus.observability.svc.cluster.local:9090/api/v1/rules | grep -o "\"name\":\"assistant-slo-burn\"" | head -1)"
echo "grafana $(c http://grafana.observability.svc.cluster.local:3000/api/health | grep -o "\"database\": *\"ok\"")"
echo "datasources $(c -u "admin:$GRAFANA_PASSWORD" http://grafana.observability.svc.cluster.local:3000/api/datasources | grep -o "\"uid\":\"[a-z]*\"" | tr "\n" " ")"
echo "dashboards $(c -u "admin:$GRAFANA_PASSWORD" "http://grafana.observability.svc.cluster.local:3000/api/search?type=dash-db" | grep -o "\"uid\"" | wc -l)"
echo "sso $(curl -sS -o /dev/null --max-time 15 --cacert /etc/polaris-trust/ca.crt -w "%{http_code} %{redirect_url}" https://grafana.polaris.local/login/generic_oauth | cut -d"&" -f1)"'
# Grafana's admin password is the realm placeholder DEFAULT_PASSWORD (platform-lgtm.sh); the Secret grafana-admin is in
# the other namespace, so the probe reads the source.
lgtm_env="$(secret_env GRAFANA_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)"
register_probes lgtm-smoke
if out="$(run_probe lgtm-smoke "$SMOKE_CURL_IMAGE" 100 "$lgtm_script" "$lgtm_env")"; then
  log "LGTM probe results:"$'\n'"$out"
  check "Tempo returns the trace of a request through the Gateway, with the span of service nginx-gateway" \
    grep -qx "tempo nginx-gateway" <<<"$out"
  check "Loki returns the Gateway's access log line of that trace by trace_id" \
    grep -Eqx "loki [0-9a-f]{32}" <<<"$out"
  check "Tempo's metrics generator wrote span-metrics to Prometheus" grep -Eqx "spanmetrics [1-9][0-9]*" <<<"$out"
  check "every Prometheus scrape target is up (Collector, Collector self-metrics, Tempo, Loki, Grafana, Prometheus)" \
    grep -qx "down 0" <<<"$out"
  check "Prometheus scrapes 11 targets" grep -qx "targets 11" <<<"$out"
  check "Prometheus loaded the Compose rule files (assistant-slo-burn)" grep -q 'assistant-slo-burn' <<<"$out"
  check "Grafana is healthy" grep -q 'grafana "database": *"ok"' <<<"$out"
  check "Grafana has the datasources prometheus, loki and tempo" \
    grep -Eq '^datasources ("uid":"(loki|prometheus|tempo)" ?){3}$' <<<"$out"
  check "Grafana has the 4 provisioned dashboards" grep -qx "dashboards 4" <<<"$out"
  check "https://grafana.polaris.local redirects to the Keycloak master realm for SSO" \
    grep -q "^sso 302 https://id.polaris.local/realms/master/protocol/openid-connect/auth" <<<"$out"
else
  fail "LGTM probe pod did not succeed: $(kctl -n "$K8S_NAMESPACE" logs lgtm-smoke 2>&1 | tail -5)"
fi
check "no Grafana Cloud credentials remain in the cluster (Secret grafana-cloud)" \
  [ -z "$(kctl -n "$K8S_NAMESPACE" get secret grafana-cloud --ignore-not-found -o name)" ]
