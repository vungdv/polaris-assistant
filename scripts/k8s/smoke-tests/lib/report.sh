# shellcheck shell=bash
# Result reporting: pass / fail / check, and the exit status of the run. Source it; don't run it.

failures=0
pass() { log "PASS: $1"; }
fail() {
  log "FAIL: $1"
  failures=$((failures + 1))
}

# check <description> <command...>: pass when the command succeeds.
check() {
  local what="$1"
  shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}

# smoke_finish: the summary line and the exit status of the whole run.
smoke_finish() {
  if [ "$failures" -gt 0 ]; then
    log "$failures smoke check(s) failed"
    exit 1
  fi
  log "all smoke checks passed"
}
