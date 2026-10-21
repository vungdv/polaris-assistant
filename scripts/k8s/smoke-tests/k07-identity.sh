# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K7: identity (Keycloak). Sourced by smoke.sh.

# --- K7: identity (Keycloak) ----------------------------------------------------------------------------------
# The operator, the server and the realm import are ready. The Keycloak pods run in the namespace that enforces
# `restricted`, so their being ready shows they pass it.
check "deployment $K8S_NAMESPACE/$KEYCLOAK_OPERATOR (Keycloak Operator) is available" \
  quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$KEYCLOAK_OPERATOR" --timeout=60s
check "Keycloak $KEYCLOAK is ready" is_true "$(cond -n "$K8S_NAMESPACE" keycloak.k8s.keycloak.org "$KEYCLOAK")"
for realm in "${KEYCLOAK_REALM_IMPORTS[@]}"; do
  check "KeycloakRealmImport $realm is done" \
    is_true "$(COND=Done cond -n "$K8S_NAMESPACE" keycloakrealmimport.k8s.keycloak.org "$realm")"
done

# From a `restricted` probe pod, through the CoreDNS rewrite and the Gateway, verifying TLS against the bundle, the
# same way the apps will (K8-K10):
#   issuer        the polaris realm's OIDC discovery document names the public issuer (TR-K8)
#   password      a password grant for the seeded `testuser` (public client polaris-app, direct access grants on)
#                 returns a token with that issuer: DEFAULT_PASSWORD was substituted into the KeycloakRealmImport
#   emulator      a client_credentials grant for polaris-fulfilment-emulator with POLARIS_FULFILMENT_EMULATOR_SECRET
#   grafana       a password grant in the master realm (admin-cli) for `grafana-admin`: master-realm.json was imported,
#                 with DEFAULT_PASSWORD substituted
#   admin         the operator's bootstrap admin gets an admin token and finds master's `grafana` client
#   health/metrics the management port (9000) answers in-cluster
# Each line is `<check> <values...>`. The script is single-quoted, so its variables are the pod's own.
kc_issuer="https://id.polaris.local/realms/polaris"
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
kc_script='kc=https://id.polaris.local
c() { curl -sS --max-time 15 --cacert /etc/polaris-trust/ca.crt "$@"; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
claims() { p=$(printf %s "$1" | cut -d. -f2 | tr "_-" "/+"); while [ $((${#p} % 4)) -ne 0 ]; do p="$p="; done; printf %s "$p" | base64 -d; }
token() { realm=$1; shift; c "$@" "$kc/realms/$realm/protocol/openid-connect/token" | field access_token; }
echo "issuer $(c "$kc/realms/polaris/.well-known/openid-configuration" | field issuer)"
t=$(token polaris -d grant_type=password -d client_id=polaris-app -d username=testuser --data-urlencode "password=$DEFAULT_PASSWORD")
echo "password $(claims "$t" | field iss) $(claims "$t" | field preferred_username)"
t=$(token polaris -d grant_type=client_credentials -d client_id=polaris-fulfilment-emulator --data-urlencode "client_secret=$EMULATOR_SECRET")
echo "emulator $(claims "$t" | field azp)"
t=$(token master -d grant_type=password -d client_id=admin-cli -d username=grafana-admin --data-urlencode "password=$DEFAULT_PASSWORD")
echo "grafana $(claims "$t" | field iss) $(claims "$t" | field azp)"
t=$(token master -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$KC_ADMIN_USER" --data-urlencode "password=$KC_ADMIN_PASSWORD")
echo "admin $(c -H "Authorization: Bearer $t" "$kc/admin/realms/master/clients?clientId=grafana" | field clientId)"
echo "health $(curl -sS -o /dev/null --max-time 10 -w "%{http_code}" http://keycloak-service:9000/health/ready)"
echo "metrics $(curl -sS --max-time 10 http://keycloak-service:9000/metrics | grep -c "^jvm_")"'
kc_env="$(secret_env DEFAULT_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD
secret_env EMULATOR_SECRET "$KEYCLOAK_REALM_SECRET" POLARIS_FULFILMENT_EMULATOR_SECRET
secret_env KC_ADMIN_USER "$KEYCLOAK-initial-admin" username
secret_env KC_ADMIN_PASSWORD "$KEYCLOAK-initial-admin" password)"
register_probes keycloak-smoke
if out="$(run_probe keycloak-smoke "$SMOKE_CURL_IMAGE" 100 "$kc_script" "$kc_env")"; then
  log "Keycloak probe results:"$'\n'"$out"
  check "OIDC discovery from a pod names the issuer $kc_issuer" grep -qx "issuer $kc_issuer" <<<"$out"
  check "a password grant for seeded user testuser (client polaris-app) issues a token from $kc_issuer" \
    grep -qx "password $kc_issuer testuser" <<<"$out"
  check "client polaris-fulfilment-emulator gets a token with the secret from $KEYCLOAK_REALM_SECRET" \
    grep -qx "emulator polaris-fulfilment-emulator" <<<"$out"
  # The imported master realm has no `basic` scope on admin-cli, so the token carries no preferred_username; that it
  # is issued at all proves grafana-admin exists with DEFAULT_PASSWORD.
  check "the master realm was imported: grafana-admin gets a token with DEFAULT_PASSWORD" \
    grep -qx "grafana https://id.polaris.local/realms/master admin-cli" <<<"$out"
  check "the operator's bootstrap admin ($KEYCLOAK-initial-admin) reads master's grafana client" \
    grep -qx "admin grafana" <<<"$out"
  check "Keycloak health/ready answers 200 on the management port" grep -qx "health 200" <<<"$out"
  check "Keycloak serves Prometheus metrics on the management port" grep -Eq "^metrics [1-9]" <<<"$out"
else
  fail "Keycloak probe did not succeed: $(tail -5 <<<"$out")"
fi

# From the host, through kind's port mapping of the Gateway's HTTPS NodePort (443 by default, another port with
# KIND_CONFIG), as a browser reaches it: curl pins id.polaris.local to 127.0.0.1 (no /etc/hosts needed) and verifies
# the certificate against the cluster root CA.
detect_host_port
root_ca="$(mktemp)"
kctl -n cert-manager get secret polaris-root-ca -o jsonpath='{.data.tls\.crt}' 2>/dev/null | base64 -d >"$root_ca" || true
host_issuer="$(curl -sS --max-time 15 --cacert "$root_ca" --resolve "id.polaris.local:${host_port:-443}:127.0.0.1" \
  "https://id.polaris.local:${host_port:-443}/realms/polaris/.well-known/openid-configuration" 2>&1 |
  sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p')"
rm -f "$root_ca"
check "OIDC discovery from the host (127.0.0.1:${host_port:-443}) names the issuer $kc_issuer" \
  [ "$host_issuer" = "$kc_issuer" ]

# Keycloak exports its spans to the Collector (TR-K9): the token requests above show up in the debug exporter as
# spans of service.name keycloak.
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90}))
kc_spans=""
while [ "$SECONDS" -lt "$deadline" ]; do
  kc_spans="$(kctl -n "$OTEL_NAMESPACE" logs "deployment/$OTEL_COLLECTOR" --since=5m 2>/dev/null |
    grep -c "service.name: Str(keycloak)" || true)"
  [ "${kc_spans:-0}" -gt 0 ] && break
  sleep 3
done
check "the Collector received Keycloak spans (service.name keycloak)" [ "${kc_spans:-0}" -gt 0 ]
