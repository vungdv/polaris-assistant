# shellcheck shell=bash
# shellcheck disable=SC2034,SC2154 # shares the variables of the sections sourced before it (see smoke.sh)
# K5: data stores (PostgreSQL, Redis). Sourced by smoke.sh.

# --- K5: data stores (PostgreSQL, Redis) ----------------------------------------------------------------------
# Every check goes through the interfaces the apps use from K7 on: the CNPG `-app` Secrets and `-rw` Services, and the
# `redis` Service, from probe pods that meet Pod Security `restricted`. The PostgreSQL and Redis pods themselves run in
# the namespace that enforces `restricted`, so their being ready shows they pass it.
check "deployment $CNPG_NAMESPACE/$CNPG_RELEASE (CloudNativePG operator) is available" \
  quiet kctl -n "$CNPG_NAMESPACE" rollout status "deployment/$CNPG_RELEASE" --timeout=60s
for cluster in "${PG_CLUSTERS[@]}"; do
  check "PostgreSQL Cluster $cluster is ready" is_true "$(cond -n "$K8S_NAMESPACE" cluster.postgresql.cnpg.io "$cluster")"
  owner="${cluster%-db}"
  check "Secret $cluster-app holds the operator-generated credentials of owner $owner" [ "$(kctl -n "$K8S_NAMESPACE" \
    get secret "$cluster-app" -o jsonpath='{.data.username}' 2>/dev/null | base64 -d 2>/dev/null)" = "$owner" ]
done
check "deployment $REDIS is available" quiet kctl -n "$K8S_NAMESPACE" rollout status "deployment/$REDIS" --timeout=60s

register_probes pg-smoke-write pg-smoke-read redis-smoke

# PostgreSQL: a probe connects to both Clusters' `-rw` Services with the owners' credentials from the `-app` Secrets
# and prints `<user> <database> <collation> <server version>`, then writes a row with a fresh token to polaris-db. The
# row is in its own schema, dropped at the end, so the `public` schema that Flyway will own (K8) stays empty.
pg_token="k5-$(hex 8)"
sql="SELECT current_user, current_database(), datcollate, current_setting('server_version')
  FROM pg_database WHERE datname = current_database()"
if out="$(run_probe pg-smoke-write "$pg_image" 26 "set -e
$(wait_for KEYCLOAK_DB_URI)
$(wait_for POLARIS_DB_URI)
psql \"\$KEYCLOAK_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' -c \"$sql\"
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' -c \"$sql\" \\
  -c 'CREATE SCHEMA IF NOT EXISTS k5_smoke' \\
  -c 'CREATE TABLE IF NOT EXISTS k5_smoke.rows (token text PRIMARY KEY)' \\
  -c \"INSERT INTO k5_smoke.rows VALUES ('$pg_token') RETURNING 'written', token\"" "$pg_env")"; then
  log "PostgreSQL probe results:"$'\n'"$out"
  for cluster in "${PG_CLUSTERS[@]}"; do
    owner="${cluster%-db}"
    check "$cluster answers on $cluster-rw as owner $owner of database $owner, collation en_US.utf8, PostgreSQL 16" \
      grep -Eq "^$owner $owner en_US.utf8 16\." <<<"$out"
  done
  check "a row was written to polaris-db" grep -qx "written $pg_token" <<<"$out"
else
  fail "PostgreSQL write probe did not succeed: $(tail -5 <<<"$out")"
fi

# Delete the primary's pod. CNPG recreates it on the same PersistentVolumeClaim (one instance locally), and the row
# must still be there once the Cluster is ready again.
primary="$(kctl -n "$K8S_NAMESPACE" get cluster.postgresql.cnpg.io polaris-db -o jsonpath='{.status.currentPrimary}' 2>/dev/null || true)"
old_uid="$(kctl -n "$K8S_NAMESPACE" get pod "$primary" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
pg_timeout="${PG_SMOKE_TIMEOUT:-180}"
if [ -n "$primary" ] && quiet kctl -n "$K8S_NAMESPACE" delete pod "$primary" --wait=true --timeout=60s; then
  log "deleted the polaris-db primary pod $primary, waiting up to ${pg_timeout}s for it to come back"
  deadline=$((SECONDS + pg_timeout))
  new_uid=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    new_uid="$(kctl -n "$K8S_NAMESPACE" get pod "$primary" -o jsonpath='{.metadata.uid}' 2>/dev/null || true)"
    if [ -n "$new_uid" ] && [ "$new_uid" != "$old_uid" ]; then break; fi
    sleep 2
  done
  remaining=$((deadline - SECONDS > 1 ? deadline - SECONDS : 1))
  if [ -n "$new_uid" ] && [ "$new_uid" != "$old_uid" ] &&
    quiet kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready "pod/$primary" --timeout="${remaining}s" &&
    quiet kctl -n "$K8S_NAMESPACE" wait --for=condition=Ready cluster.postgresql.cnpg.io/polaris-db --timeout=60s; then
    pass "the polaris-db primary pod $primary was recreated and the Cluster is ready again"
  else
    fail "the polaris-db primary pod $primary did not come back ready within ${pg_timeout}s"
  fi
else
  fail "could not delete the polaris-db primary pod '$primary'"
fi
if out="$(run_probe pg-smoke-read "$pg_image" 26 "set -e
$(wait_for POLARIS_DB_URI)
psql \"\$POLARIS_DB_URI\" -v ON_ERROR_STOP=1 -qAt -F ' ' \\
  -c \"SELECT 'read', token FROM k5_smoke.rows WHERE token = '$pg_token'\" -c 'DROP SCHEMA k5_smoke CASCADE'" "$pg_env")"; then
  check "the row written before the primary pod was deleted is still in polaris-db" grep -qx "read $pg_token" <<<"$out"
else
  fail "PostgreSQL read probe did not succeed: $(tail -5 <<<"$out")"
fi

# Redis: `redis-cli ping` answers through the Service, persistence is off and memory is bounded, as in Compose.
if out="$(run_probe redis-smoke "$redis_image" 999 "set -e
redis-cli -h $REDIS -p 6379 ping
echo \"save=\$(redis-cli -h $REDIS -p 6379 config get save | tail -n 1)\"
echo \"appendonly=\$(redis-cli -h $REDIS -p 6379 config get appendonly | tail -n 1)\"
echo \"maxmemory=\$(redis-cli -h $REDIS -p 6379 config get maxmemory | tail -n 1)\"
echo \"maxmemory-policy=\$(redis-cli -h $REDIS -p 6379 config get maxmemory-policy | tail -n 1)\"")"; then
  log "Redis probe results:"$'\n'"$out"
  check "redis-cli ping through Service $REDIS answers PONG" grep -qx PONG <<<"$out"
  persistence_off() { grep -qx "save=" <<<"$1" && grep -qx "appendonly=no" <<<"$1"; }
  check "Redis runs with persistence off (save \"\", appendonly no), as in Compose" persistence_off "$out"
  # 200mb = 209715200 bytes, below the 256Mi container limit; evicts only keys with a TTL (the product cache).
  memory_bounded() { grep -qx "maxmemory=209715200" <<<"$1" && grep -qx "maxmemory-policy=volatile-lru" <<<"$1"; }
  check "Redis memory is bounded (maxmemory 200mb, volatile-lru), as in Compose" memory_bounded "$out"
else
  fail "Redis probe did not succeed: $(tail -5 <<<"$out")"
fi
