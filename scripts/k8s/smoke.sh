#!/usr/bin/env bash
# Smoke test for the cluster brought up by `make k8s-up`. Each slice adds its checks below.
# Runs every check, then exits non-zero if any failed.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kubectl

failures=0
pass() { log "PASS: $1"; }
fail() {
  log "FAIL: $1"
  failures=$((failures + 1))
}

ns_label() { kctl get namespace "$K8S_NAMESPACE" -o jsonpath="{.metadata.labels.pod-security\.kubernetes\.io/$1}"; }

# --- K1: namespace with Pod Security restricted ---------------------------------------------------------------
if kctl get namespace "$K8S_NAMESPACE" >/dev/null 2>&1; then
  pass "namespace $K8S_NAMESPACE exists"
  for mode in enforce warn audit; do
    level="$(ns_label "$mode")"
    if [ "$level" = restricted ]; then
      pass "namespace $K8S_NAMESPACE has Pod Security $mode=restricted"
    else
      fail "namespace $K8S_NAMESPACE has Pod Security $mode='$level', expected restricted"
    fi
  done
  # Admission really enforces it: a privileged pod is rejected (server-side dry run, nothing is created).
  if out="$(kctl -n "$K8S_NAMESPACE" run psa-smoke --image=busybox --restart=Never --dry-run=server \
    --overrides='{"spec":{"containers":[{"name":"psa-smoke","image":"busybox","securityContext":{"privileged":true}}]}}' 2>&1)"; then
    fail "a privileged pod was admitted to $K8S_NAMESPACE"
  elif [[ "$out" == *"violates PodSecurity"* ]]; then
    pass "Pod Security admission rejects a privileged pod in $K8S_NAMESPACE"
  else
    fail "privileged pod dry run failed for another reason: $out"
  fi
else
  fail "namespace $K8S_NAMESPACE does not exist"
fi

# --- K3: edge (TLS, Gateway, DNS) -----------------------------------------------------------------------------
cond() { kctl get "$@" -o jsonpath="{.status.conditions[?(@.type=='${COND:-Ready}')].status}" 2>/dev/null; }

for deploy in cert-manager/cert-manager cert-manager/trust-manager nginx-gateway/nginx-gateway-fabric; do
  if kctl -n "${deploy%%/*}" rollout status "deployment/${deploy#*/}" --timeout=60s >/dev/null 2>&1; then
    pass "deployment $deploy is available"
  else
    fail "deployment $deploy is not available"
  fi
done

# check <description> <command...>: pass when the command succeeds.
check() {
  local what="$1"
  shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}
is_true() { [ "$1" = True ]; }

check "ClusterIssuer polaris-ca is ready" is_true "$(cond clusterissuer polaris-ca)"
check "certificate polaris-edge-tls is ready" is_true "$(cond -n "$K8S_NAMESPACE" certificate polaris-edge-tls)"
check "Gateway $GATEWAY_NAME is programmed" is_true "$(COND=Programmed cond -n "$K8S_NAMESPACE" gateway "$GATEWAY_NAME")"

# The trust bundle reached the namespace as PEM and as a JKS store (JKS magic number 0xFEEDFEED, base64 /u3+7Q).
pem="$(kctl -n "$K8S_NAMESPACE" get configmap "$CA_BUNDLE" -o jsonpath='{.data.ca\.crt}' 2>/dev/null || true)"
jks="$(kctl -n "$K8S_NAMESPACE" get configmap "$CA_BUNDLE" -o jsonpath='{.binaryData.truststore\.jks}' 2>/dev/null || true)"
check "configmap $CA_BUNDLE has the PEM bundle ca.crt" grep -q "BEGIN CERTIFICATE" <<<"$pem"
check "configmap $CA_BUNDLE has the JKS trust store truststore.jks" grep -q "^/u3+7Q" <<<"$jks"

# From a pod that meets Pod Security `restricted`, with the bundle mounted: each public host resolves to the Gateway
# Service (CoreDNS rewrite), its certificate verifies against the bundle, and a path no HTTPRoute serves answers 404
# (the placeholder: NGINX Gateway Fabric serves every HTTPS listener host and answers 404 until a route matches; the
# 404 stays true once K7-K12 attach app routes, whose apps 404 on this path too). Plain HTTP redirects to HTTPS. The probe prints one line per request: <url> <http_code> <remote_ip> <redirect_url>.
gateway_ip="$(kctl -n "$K8S_NAMESPACE" get service "$GATEWAY_SERVICE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
probe=edge-smoke
kctl -n "$K8S_NAMESPACE" delete pod "$probe" --ignore-not-found --wait=true >/dev/null
trap 'kctl -n "$K8S_NAMESPACE" delete pod "$probe" --ignore-not-found --wait=false >/dev/null 2>&1 || true' EXIT
urls=()
unrouted=/k3-edge-smoke/unrouted
for host in "${EDGE_HOSTS[@]}"; do urls+=("https://$host$unrouted"); done
urls+=("http://polaris.local/some/path")
kctl -n "$K8S_NAMESPACE" apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $probe
  labels:
    app.kubernetes.io/name: $probe
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: curl
      image: $SMOKE_CURL_IMAGE
      command: ["sh", "-c"]
      args:
        - |
          for url in ${urls[*]}; do
            curl -sS -o /dev/null --max-time 10 --cacert /etc/polaris-trust/ca.crt \\
              -w "\$url %{http_code} %{remote_ip} %{redirect_url}\\n" "\$url" || echo "\$url curl-exit-\$?"
          done
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
if kctl -n "$K8S_NAMESPACE" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$probe" --timeout=180s >/dev/null; then
  results="$(kctl -n "$K8S_NAMESPACE" logs "$probe")"
  log "edge probe results:"$'\n'"$results"
  for host in "${EDGE_HOSTS[@]}"; do
    read -r _ code ip _ <<<"$(grep "^https://$host$unrouted " <<<"$results" || true)"
    if [ -n "$gateway_ip" ] && [ "$ip" = "$gateway_ip" ]; then
      pass "https://$host resolves to the Gateway Service $GATEWAY_SERVICE ($gateway_ip) from a pod"
    else
      fail "https://$host resolved to '$ip', expected the Gateway Service $GATEWAY_SERVICE ('$gateway_ip')"
    fi
    if [ "$code" = 404 ]; then
      pass "https://$host certificate verifies against $CA_BUNDLE and an unrouted path returns 404"
    else
      fail "https://$host$unrouted returned '$code' (curl verifies the certificate against $CA_BUNDLE), expected 404"
    fi
  done
  read -r _ code _ location <<<"$(grep "^http://polaris.local/" <<<"$results" || true)"
  if [ "$code" = 301 ] && [ "$location" = "https://polaris.local/some/path" ]; then
    pass "http://polaris.local redirects to https with 301"
  else
    fail "http://polaris.local/some/path returned '$code' to '$location', expected 301 to https://polaris.local/some/path"
  fi
else
  fail "edge probe pod did not succeed: $(kctl -n "$K8S_NAMESPACE" logs "$probe" 2>&1 | tail -5)"
fi

if [ "$failures" -gt 0 ]; then
  log "$failures smoke check(s) failed"
  exit 1
fi
log "all smoke checks passed"
