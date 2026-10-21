# shellcheck shell=bash
# shellcheck disable=SC2034 # used by the sections
# The smoke-probe test fixture (see probe-fixture.sh, which installs it). Source it; don't run it.

# --- Smoke probes under the NetworkPolicies (K11) ------------------------------------------------------------
# The namespace denies all traffic except the flows of §Topology (base/network-policies). The smoke test's own probe
# pods (curl, psql, redis-cli, k6) aren't part of that allow list, but check components directly (a database, Redis,
# management ports, pod addresses). For the duration of the run, a test fixture lets pods labelled
# polaris.local/smoke-probe=true reach every pod of the namespace (the callee's ingress) and lets them, and pods
# labelled polaris.local/smoke-probe=egress-only, send anywhere (the probe's own egress). It is removed on exit. An
# `egress-only` probe is outside the allow list on the callee's side only, so the K11 checks with it show that the
# callees' ingress rules lock it out. The `otel` and `observability` namespaces get the ingress half, so the probes can
# read the telemetry backends. The fixture is never part of the deployed manifests.
SMOKE_PROBE_KEY=polaris.local/smoke-probe
remove_smoke_policy() {
  kctl -n "$K8S_NAMESPACE" delete networkpolicy smoke-probes smoke-probes-egress --ignore-not-found --wait=false \
    >/dev/null 2>&1 || true
  for ns in "$OTEL_NAMESPACE" "$OBSERVABILITY_NAMESPACE"; do
    kctl -n "$ns" delete networkpolicy smoke-probes --ignore-not-found --wait=false >/dev/null 2>&1 || true
  done
}
