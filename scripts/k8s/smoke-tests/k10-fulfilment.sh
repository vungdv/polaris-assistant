# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K10: Fulfilment (polaris-fulfilment-emulator). Sourced by smoke.sh.

# --- K10: Fulfilment (polaris-fulfilment-emulator) ------------------------------------------------------------
# The emulator pod runs in the namespace that enforces `restricted`, so its being ready shows it passes it. It has no
# HTTPRoute (actuator is in-cluster only), reads Kafka through the Strimzi bootstrap Service, and its client secret is
# the one the realm import put into Keycloak (Secret keycloak-realm-placeholders), never Compose's dev default.
check "deployment $EMULATOR has 1 of 1 replica ready" [ "$(kctl -n "$K8S_NAMESPACE" get deployment "$EMULATOR" \
  -o jsonpath='{.status.readyReplicas}/{.spec.replicas}' 2>/dev/null)" = 1/1 ]
route_backends="$(kctl -n "$K8S_NAMESPACE" get httproutes \
  -o jsonpath='{range .items[*].spec.rules[*].backendRefs[*]}{.name}{"\n"}{end}' 2>/dev/null || true)"
no_emulator_route() { [ -n "$route_backends" ] && ! grep -qx "$EMULATOR" <<<"$route_backends"; }
check "no HTTPRoute routes to $EMULATOR: its actuator is in-cluster only" no_emulator_route
emulator_secret_ref="$(kctl -n "$K8S_NAMESPACE" get deployment "$EMULATOR" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="POLARIS_FULFILMENT_EMULATOR_SECRET")].valueFrom.secretKeyRef}' 2>/dev/null || true)"
check "the emulator reads POLARIS_FULFILMENT_EMULATOR_SECRET from Secret $KEYCLOAK_REALM_SECRET" \
  grep -q "\"name\":\"$KEYCLOAK_REALM_SECRET\"" <<<"$emulator_secret_ref"
# The value is compared in this shell only; it is never printed.
not_dev_secret() {
  local value
  value="$(secret_value "$KEYCLOAK_REALM_SECRET" POLARIS_FULFILMENT_EMULATOR_SECRET)"
  [ -n "$value" ] && [ "$value" != fulfilment-emulator-dev-secret ]
}
check "the emulator's client secret is set and is not Compose's dev default" not_dev_secret
check "the emulator bootstraps Kafka from the Strimzi bootstrap Service $KAFKA_BOOTSTRAP" [ "$(kctl -n "$K8S_NAMESPACE" \
  get configmap "$EMULATOR" -o jsonpath='{.data.SPRING_KAFKA_BOOTSTRAP_SERVERS}' 2>/dev/null)" = "$KAFKA_BOOTSTRAP" ]
check "the emulator's token endpoint is the public issuer's (id.polaris.local)" [ "$(kctl -n "$K8S_NAMESPACE" \
  get configmap "$EMULATOR" -o jsonpath='{.data.POLARIS_FULFILMENT_TOKEN_URI}' 2>/dev/null)" = \
  "https://id.polaris.local/realms/polaris/protocol/openid-connect/token" ]

# Actuator answers in-cluster, on the emulator's Service, from a `restricted` probe.
register_probes k10-emulator-smoke k10-trace
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
emulator_script='for g in liveness readiness; do
  echo "$g $(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" "http://polaris-fulfilment-emulator:8082/actuator/health/$g")"
done'
if out="$(run_probe k10-emulator-smoke "$SMOKE_CURL_IMAGE" 100 "$emulator_script")"; then
  log "emulator probe results:"$'\n'"$out"
  check "the emulator's liveness and readiness answer 200 in-cluster on Service $EMULATOR:8082" \
    [ "$(tr '\n' ' ' <<<"$out")" = "liveness 200 readiness 200 " ]
else
  fail "emulator probe did not succeed: $(tail -5 <<<"$out")"
fi

# Each partner is one active consumer group on the order lifecycle topic, joined through the bootstrap Service.
for partner in "${EMULATOR_PARTNERS[@]}"; do
  group="$EMULATOR_GROUP_PREFIX$partner"
  check "consumer group $group is Stable (its partner is consuming)" grep -Eq "^$group .* Stable( |$)" <<<"$(kafka_cli 1 \
    kafka-consumer-groups --describe --state --group "$group" </dev/null 2>/dev/null || true)"
done

# tests/e2e/run-fulfilment.sh against the cluster (`make k8s-e2e-fulfilment`), from the host through the Gateway as on
# Compose: setup, one order plus a batch of 10 placed as alice.tran (DEFAULT_PASSWORD), each fulfilled by the emulator's
# partners to DELIVERED with an assignedPartner, more than one winning partner, then the five order.*.v1 events per
# order on polaris.order.lifecycle, in order (read inside a Kafka node).
if out="$(host_make k8s-e2e-fulfilment)"; then
  pass "tests/e2e/run-fulfilment.sh passes against the cluster through the Gateway (orders DELIVERED, Kafka events in order)"
else
  fail "tests/e2e/run-fulfilment.sh failed against the cluster"
fi
log "run-fulfilment.sh results:"$'\n'"$(grep -E "✓|✗|checks|orders_|batch|Kafka|ok |FAIL|PASS|ERRO|level=error" <<<"$out" || tail -30 <<<"$out")"

# One trace continues across Kafka (TR-K9): a probe places an order through the Gateway with a fresh traceparent. polaris
# records the order's events with that trace context (outbox) and hands them off to Kafka; the emulator's consumer span
# for the order is the child of a polaris span of the trace, and its claim REST call is the parent of a polaris server
# span: Gateway -> polaris -> Kafka -> emulator -> polaris.
trace_id="$(hex 16)"
traceparent="00-$trace_id-$(hex 8)-01"
# shellcheck disable=SC2016 # expanded by the probe's shell, not here
trace_script='c() { curl -sS --max-time 15 --cacert /etc/polaris-trust/ca.crt "$@"; }
t=$(c -d grant_type=password -d client_id=polaris-app -d username=alice.tran --data-urlencode "password=$DEFAULT_PASSWORD" \
  https://id.polaris.local/realms/polaris/protocol/openid-connect/token | sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
r=$(c -w " %{http_code}" -H "Authorization: Bearer $t" -H "traceparent: $TRACEPARENT" \
  -H "Idempotency-Key: k10-trace-$TRACE_ID" -H "Content-Type: application/json" \
  -d "{\"items\":[{\"sku\":\"E2E-UNLIMITED-01\",\"quantity\":1}]}" https://polaris.local/api/v1/orders)
echo "order $(printf %s "$r" | sed -n "s/.*\"orderNumber\":\"\([^\"]*\)\".*/\1/p") ${r##* }"'
trace_env="$(secret_env DEFAULT_PASSWORD "$KEYCLOAK_REALM_SECRET" DEFAULT_PASSWORD)
        - name: TRACEPARENT
          value: \"$traceparent\"
        - name: TRACE_ID
          value: \"$trace_id\""
if out="$(run_probe k10-trace "$SMOKE_CURL_IMAGE" 100 "$trace_script" "$trace_env")"; then
  log "trace probe results:"$'\n'"$out"
  check "an order placed through the Gateway with a fresh traceparent answers 201" grep -Eq "^order [^ ]+ 201$" <<<"$out"
else
  fail "trace probe did not succeed: $(tail -5 <<<"$out")"
fi
deadline=$((SECONDS + ${OTEL_SMOKE_TIMEOUT:-90} + 30))
pl_rows="" em_block="" em_rows="" em_child="" pl_child=""
while [ "$SECONDS" -lt "$deadline" ]; do
  spans="$(telemetry_block ResourceSpans "$trace_id")"
  pl_rows="$(span_rows "$(resource_blocks "k8s.deployment.name: Str\\\\($POLARIS\\\\)")")"
  em_block="$(resource_blocks "k8s.deployment.name: Str\\\\($EMULATOR\\\\)")"
  em_rows="$(span_rows "$em_block")"
  pl_ids=" $(awk '{ printf "%s ", $3 }' <<<"$pl_rows")"
  em_ids=" $(awk '{ printf "%s ", $3 }' <<<"$em_rows")"
  em_child="$(awk -v ids="$pl_ids" 'index(ids, " " $2 " ")' <<<"$em_rows")"
  pl_child="$(awk -v ids="$em_ids" 'index(ids, " " $2 " ")' <<<"$pl_rows")"
  if [ -n "$em_child" ] && [ -n "$pl_child" ]; then break; fi
  sleep 3
done
log "spans of trace $trace_id (trace, parent, id, name):"$'\n'"polaris:"$'\n'"$pl_rows"$'\n'"emulator:"$'\n'"$em_rows"
check "an emulator span is a child of a polaris span: the trace continues across Kafka into $EMULATOR" [ -n "$em_child" ]
check "a polaris span is a child of an emulator span: the claim continues the trace back into $POLARIS" [ -n "$pl_child" ]
emulator_attributes() {
  grep -q "k8s.namespace.name: Str($K8S_NAMESPACE)" <<<"$1" && grep -q "k8s.pod.name: Str($EMULATOR-" <<<"$1"
}
check "the emulator spans carry k8s.namespace.name, k8s.pod.name and k8s.deployment.name of $EMULATOR" \
  emulator_attributes "$em_block"
