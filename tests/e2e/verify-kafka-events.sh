#!/usr/bin/env bash
# Verifies the plan acceptance "all 5 order.*.v1 events on Kafka, in order" for the given order numbers by reading
# polaris.order.lifecycle through the cluster's own console consumer (what any consumer would see).
# Usage: verify-kafka-events.sh ORD-1[=partner] ORD-2[=partner] ...   (run from the compose project directory)
# With =partner, the confirmed event's assignedPartner must equal it (the partner GET /orders/{n} reported).
# No -e on purpose: mismatches are counted and reported per order before the single final exit code.
set -uo pipefail
TOPIC=${TOPIC:-polaris.order.lifecycle}
KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-kafka-1:9092,kafka-2:9092,kafka-3:9092}
EXPECTED="placed confirmed parceled delivering delivered"
[ "$#" -gt 0 ] || { echo "FAIL no order numbers given"; exit 1; }

# Output lines: <headers> TAB <key> TAB <value>; prints "<orderNumber> <event>" in topic order
# (the confirmed event is printed as "confirmed@<assignedPartner>").
lifecycle_events() {
  docker compose exec -T kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "$TOPIC" --from-beginning --timeout-ms 6000 --property print.headers=true --property print.key=true 2>/dev/null |
    awk -F'\t' '{ if (match($1, /ce_type:vn\.danang\.polaris\.order\.[a-z]+\.v1/)) { t = substr($1, RSTART, RLENGTH);
      sub(/.*order\./, "", t); sub(/\.v1/, "", t);
      if (t == "confirmed" && match($3, /"assignedPartner":"[^"]*"/)) t = t "@" substr($3, RSTART + 19, RLENGTH - 20); print $2, t } }'
}

# ORD or ORD=partner -> the expected sequence for that order (confirmed carries the partner when given).
expected_for() {
  if [ "$1" = "${1%%=*}" ]; then echo "$EXPECTED"; else echo "${EXPECTED/confirmed/confirmed@${1#*=}}"; fi
}
events_of() { awk -v n="${1%%=*}" '$1 == n { printf "%s ", $2 }' <<<"$captured"; }

fails=0
for attempt in 1 2 3 4 5; do   # events are published asynchronously; allow the last ones to land
  captured=$(lifecycle_events)
  fails=0
  for n in "$@"; do
    got=$(events_of "$n"); want=$(expected_for "$n")
    [ "${got% }" = "$want" ] || fails=$((fails + 1))
  done
  [ "$fails" -eq 0 ] && break
  sleep 3
done
for n in "$@"; do
  got=$(events_of "$n"); want=$(expected_for "$n")
  if [ "${got% }" = "$want" ]; then echo "   ok   ${n%%=*}: ${got% }"; else echo "   FAIL ${n%%=*}: got '${got% }', want '$want'"; fi
done
[ "$fails" -eq 0 ] && echo "Kafka: PASS ($# orders x 5 events)" || { echo "Kafka: FAIL ($fails order(s))"; exit 1; }
