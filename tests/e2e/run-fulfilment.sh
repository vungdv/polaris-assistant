#!/usr/bin/env bash
# One-command fulfilment e2e: k6 scenario (setup + place + poll to DELIVERED) followed by the Kafka event check
# for exactly the orders that run placed.
# Usage: make e2e-fulfilment       (Compose stack must be up: make up)
#        make k8s-e2e-fulfilment   (E2E_TARGET=k8s: the kind cluster of make k8s-up, through its Gateway)
# No -e on purpose: the Kafka check must still run after a k6 failure, and both exit codes are aggregated below.
set -uo pipefail
cd "$(dirname "$0")/../.."   # compose project directory (the repo root)

E2E_TARGET=${E2E_TARGET:-compose}
case "$E2E_TARGET" in
  compose)
    docker compose ps --status running --format '{{.Name}}' 2>/dev/null | grep -qx polaris-fulfilment-emulator ||
      { echo "FAIL polaris-fulfilment-emulator is not running (make up)"; exit 1; }
    ;;
  k8s)
    # The cluster's settings and kubectl context (scripts/k8s/lib.sh); the run goes through the Gateway, as clients do.
    # shellcheck source=scripts/k8s/lib.sh
    source scripts/k8s/lib.sh
    ensure_tools kind kubectl
    cluster_exists || { echo "FAIL kind cluster '$KIND_CLUSTER_NAME' doesn't exist (make k8s-up)"; exit 1; }
    ensure_kubeconfig 2>/dev/null
    kctl -n "$K8S_NAMESPACE" rollout status "deployment/$EMULATOR" --timeout=10s >/dev/null 2>&1 ||
      { echo "FAIL deployment/$EMULATOR is not ready (make k8s-images APPS=$EMULATOR && make k8s-up)"; exit 1; }
    export API_BASE=${API_BASE:-https://polaris.local}
    ;;
  *) echo "FAIL unknown E2E_TARGET '$E2E_TARGET' (compose or k8s)"; exit 1 ;;
esac
export E2E_TARGET

LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
# Not --network host: on Docker Desktop that is the VM, not this machine. id.polaris.local must resolve to the
# edge that fronts Keycloak (Compose's nginx, or the kind Gateway's host port) so the token issuer is the canonical
# public URL; polaris.local reaches the kind Gateway the same way.
docker run --rm -i --add-host id.polaris.local:host-gateway --add-host polaris.local:host-gateway \
  --add-host host.docker.internal:host-gateway \
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
