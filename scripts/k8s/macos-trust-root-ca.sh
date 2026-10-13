#!/usr/bin/env bash
# macOS only: trusts the kind cluster's root CA (cert-manager Secret polaris-root-ca) in the System keychain, after
# removing the root CAs of earlier clusters (each new cluster generates a new one). Needs sudo. Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

[ "$(uname -s)" = Darwin ] || die "trust-root-ca.sh supports macOS only"

CA_NAME="Polaris Local Root CA"
KEYCHAIN="/Library/Keychains/System.keychain"

ensure_tools kubectl
ca_file="$(mktemp)"
trap 'rm -f "$ca_file"' EXIT

kctl --request-timeout=10s -n cert-manager get secret polaris-root-ca -o jsonpath='{.data.tls\.crt}' 2>/dev/null |
  base64 -d >"$ca_file" || true
openssl x509 -in "$ca_file" -noout 2>/dev/null ||
  die "no root CA found in cluster '$KIND_CLUSTER_NAME' (context $KUBE_CONTEXT); run 'make k8s-up' first"

fingerprint="$(openssl x509 -in "$ca_file" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d :)"

current=false
for hash in $(security find-certificate -a -Z -c "$CA_NAME" "$KEYCHAIN" 2>/dev/null | awk '/^SHA-1 hash:/ { print $3 }'); do
  if [ "$hash" = "$fingerprint" ]; then
    current=true
  else
    log "removing the root CA of an earlier cluster ($hash) from the System keychain (sudo)"
    sudo security delete-certificate -Z "$hash" "$KEYCHAIN"
  fi
done

if $current; then
  log "cluster root CA ($fingerprint) is already trusted"
else
  log "trusting the cluster root CA ($fingerprint) in the System keychain (sudo)"
  sudo security add-trusted-cert -d -r trustRoot -k "$KEYCHAIN" "$ca_file"
fi
log "done: restart Safari if it still shows a certificate warning"
