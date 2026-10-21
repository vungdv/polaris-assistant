# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# The smoke probes under the NetworkPolicies (K11): installs the test fixture for the run. Sourced by smoke.sh.

# --- Smoke probes under the NetworkPolicies (K11) ------------------------------------------------------------
# The namespace denies all traffic except the flows of §Topology (base/network-policies). The smoke test's own probe
# pods (curl, psql, redis-cli, k6) aren't part of that allow list, but check components directly (a database, Redis,
# management ports, pod addresses). For the duration of the run, a test fixture lets pods labelled
# polaris.local/smoke-probe=true reach every pod of the namespace (the callee's ingress) and lets them, and pods
# labelled polaris.local/smoke-probe=egress-only, send anywhere (the probe's own egress). It is removed on exit. An
# `egress-only` probe is outside the allow list on the callee's side only, so the K11 checks with it show that the
# callees' ingress rules lock it out. The `otel` and `observability` namespaces get the ingress half, so the probes can
# read the telemetry backends. The fixture is never part of the deployed manifests.
on_cleanup remove_smoke_policy
kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: smoke-probes
  labels:
    app.kubernetes.io/name: smoke-probes
spec:
  podSelector: {}
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              $SMOKE_PROBE_KEY: "true"
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: smoke-probes-egress
  labels:
    app.kubernetes.io/name: smoke-probes
spec:
  podSelector:
    matchExpressions:
      - key: $SMOKE_PROBE_KEY
        operator: In
        values: ["true", "egress-only"]
  policyTypes:
    - Egress
  egress:
    - {}
EOF
for ns in "$OTEL_NAMESPACE" "$OBSERVABILITY_NAMESPACE"; do
  kctl -n "$ns" apply -f - >/dev/null <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: smoke-probes
spec:
  podSelector: {}
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: $K8S_NAMESPACE
          podSelector:
            matchLabels:
              $SMOKE_PROBE_KEY: "true"
EOF
done
