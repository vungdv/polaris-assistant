# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K6: Kafka. Sourced by smoke.sh.

# --- K6: Kafka ------------------------------------------------------------------------------------------------
# The Kafka CLI runs inside a Kafka node (`kubectl exec`, as `make k8s-kafka-*` does) and talks to the cluster through
# the bootstrap Service the apps use from K8 on. The Kafka pods run in the namespace that enforces `restricted`, so
# their being ready shows they pass it.
check "deployment $STRIMZI_NAMESPACE/strimzi-cluster-operator (Strimzi operator) is available" \
  quiet kctl -n "$STRIMZI_NAMESPACE" rollout status "deployment/strimzi-cluster-operator" --timeout=60s
check "Kafka $KAFKA_CLUSTER is ready" is_true "$(cond -n "$K8S_NAMESPACE" kafka.kafka.strimzi.io "$KAFKA_CLUSTER")"
check "KafkaNodePool $KAFKA_NODE_POOL runs nodes 1, 2 and 3 as controller and broker" [ "$(kctl -n "$K8S_NAMESPACE" \
  get kafkanodepool.kafka.strimzi.io "$KAFKA_NODE_POOL" -o jsonpath='{.status.nodeIds} {.status.roles}' 2>/dev/null)" \
  = '[1,2,3] ["controller","broker"]' ]
check "the Topic and User Operators are not deployed (D10)" [ -z "$(kctl -n "$K8S_NAMESPACE" get deployments \
  -l "strimzi.io/cluster=$KAFKA_CLUSTER" -o name 2>/dev/null)" ]


# The cluster runs Compose's broker configuration (TR-K6), as the brokers report it.
if broker_config="$(kafka_cli 1 kafka-configs --describe --all --entity-type brokers --entity-name 1 </dev/null 2>&1)"; then
  for setting in auto.create.topics.enable=false default.replication.factor=3 min.insync.replicas=2 \
    offsets.topic.replication.factor=3 transaction.state.log.replication.factor=3 transaction.state.log.min.isr=2; do
    check "broker 1 runs with $setting" grep -Eq "^ *$setting( |$)" <<<"$broker_config"
  done
else
  fail "could not read the broker configuration: $(tail -3 <<<"$broker_config")"
fi
check "the KRaft controller quorum has 3 voters" [ "$(kafka_cli 1 kafka-metadata-quorum describe --status </dev/null \
  2>/dev/null | sed -n 's/^CurrentVoters: *//p' | grep -o '"id":' | wc -l | tr -d ' ')" = 3 ]

# Losing one node loses no acks=all write (TR-K6): a topic with RF 3 and min ISR 2 gets a first batch of records, the
# node that leads its partition 0 is deleted, a second acks=all batch still succeeds while that node is down, and once
# it is back in sync every record of both batches is there exactly once. The topic is deleted at the end (and on exit).
kafka_token="$(hex 4)"
kafka_topic="k6-smoke-$kafka_token"
kafka_records=100
kafka_timeout="${KAFKA_SMOKE_TIMEOUT:-240}"
# shellcheck disable=SC2329 # invoked by the EXIT trap
cleanup_k06() {
  if [ -n "${kafka_topic_created:-}" ]; then
    for node in 1 2 3; do
      kafka_cli "$node" kafka-topics --delete --if-exists --topic "$kafka_topic" </dev/null >/dev/null 2>&1 && break
    done
  fi
}
on_cleanup cleanup_k06
if kafka_cli 1 kafka-topics --create --topic "$kafka_topic" --partitions 3 --replication-factor 3 \
  --config min.insync.replicas=2 </dev/null >/dev/null; then
  kafka_topic_created=1
  description="$(kafka_cli 1 kafka-topics --describe --topic "$kafka_topic" </dev/null 2>/dev/null)"
  log "test topic:"$'\n'"$description"
  check "test topic $kafka_topic has 3 partitions, replication factor 3 and min.insync.replicas=2" \
    grep -Eq "PartitionCount: 3.*ReplicationFactor: 3.*min.insync.replicas=2" <<<"$description"
  victim="$(sed -nE 's/.*Partition: 0[[:space:]]+Leader: ([0-9]+).*/\1/p' <<<"$description")"
  client=1
  [ "$victim" != 1 ] || client=2

  produce "$client" "$kafka_topic" 1 $((kafka_records / 2)) || true
  check "the first $((kafka_records / 2)) records were written with acks=all" \
    [ "$(end_offset_sum "$client" "$kafka_topic")" = $((kafka_records / 2)) ]

  victim_pod="$(kafka_pod "$victim")"
  if [ -n "$victim" ] && quiet kctl -n "$K8S_NAMESPACE" delete pod "$victim_pod" --wait=true --timeout=120s; then
    log "deleted $victim_pod (leader of partition 0), producing from node $client while it is down"
    down_before="$(under_replicated "$client" "$kafka_topic")"
    produce "$client" "$kafka_topic" $((kafka_records / 2 + 1)) "$kafka_records" || true
    victim_ready="$(kctl -n "$K8S_NAMESPACE" get pod "$victim_pod" \
      -o jsonpath="{.status.conditions[?(@.type=='Ready')].status}" 2>/dev/null || true)"
    check "node $victim was out of every partition's ISR before the second batch (under-replicated: '$down_before' of 3)" \
      [ "$down_before" = 3 ]
    check "node $victim was still not ready after the second batch (Ready='$victim_ready')" [ "$victim_ready" != True ]
    check "the second $((kafka_records / 2)) records were written with acks=all while node $victim was down" \
      [ "$(end_offset_sum "$client" "$kafka_topic")" = "$kafka_records" ]

    # The pod comes back on its PersistentVolumeClaim and catches up: ready, and back in every partition's ISR.
    deadline=$((SECONDS + kafka_timeout))
    back=""
    while [ "$SECONDS" -lt "$deadline" ]; do
      if is_true "$(kctl -n "$K8S_NAMESPACE" get pod "$victim_pod" \
        -o jsonpath="{.status.conditions[?(@.type=='Ready')].status}" 2>/dev/null)" &&
        [ "$(under_replicated "$client" "$kafka_topic")" = 0 ]; then
        back=1
        break
      fi
      sleep 5
    done
    check "node $victim came back ready and in sync within ${kafka_timeout}s" [ -n "$back" ]
  else
    fail "could not delete the leader of partition 0 of $kafka_topic ('$victim_pod')"
  fi

  # Every record of both batches, read back from the returned node: the expected values exactly, none lost, none twice.
  read_node="${victim:-1}"
  records="$(kafka_cli "$read_node" kafka-console-consumer --topic "$kafka_topic" --from-beginning \
    --group "$kafka_topic" --max-messages "$kafka_records" --timeout-ms 30000 </dev/null 2>/dev/null | sort || true)"
  expected="$(for ((i = 1; i <= kafka_records; i++)); do printf '%s-%d\n' "$kafka_token" "$i"; done | sort)"
  check "all $kafka_records acks=all records are in $kafka_topic after node $victim returned (no data loss)" \
    [ "$records" = "$expected" ]
  check "$kafka_topic holds exactly $kafka_records records (end offsets)" \
    [ "$(end_offset_sum "$read_node" "$kafka_topic")" = "$kafka_records" ]

  # The reader's consumer group (named after the topic) goes with it.
  kafka_cli 1 kafka-consumer-groups --delete --group "$kafka_topic" </dev/null >/dev/null 2>&1 || true
  if kafka_cli 1 kafka-topics --delete --topic "$kafka_topic" </dev/null >/dev/null 2>&1; then
    kafka_topic_created=""
    pass "test topic $kafka_topic deleted"
  else
    fail "could not delete test topic $kafka_topic"
  fi
else
  fail "could not create test topic $kafka_topic"
fi
