# shellcheck shell=bash
# shellcheck disable=SC2154 # uses variables the sections set
# The Kafka CLI inside a Kafka node. Source it; don't run it.

# kafka_cli <node id> <tool> [args...]: runs /opt/kafka/bin/<tool>.sh against the bootstrap Service in that node's
# container, with a small heap so it fits next to the broker. stdin is passed to the tool.
kafka_cli() {
  local node="$1" tool="$2"
  shift 2
  kctl -n "$K8S_NAMESPACE" exec -i "$(kafka_pod "$node")" -c kafka -- env KAFKA_HEAP_OPTS="-Xms32m -Xmx128m" \
    "/opt/kafka/bin/$tool.sh" --bootstrap-server "$KAFKA_BOOTSTRAP" "$@"
}
# end_offset_sum <node> <topic>: the sum of the topic's end offsets, i.e. the number of records written to it.
end_offset_sum() {
  kafka_cli "$1" kafka-get-offsets --topic "$2" </dev/null 2>/dev/null |
    awk -F: 'NF == 3 { sum += $3; n++ } END { if (n) print sum }'
}
# under_replicated <node> <topic>: the number of the topic's partitions whose ISR is short of their replicas.
under_replicated() {
  kafka_cli "$1" kafka-topics --describe --under-replicated-partitions --topic "$2" </dev/null 2>/dev/null |
    grep -c "Partition:" || true
}
# produce <node> <topic> <first> <last>: writes the records <token>-<first>..<token>-<last> with acks=all.
produce() {
  local i
  for ((i = $3; i <= $4; i++)); do printf '%s-%d\n' "$kafka_token" "$i"; done |
    kafka_cli "$1" kafka-console-producer --topic "$2" --producer-property acks=all \
      --producer-property enable.idempotence=true >/dev/null
}
