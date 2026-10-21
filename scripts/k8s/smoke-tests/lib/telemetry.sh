# shellcheck shell=bash
# shellcheck disable=SC2154 # uses variables the sections set
# W3C trace IDs and the Collector's debug exporter, where the sections read the spans and log records back.
# Source it; don't run it.

hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

# The traced request above must reach the Collector's debug exporter twice, each carrying the Kubernetes resource
# attributes of the Gateway's data-plane pod (TR-K9):
#   - as a span of the trace in the request's traceparent, whose parent is the request's span: the Gateway continues
#     the incoming W3C trace context (OTLP from nginx, attributes from k8s_attributes),
#   - as the JSON access log line with that trace_id (stdout -> log agent -> Collector).
# The debug exporter prints each batch as `ResourceSpans #n` / `ResourceLog #n` blocks; telemetry_block prints the
# blocks of one kind that contain the trace ID. nginx exports spans every 5s and both hops batch for 2s, so the checks
# poll the Collector's log for up to OTEL_SMOKE_TIMEOUT seconds.
telemetry_block() { # <ResourceSpans|ResourceLog> [<trace id>, default: $trace_id]
  kctl -n "$OTEL_NAMESPACE" logs "deployment/$OTEL_COLLECTOR" --since=15m 2>/dev/null |
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

# span_rows <blocks>: one line per span of the trace, `<trace id> <parent id, or -> <span id> <name>`, from the debug
# exporter's `Trace ID`, `Parent ID`, `ID` and `Name` lines.
span_rows() {
  awk '/^ +Trace ID +:/ { t = $NF } /^ +Parent ID +:/ { p = ($NF == ":" ? "-" : $NF) } /^ +ID +:/ { i = $NF }
    /^ +Name +:/ { n = $0; sub(/^ +Name +: */, "", n); print t, p, i, n }' <<<"$1" | awk -v t="$trace_id" '$1 == t'
}
# resource_blocks <regex>: the span blocks of $spans whose resource has a line matching <regex>, from that line on.
resource_blocks() { awk -v r="$1" '/ResourceSpans #/ { keep = 0 } $0 ~ r { keep = 1 } keep' <<<"$spans"; }
