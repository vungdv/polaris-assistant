# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K3: edge (TLS, Gateway, DNS). Sends the traced request that k04-observability.sh looks for. Sourced by smoke.sh.


for deploy in cert-manager/cert-manager cert-manager/trust-manager nginx-gateway/nginx-gateway-fabric; do
  if kctl -n "${deploy%%/*}" rollout status "deployment/${deploy#*/}" --timeout=60s >/dev/null 2>&1; then
    pass "deployment $deploy is available"
  else
    fail "deployment $deploy is not available"
  fi
done


check "ClusterIssuer polaris-ca is ready" is_true "$(cond clusterissuer polaris-ca)"
check "certificate polaris-edge-tls is ready" is_true "$(cond -n "$K8S_NAMESPACE" certificate polaris-edge-tls)"
check "Gateway $GATEWAY_NAME is programmed" is_true "$(COND=Programmed cond -n "$K8S_NAMESPACE" gateway "$GATEWAY_NAME")"

# The trust bundle reached the namespace as PEM and as a JKS store (JKS magic number 0xFEEDFEED, base64 /u3+7Q).
pem="$(kctl -n "$K8S_NAMESPACE" get configmap "$CA_BUNDLE" -o jsonpath='{.data.ca\.crt}' 2>/dev/null || true)"
jks="$(kctl -n "$K8S_NAMESPACE" get configmap "$CA_BUNDLE" -o jsonpath='{.binaryData.truststore\.jks}' 2>/dev/null || true)"
check "configmap $CA_BUNDLE has the PEM bundle ca.crt" grep -q "BEGIN CERTIFICATE" <<<"$pem"
check "configmap $CA_BUNDLE has the JKS trust store truststore.jks" grep -q "^/u3+7Q" <<<"$jks"

# From a pod that meets Pod Security `restricted`, with the bundle mounted: each public host resolves to the Gateway
# Service (CoreDNS rewrite), its certificate verifies against the bundle, and a path no app serves answers 404
# (NGINX Gateway Fabric serves every HTTPS listener host and answers 404 until a route matches; Keycloak 404s on this
# path too). polaris.local is the exception since K8: its `/` route sends every path to polaris, which answers an
# unauthenticated request with 401 before routing it. Plain HTTP redirects to HTTPS. The probe prints one line per
# request: <url> <http_code> <remote_ip> <redirect_url>.
# Last, it sends one request with a fresh W3C traceparent through a traced route (K4, checked below), and prints
# `traced <url> <http_code>`.
trace_id="$(hex 16)"
parent_span_id="$(hex 8)"
traceparent="00-$trace_id-$parent_span_id-01"
traced_url="http://polaris.local/k4-trace-smoke/$trace_id"
gateway_ip="$(kctl -n "$K8S_NAMESPACE" get service "$GATEWAY_SERVICE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
probe=edge-smoke
kctl -n "$K8S_NAMESPACE" delete pod "$probe" --ignore-not-found --wait=true >/dev/null
register_probes "$probe"
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
    $SMOKE_PROBE_KEY: "true"
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
          curl -sS -o /dev/null --max-time 10 -H "traceparent: $traceparent" \\
            -w "traced $traced_url %{http_code}\\n" "$traced_url" || echo "traced $traced_url curl-exit-\$?"
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
    expected=404
    [ "$host" != polaris.local ] || expected=401
    # grafana.polaris.local routes every path to Grafana (K12), which sends an anonymous request to its login page.
    [ "$host" != grafana.polaris.local ] || expected=302
    if [ "$code" = "$expected" ]; then
      pass "https://$host certificate verifies against $CA_BUNDLE and an unrouted path returns $expected"
    else
      fail "https://$host$unrouted returned '$code' (curl verifies the certificate against $CA_BUNDLE), expected $expected"
    fi
  done
  read -r _ code _ location <<<"$(grep "^http://polaris.local/" <<<"$results" || true)"
  if [ "$code" = 301 ] && [ "$location" = "https://polaris.local/some/path" ]; then
    pass "http://polaris.local redirects to https with 301"
  else
    fail "http://polaris.local/some/path returned '$code' to '$location', expected 301 to https://polaris.local/some/path"
  fi
  read -r _ _ traced_code <<<"$(grep "^traced " <<<"$results" || true)"
  check "the traced request $traced_url went through the Gateway (301)" [ "$traced_code" = 301 ]
else
  fail "edge probe pod did not succeed: $(kctl -n "$K8S_NAMESPACE" logs "$probe" 2>&1 | tail -5)"
fi
