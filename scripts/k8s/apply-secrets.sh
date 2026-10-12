#!/usr/bin/env bash
# Creates the Secrets the apps read from the untracked env file (K8S_ENV_FILE): the Keycloak realm placeholders (K7) and
# the assistant's credentials (K9). Safe to re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

# Realm placeholders (K7): DEFAULT_PASSWORD and POLARIS_FULFILMENT_EMULATOR_SECRET, the keys Compose substitutes into
# docker/keycloak/*.json. Each comes from the untracked env file when it sets it. Otherwise (CI, or a key left empty)
# the value already in the Secret is kept, and only a missing one is generated at random, so re-runs never change it:
# the realms are imported once, into an empty database, like Compose's --import-realm, and keep their first values.
# printf is a shell builtin, so the values never appear in a process's arguments.
apply_realm_secret() {
  local key value values="" generated=()
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  for key in DEFAULT_PASSWORD POLARIS_FULFILMENT_EMULATOR_SECRET; do
    value="$(env_value "$key")"
    [ -n "$value" ] || value="$(kctl -n "$K8S_NAMESPACE" get secret "$KEYCLOAK_REALM_SECRET" \
      -o jsonpath="{.data.$key}" 2>/dev/null | base64 -d 2>/dev/null || true)"
    if [ -z "$value" ]; then
      value="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
      generated+=("$key")
    fi
    values+="$key=$value"$'\n'
  done
  [ "${#generated[@]}" -eq 0 ] ||
    log "generated ${generated[*]} at random (not set in ${K8S_ENV_FILE#"$REPO_ROOT"/}); read it from Secret $KEYCLOAK_REALM_SECRET"
  kctl -n "$K8S_NAMESPACE" create secret generic "$KEYCLOAK_REALM_SECRET" --from-env-file=<(printf '%s' "$values") \
    --dry-run=client -o yaml | kctl apply -f - >/dev/null
}

# Assistant credentials and settings (K9): GEMINI_API_KEY, TYPESAFE_API_KEY and AGENTO11Y_*, the keys Compose passes to
# polaris-assistant from .env. Unlike the realm placeholders these aren't imported once, so the Secret mirrors the env
# file on every run: a key it leaves empty is left out, and application.yml's default applies (no API key: the
# assistant still starts and stays ready, but chat turns answer 503). Pods read the Secret at start, so a changed Secret
# restarts the Deployment (rolling, as any restart). CI writes the env file from repository secrets.
apply_assistant_secret() {
  local key value values="" result
  for key in "${ASSISTANT_SECRET_KEYS[@]}"; do
    value="$(env_value "$key")"
    [ -z "$value" ] || values+="$key=$value"$'\n'
  done
  [ -n "$values" ] || log "no assistant keys in ${K8S_ENV_FILE#"$REPO_ROOT"/}: chat turns need GEMINI_API_KEY"
  result="$(kctl -n "$K8S_NAMESPACE" create secret generic "$ASSISTANT_SECRET" --from-env-file=<(printf '%s' "$values") \
    --dry-run=client -o yaml | kctl apply -f -)"
  if [[ "$result" == *" configured" ]] && kctl -n "$K8S_NAMESPACE" get "deployment/$ASSISTANT" >/dev/null 2>&1; then
    log "Secret $ASSISTANT_SECRET changed: restarting deployment/$ASSISTANT"
    kctl -n "$K8S_NAMESPACE" rollout restart "deployment/$ASSISTANT" >/dev/null
  fi
}

apply_realm_secret
apply_assistant_secret
