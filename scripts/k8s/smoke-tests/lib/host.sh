# shellcheck shell=bash
# From the host, through kind's port mapping of the Gateway: `make` targets that run against the cluster. Source it; don't run it.

# detect_host_port: sets host_port to the host port kind maps to the Gateway's HTTPS NodePort (443 by default,
# another port with KIND_CONFIG).
detect_host_port() {
  host_port="$(docker port "$KIND_CLUSTER_NAME-control-plane" 30443/tcp 2>/dev/null | sed -n '1s/.*://p')"
}

# `make seed-shoppers` and `make chat-scenarios` from the host, through kind's port mapping of the Gateway (host_port,
# K7), as on Compose: the k6 container reaches id.polaris.local and polaris.local on the host gateway. The Keycloak
# admin is the operator's bootstrap admin (Secret keycloak-initial-admin), not Compose's admin/admin, and the realm's
# users (shopper.0-9, testuser) have DEFAULT_PASSWORD. make gets the values as exported variables (builtins), never as
# process arguments, and the Makefile passes them on by name (`docker run -e NAME`).
host_make() { # <target>
  local port_suffix=""
  [ "${host_port:-443}" = 443 ] || port_suffix=":$host_port"
  (
    export KC_BASE="https://id.polaris.local$port_suffix" API_BASE="https://polaris.local$port_suffix"
    export ASSISTANT_BASE="https://polaris.local$port_suffix"
    SHOPPER_PASSWORD="$(secret_value "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)"
    E2E_STAFF_PASSWORD="$SHOPPER_PASSWORD"
    E2E_PASSWORD="$SHOPPER_PASSWORD" # the fulfilment e2e's shopper alice.tran (K10)
    KC_ADMIN_USER="$(secret_value "$KEYCLOAK-initial-admin" username)"
    KC_ADMIN_PASSWORD="$(secret_value "$KEYCLOAK-initial-admin" password)"
    export SHOPPER_PASSWORD E2E_STAFF_PASSWORD E2E_PASSWORD KC_ADMIN_USER KC_ADMIN_PASSWORD
    make -s -C "$REPO_ROOT" "$1"
  ) 2>&1
}
