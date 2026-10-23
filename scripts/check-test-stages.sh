#!/usr/bin/env bash
# Test stage guard (Tests Improvement Q2, TR-I4). See docs/development/testing-guideline.md §4.
#   A: a *Test (unit stage) must not boot Spring or open a real boundary.
#   B: an *IntegrationTest must declare @Tag("aspect:<name>"), unless listed in the baseline.
# Usage: check-test-stages.sh [module-dir ...]   (default: apps/* libs/*)
# Env:   TEST_STAGE_ROOT (repo root), TEST_STAGE_BASELINE (baseline file); both default to this repo.
set -u

ROOT="${TEST_STAGE_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
BASELINE="${TEST_STAGE_BASELINE:-$ROOT/scripts/test-stage-baseline.txt}"
RULE_A='@SpringBootTest|@WebMvcTest|@DataJpaTest|@JdbcTest|@JsonTest|@RestClientTest|@AutoConfigureMockMvc|@ContextConfiguration|SpringExtension|ApplicationContextRunner|SpringApplication|@Testcontainers|@EmbeddedKafka|WireMockExtension|WireMockServer|@WireMockTest|ServerSocket'
ASPECTS='persistence|web|boundary|resilience|cross-cutting'
TAG_RE="@Tag\\(\"aspect:($ASPECTS)\"\\)"

baseline_entries() { [ -f "$BASELINE" ] && grep -vE '^\s*(#|$)' "$BASELINE"; }

[ $# -eq 0 ] && set -- "$ROOT"/apps/* "$ROOT"/libs/*
failures=0
fail() { echo "$1"; failures=$((failures + 1)); }

for dir in "$@"; do
  dir=$(cd "$dir" 2>/dev/null && pwd) || continue
  [ -d "$dir/src/test/java" ] || continue
  module=${dir#"$ROOT"/}

  while IFS= read -r file; do
    rel=${file#"$ROOT"/}
    case "$file" in
      *IntegrationTest.java)
        if grep -qE "$TAG_RE" "$file"; then
          baseline_entries | grep -qxF "$rel" && fail "baseline: stale entry $rel (already tagged)"
        else
          baseline_entries | grep -qxF "$rel" || fail "$rel: rule B: missing @Tag(\"aspect:<$ASPECTS>\")"
        fi ;;
      *)
        token=$(grep -oE "$RULE_A" "$file" | head -1)
        [ -n "$token" ] && fail "$rel: rule A: unit test uses $token (rename to *IntegrationTest)" ;;
    esac
  done < <(find "$dir/src/test/java" -name '*Test.java' | sort)

  while IFS= read -r entry; do
    [ -f "$ROOT/$entry" ] || fail "baseline: stale entry $entry (file not found)"
  done < <(baseline_entries | grep "^$module/")
done

if [ "$failures" -gt 0 ]; then
  echo "test stage guard: $failures violation(s)" >&2
  exit 1
fi
echo "test stage guard: ok"
