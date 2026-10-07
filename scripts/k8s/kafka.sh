#!/usr/bin/env bash
# Kafka admin commands against the kind cluster: the Kubernetes equivalents of the Compose `make kafka-*` targets.
# Each runs the Kafka CLI inside a Kafka node with `kubectl exec`, against the bootstrap Service, so any running node
# works (NODE=2 if node 1 is down).
# Usage: scripts/k8s/kafka.sh topics|tail|cluster|offsets|groups|leaders
#   TOPIC=<topic>  topic for tail (required) and offsets (default polaris.order.lifecycle)
#   NODE=<id>      the node to run in (1-3, default 1)
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

KAFKA_BIN=/opt/kafka/bin
NODE="${NODE:-1}"
TOPIC="${TOPIC:-}"

ensure_tools kind kubectl
cluster_exists || die "kind cluster '$KIND_CLUSTER_NAME' doesn't exist; run 'make k8s-up'"
ensure_kubeconfig

# kafka <tool> <args...>: runs /opt/kafka/bin/<tool>.sh in the node's kafka container. The tool's JVM gets a small heap,
# so it fits next to the broker under the container's memory limit; stdin is passed through only when it's a terminal.
kafka() {
  local tool="$1" exec_args=(-c kafka)
  shift
  if [ -t 0 ]; then exec_args+=(-it); fi
  kctl -n "$K8S_NAMESPACE" exec "$(kafka_pod "$NODE")" "${exec_args[@]}" -- \
    env KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" "$KAFKA_BIN/$tool.sh" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"
}

case "${1:-}" in
  topics) kafka kafka-topics --describe --exclude-internal ;;
  tail)
    [ -n "$TOPIC" ] || die "usage: make k8s-kafka-tail TOPIC=<topic>"
    kafka kafka-console-consumer --topic "$TOPIC" --from-beginning \
      --property print.timestamp=true --property print.partition=true --property print.offset=true \
      --property print.headers=true --property print.key=true
    ;;
  cluster)
    kafka kafka-metadata-quorum describe --status
    kafka kafka-metadata-quorum describe --replication
    kafka kafka-topics --describe --under-replicated-partitions
    ;;
  offsets) kafka kafka-get-offsets --topic "${TOPIC:-polaris.order.lifecycle}" ;;
  groups) kafka kafka-consumer-groups --describe --all-groups ;;
  leaders) kafka kafka-leader-election --election-type preferred --all-topic-partitions ;;
  *) die "usage: $0 topics|tail|cluster|offsets|groups|leaders (TOPIC=<topic>, NODE=<id>)" ;;
esac
