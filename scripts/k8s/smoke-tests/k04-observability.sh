# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K4: observability pipeline. Looks for the traced request that k03-edge.sh sent. Sourced by smoke.sh.

check "deployment $OTEL_NAMESPACE/$OTEL_COLLECTOR is available" \
  quiet kctl -n "$OTEL_NAMESPACE" rollout status "deployment/$OTEL_COLLECTOR" --timeout=60s
check "daemonset $OTEL_NAMESPACE/$OTEL_AGENT is ready" \
  quiet kctl -n "$OTEL_NAMESPACE" rollout status "daemonset/$OTEL_AGENT" --timeout=60s
check "Service $OTEL_COLLECTOR exposes OTLP gRPC 4317, OTLP HTTP 4318, its metrics 8888 and the Prometheus exporter 8889" [ \
  "$(kctl -n "$OTEL_NAMESPACE" get service "$OTEL_COLLECTOR" -o jsonpath='{.spec.ports[*].port}' 2>/dev/null)" = \
  "8888 4317 4318 8889" ]
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
