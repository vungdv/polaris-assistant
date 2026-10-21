# shellcheck shell=bash
# kubectl-level helpers shared by the sections. Source it; don't run it.

cond() { kctl get "$@" -o jsonpath="{.status.conditions[?(@.type=='${COND:-Ready}')].status}" 2>/dev/null; }
is_true() { [ "$1" = True ]; }
quiet() { "$@" >/dev/null 2>&1; }

# all_ready <deployment>: every replica is ready, and there are 2 to 4 of them (the HorizontalPodAutoscaler's range, K11).
all_ready() {
  local ready
  ready="$(kctl -n "$K8S_NAMESPACE" get deployment "$1" -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null)"
  [[ "$ready" =~ ^([2-4])/([2-4])$ ]] && [ "${BASH_REMATCH[1]}" = "${BASH_REMATCH[2]}" ]
}
# pdb_allows <name>: the PodDisruptionBudget keeps 1 pod (minAvailable) and allows evicting all the other ready ones.
pdb_allows() {
  local ready
  ready="$(kctl -n "$K8S_NAMESPACE" get deployment "$1" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)"
  [ "$(kctl -n "$K8S_NAMESPACE" get pdb "$1" -o jsonpath='{.spec.minAvailable} {.status.disruptionsAllowed}' \
    2>/dev/null)" = "1 $((${ready:-0} - 1))" ]
}

secret_env() { # <variable> <secret> <key>
  printf '        - name: %s\n          valueFrom:\n            secretKeyRef:\n              name: %s\n              key: %s\n' "$@"
}

secret_value() { kctl -n "$K8S_NAMESPACE" get secret "$1" -o jsonpath="{.data.$2}" 2>/dev/null | base64 -d 2>/dev/null || true; }
