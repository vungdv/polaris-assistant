# shellcheck shell=bash
# One EXIT trap for the whole run. Sections register what they leave behind instead of each re-setting the trap:
#   register_probes <pod>...        probe pods to delete on exit
#   on_cleanup <function>...        functions to call on exit (each must be safe to run more than once)
# Source it; don't run it.

SMOKE_PROBES=()
SMOKE_CLEANUPS=()
register_probes() { SMOKE_PROBES+=("$@"); }
on_cleanup() { SMOKE_CLEANUPS+=("$@"); }

# shellcheck disable=SC2329 # invoked by the EXIT trap
smoke_cleanup() {
  local fn
  if [ "${#SMOKE_PROBES[@]}" -gt 0 ]; then
    kctl -n "$K8S_NAMESPACE" delete pod "${SMOKE_PROBES[@]}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  fi
  for fn in ${SMOKE_CLEANUPS[@]+"${SMOKE_CLEANUPS[@]}"}; do "$fn" || true; done
}
trap smoke_cleanup EXIT
