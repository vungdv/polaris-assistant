# shellcheck shell=bash
# k6 probe pods (tests/perf/api-test.js). Source it; don't run it.

# tests/perf/api-test.js (k6) against https://polaris.local from a `restricted` pod, as `make test-perf` runs it
# against Compose: same script, same thresholds, the token from Keycloak through the Gateway. k6 also writes every
# request to a CSV file, from which the pod prints `server-errors <n>`: responses with a 5xx status or none at all.
# Runs in the background with start_k6 <name> <extra k6 args>; wait_k6 <name> <timeout> waits and prints the output.

start_k6() {
  local name="$1" args="$2"
  kctl -n "$K8S_NAMESPACE" delete pod "$name" --ignore-not-found --wait=true >/dev/null
  kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $name
  labels:
    app.kubernetes.io/name: $name
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
      command: ["sh", "-c"]
      # k6 runs in the background so that \`touch /tmp/stop\` (kubectl exec) can end an open-ended run gracefully.
      args:
        - |
          k6 run $args --out csv=/tmp/k6.csv /scripts/api-test.js &
          pid=\$!
          while kill -0 \$pid 2>/dev/null; do
            if [ -f /tmp/stop ]; then kill -INT \$pid; break; fi
            sleep 1
          done
          wait \$pid
          rc=\$?
          awk -F, 'NR == 1 { for (i = 1; i <= NF; i++) if (\$i == "status") s = i; next }
            \$1 == "http_reqs" && (\$s >= 500 || \$s == 0) { n++ } END { print "server-errors " n + 0 }' /tmp/k6.csv
          exit \$rc
      env:
        - name: K6_NO_USAGE_REPORT
          value: "true"
        - name: BASE_URL
          value: https://polaris.local
        - name: CLIENT_ID
          value: polaris-local
        - name: USERNAME
          value: testuser
        - name: PASSWORD
          valueFrom:
            secretKeyRef:
              name: $KEYCLOAK_REALM_SECRET
              key: DEFAULT_PASSWORD
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
        name: k8-perf-scripts
        items:
          - key: api-test.js
            path: api-test.js
          - key: auth.js
            path: common/auth.js
          - key: index.js
            path: common/index.js
    - name: tmp
      emptyDir:
        sizeLimit: 256Mi
EOF
}
wait_k6() {
  local name="$1" phase="" deadline=$((SECONDS + $2))
  while [ "$SECONDS" -lt "$deadline" ]; do
    phase="$(kctl -n "$K8S_NAMESPACE" get pod "$name" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in Succeeded | Failed) break ;; esac
    sleep 3
  done
  echo "k6 pod phase ${phase:-(none)}"
  kctl -n "$K8S_NAMESPACE" logs "$name" 2>&1 | grep -v '^time=' || true
  kctl -n "$K8S_NAMESPACE" delete pod "$name" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  [ "$phase" = Succeeded ]
}
