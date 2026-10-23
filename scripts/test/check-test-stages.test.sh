#!/usr/bin/env bash
# Self-test for scripts/check-test-stages.sh (spec Q2: AC-4, AC-5, AC-6). Run: scripts/test/check-test-stages.test.sh
set -u
GUARD="$(cd "$(dirname "$0")/.." && pwd)/check-test-stages.sh"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
failed=0

# new_root <name> -> creates $WORK/<name>/apps/m/src/test/java and an empty baseline
new_root() { mkdir -p "$WORK/$1/apps/m/src/test/java"; : > "$WORK/$1/baseline.txt"; echo "$WORK/$1"; }
run() { TEST_STAGE_ROOT="$1" TEST_STAGE_BASELINE="$1/baseline.txt" "$GUARD" "$1/apps/m" 2>&1; }
expect() { # name, expected exit, expected output fragment, actual exit, actual output
  if [ "$4" -ne "$2" ] || ! grep -qF -- "$3" <<<"$5"; then
    echo "FAIL $1: exit=$4 (want $2), output: $5"; failed=1
  else echo "ok   $1"; fi
}

r=$(new_root unit-spring); echo '@SpringBootTest class FooTest {}' > "$r/apps/m/src/test/java/FooTest.java"
out=$(run "$r"); expect "rule A: *Test with @SpringBootTest" 1 "FooTest.java: rule A" $? "$out"

r=$(new_root unit-clean); echo 'class FooTest {}' > "$r/apps/m/src/test/java/FooTest.java"
out=$(run "$r"); expect "clean unit test passes" 0 "guard: ok" $? "$out"

r=$(new_root it-untagged); echo 'class FooIntegrationTest {}' > "$r/apps/m/src/test/java/FooIntegrationTest.java"
out=$(run "$r"); expect "rule B: untagged integration test" 1 "FooIntegrationTest.java: rule B" $? "$out"

r=$(new_root it-badtag); echo '@Tag("aspect:unknown") class FooIntegrationTest {}' > "$r/apps/m/src/test/java/FooIntegrationTest.java"
out=$(run "$r"); expect "rule B: unknown aspect" 1 "rule B" $? "$out"

r=$(new_root it-tagged); echo '@Tag("aspect:persistence") class FooIntegrationTest {}' > "$r/apps/m/src/test/java/FooIntegrationTest.java"
out=$(run "$r"); expect "tagged integration test passes" 0 "guard: ok" $? "$out"

r=$(new_root it-baseline); echo 'class FooIntegrationTest {}' > "$r/apps/m/src/test/java/FooIntegrationTest.java"
echo 'apps/m/src/test/java/FooIntegrationTest.java' > "$r/baseline.txt"
out=$(run "$r"); expect "baselined untagged integration test passes" 0 "guard: ok" $? "$out"

r=$(new_root stale-tagged); echo '@Tag("aspect:web") class FooIntegrationTest {}' > "$r/apps/m/src/test/java/FooIntegrationTest.java"
echo 'apps/m/src/test/java/FooIntegrationTest.java' > "$r/baseline.txt"
out=$(run "$r"); expect "stale baseline: already tagged" 1 "stale entry" $? "$out"

r=$(new_root stale-missing); echo 'apps/m/src/test/java/GoneIntegrationTest.java' > "$r/baseline.txt"
out=$(run "$r"); expect "stale baseline: file removed" 1 "stale entry" $? "$out"

r=$(new_root no-tests); rmdir "$r/apps/m/src/test/java"
out=$(run "$r"); expect "module without tests passes" 0 "guard: ok" $? "$out"

exit $failed
