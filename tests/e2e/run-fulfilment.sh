#!/usr/bin/env bash
# One-command fulfilment e2e: k6 scenario (setup + place + poll to DELIVERED) followed by the Kafka event check
# for exactly the orders that run placed. Usage: make e2e-fulfilment   (stack must be up: make up)
# No -e on purpose: the Kafka check must still run after a k6 failure, and both exit codes are aggregated below.
set -uo pipefail
cd "$(dirname "$0")/../.."   # compose project directory

docker compose ps --status running --format '{{.Name}}' 2>/dev/null | grep -qx polaris-fulfilment-emulator ||
  { echo "FAIL polaris-fulfilment-emulator is not running (make up)"; exit 1; }

LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
# Not --network host: on Docker Desktop that is the VM, not this machine. id.polaris.local must resolve to the
# nginx that fronts Keycloak so the token issuer is the canonical public URL.
docker run --rm -i --add-host id.polaris.local:host-gateway --add-host host.docker.internal:host-gateway \
  -v "$PWD/tests/e2e/k6:/scripts:ro" -w /scripts \
  -e API_BASE -e KC_BASE -e E2E_STAFF_USER -e E2E_STAFF_PASSWORD -e E2E_USER -e E2E_PASSWORD -e SKU -e BATCH -e TIMEOUT -e POLL_INTERVAL \
  grafana/k6 run fulfilment.js "$@" 2>&1 | tee "$LOG"
k6_rc=${PIPESTATUS[0]}

orders=$(sed -n 's/.*E2E_ORDERS \([^"]*\).*/\1/p' "$LOG" | tail -1)
kafka_rc=0
if [ -n "$orders" ]; then
  echo; echo "== Kafka: 5 order.*.v1 events per order, in order"
  # shellcheck disable=SC2086
  ./tests/e2e/verify-kafka-events.sh $orders || kafka_rc=1
else
  echo "FAIL no orders were placed, skipping the Kafka check"; kafka_rc=1
fi

echo
if [ "$k6_rc" -eq 0 ] && [ "$kafka_rc" -eq 0 ]; then echo "PASS"; else echo "FAIL (k6 exit $k6_rc, kafka check $kafka_rc)"; exit 1; fi
