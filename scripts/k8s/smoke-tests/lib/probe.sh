# shellcheck shell=bash
# Probe pods that meet Pod Security `restricted`. Source it; don't run it.

# probe_label: the value of $SMOKE_PROBE_KEY on the probe pods below (run_probe); "egress-only" leaves a probe outside
# the callees' allow list.
probe_label=true

# run_probe <name> <image> <uid> <script> [<env>]: runs <script> with `sh -c` in a pod that meets Pod Security
# `restricted` (as <uid>, read-only root filesystem, no capabilities, no service account token), with the trust bundle
# $CA_BUNDLE at /etc/polaris-trust, and prints its output.
# <env> is an optional container `env:` list, already indented. Returns non-zero when the pod doesn't succeed in 180s.
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
    $SMOKE_PROBE_KEY: "$probe_label"
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
