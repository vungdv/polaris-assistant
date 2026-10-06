#!/usr/bin/env bash
#
# setup-local-https.sh
#
# Sets up local HTTPS dev environment for polaris.local / id.polaris.local
# using mkcert for locally-trusted TLS certificates.
#
# Target platform: macOS (Apple Silicon / M1) with Homebrew.
# Safe to re-run — all steps are idempotent.
#
# Usage:
#   ./setup-local-https.sh
#
set -euo pipefail

# ---- Config ------------------------------------------------------------
DOMAINS=("polaris.local" "id.polaris.local" "grafana.polaris.local")
HOSTS_FILE="/etc/hosts"
CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/nginx/certs"
CERT_BASENAME="polaris.local"   # mkcert names output after the first domain

# ---- Helpers -------------------------------------------------------------
log()  { printf "\033[1;34m[setup]\033[0m %s\n" "$1"; }
ok()   { printf "\033[1;32m[ ok ]\033[0m %s\n" "$1"; }
warn() { printf "\033[1;33m[warn]\033[0m %s\n" "$1"; }
err()  { printf "\033[1;31m[fail]\033[0m %s\n" "$1" >&2; }

require_macos() {
  if [[ "$(uname -s)" != "Darwin" ]]; then
    err "This script targets macOS. Detected: $(uname -s)."
    err "Adapt the package-manager and hosts-file steps for your OS."
    exit 1
  fi
}

# ---- Step 1: Homebrew -----------------------------------------------------
ensure_homebrew() {
  if ! command -v brew >/dev/null 2>&1; then
    log "Homebrew not found. Installing..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    # Apple Silicon default prefix
    if [[ -d /opt/homebrew/bin ]]; then
      eval "$(/opt/homebrew/bin/brew shellenv)"
    fi
  else
    ok "Homebrew already installed."
  fi
}

# ---- Step 2: mkcert --------------------------------------------------------
ensure_mkcert() {
  if ! command -v mkcert >/dev/null 2>&1; then
    log "Installing mkcert..."
    brew install mkcert nss
  else
    ok "mkcert already installed ($(mkcert -version 2>/dev/null || echo 'version unknown'))."
  fi

  log "Installing mkcert local CA into system/browser trust stores..."
  mkcert -install
  ok "Local CA installed."
}

# ---- Step 3: /etc/hosts entries --------------------------------------------
ensure_hosts_entries() {
  log "Checking ${HOSTS_FILE} for required domain entries..."
  local needs_sudo=false

  for domain in "${DOMAINS[@]}"; do
    if grep -qE "^\s*127\.0\.0\.1\s+${domain}(\s|$)" "$HOSTS_FILE"; then
      ok "${domain} already mapped to 127.0.0.1."
    else
      needs_sudo=true
    fi
  done

  if [[ "$needs_sudo" == true ]]; then
    log "Adding missing entries to ${HOSTS_FILE} (requires sudo)..."
    {
      echo ""
      echo "# Added by setup-local-https.sh for Polaris local dev"
      for domain in "${DOMAINS[@]}"; do
        if ! grep -qE "^\s*127\.0\.0\.1\s+${domain}(\s|$)" "$HOSTS_FILE"; then
          echo "127.0.0.1   ${domain}"
        fi
      done
    } | sudo tee -a "$HOSTS_FILE" > /dev/null
    ok "Hosts file updated."
  fi
}

# ---- Step 4: Generate certs -------------------------------------------------
generate_certs() {
  mkdir -p "$CERT_DIR"
  log "Generating certs into ${CERT_DIR} ..."

  local cert_file="${CERT_DIR}/polaris.local+1.pem"
  local key_file="${CERT_DIR}/polaris.local+1-key.pem"

  if [[ -f "$cert_file" && -f "$key_file" ]]; then
    local sans
    sans=$(openssl x509 -in "$cert_file" -noout -text 2>/dev/null | grep -A 2 "Subject Alternative Name" || true)
    local all_covered=true
    for domain in "${DOMAINS[@]}"; do
      if [[ "$sans" != *"$domain"* ]]; then
        all_covered=false
        break
      fi
    done
    if [[ "$all_covered" == true ]]; then
      ok "Certs already exist at ${cert_file} and cover all domains. Skipping generation."
      return
    fi
    log "Existing cert does not cover all required domains. Regenerating..."
  fi

  (cd "$CERT_DIR" && mkcert -cert-file "$cert_file" -key-file "$key_file" "polaris.local" "*.polaris.local" "${DOMAINS[@]}")
  ok "Certs generated: ${cert_file}"
}

# ---- Step 5: Export CA root for Docker/JVM trust ---------------------------
export_ca_root() {
  local ca_root
  ca_root="$(mkcert -CAROOT)"
  local dest="${CERT_DIR}/../rootCA.pem"

  if [[ -f "${ca_root}/rootCA.pem" ]]; then
    cp "${ca_root}/rootCA.pem" "$dest"
    ok "Copied mkcert root CA to ${dest} (used by Dockerfile to trust certs inside containers)."
  else
    warn "Could not find rootCA.pem in ${ca_root}. Run 'mkcert -install' manually and re-run this script."
  fi
}

# ---- Step 6: Summary ---------------------------------------------------------
print_summary() {
  echo ""
  log "Setup complete. Summary:"
  echo "  Domains mapped in /etc/hosts:"
  for domain in "${DOMAINS[@]}"; do
    echo "    - https://${domain}"
  done
  echo "  Certs directory: ${CERT_DIR}"
  echo "  CA root copied to: ${CERT_DIR}/../rootCA.pem"
  echo ""
  echo "  Next steps:"
  echo "    1. docker compose up --build"
  echo "    2. Visit https://polaris.local/swagger-ui/index.html"
  echo "    3. Visit https://grafana.polaris.local for Grafana Dashboards"
  echo "    4. Click Authorize / Sign In with Keycloak — should redirect without cert warnings"
  echo ""
}

# ---- Step 7: Build JVM trust store using Docker -----------------------------

build_jvm_truststore() {
local root_ca="${CERT_DIR}/../rootCA.pem"
local truststore="${CERT_DIR}/../truststore.jks"
local truststore_password="${TRUSTSTORE_PASSWORD:-changeit}"
local docker_image="eclipse-temurin:21-jdk"

if [[ ! -f "$root_ca" ]]; then
warn "Could not find ${root_ca}. Skipping JVM trust store generation."
return
fi

if ! command -v docker >/dev/null 2>&1; then
warn "Docker not found. Skipping JVM trust store generation."
return
fi

log "Building JVM trust store using Docker image ${docker_image}..."

docker run --rm 
-v "${CERT_DIR}/..:/work" 
"${docker_image}" 
keytool 
-importcert 
-noprompt 
-trustcacerts 
-alias polaris-local-root-ca 
-file /work/rootCA.pem 
-keystore /work/truststore.jks 
-storepass "${truststore_password}"

ok "JVM trust store generated: ${truststore}"
}

# ---- Main -------------------------------------------------------------------
main() {
  require_macos
  ensure_homebrew
  ensure_mkcert
  ensure_hosts_entries
  generate_certs
  export_ca_root
  build_jvm_truststore
  print_summary
}

main "$@"