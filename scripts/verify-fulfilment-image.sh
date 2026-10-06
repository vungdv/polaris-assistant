#!/usr/bin/env bash
# Builds the fulfilment emulator image from its Dockerfile and checks it starts on the plain-JRE runtime image.
# "Starts" = Spring context refresh completed (the "Started ... in N seconds" line) and the process is still running.
# Kafka and Keycloak are deliberately unreachable (localhost:9092 refuses connections; an unresolvable host fails consumer construction instead): consumers retry in the background and /actuator/health may be DOWN.
set -euo pipefail
cd "$(dirname "$0")/.."
image=polaris-fulfilment-emulator:verify
name=polaris-fulfilment-verify-$$
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT

docker build -q -f apps/polaris-fulfilment-emulator/Dockerfile -t "$image" .
docker run -d --name "$name" -e POLARIS_FULFILMENT_EMULATOR_SECRET=verify \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 "$image" >/dev/null

for _ in $(seq 1 60); do
  if [[ "$(docker logs "$name" 2>&1)" == *" Started "*" seconds"* ]]; then
    [ "$(docker inspect -f '{{.State.Running}}' "$name")" = true ] && { echo "OK: emulator context started in image"; exit 0; }
  fi
  [ "$(docker inspect -f '{{.State.Running}}' "$name")" = true ] || break
  sleep 2
done
echo "FAIL: emulator did not start" >&2
docker logs "$name" 2>&1 | tail -40 >&2
exit 1
