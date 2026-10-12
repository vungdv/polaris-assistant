#!/usr/bin/env bash
# Installs the in-cluster S3 object store for the PostgreSQL backups, with its credentials and bucket (K11). Safe to
# re-run.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools_cluster

weed_shell() {
  kctl -n "$OBJECT_STORE_NAMESPACE" exec "deployment/$OBJECT_STORE_DEPLOYMENT" -c seaweedfs -- \
    sh -c "echo '$1' | HOME=/tmp weed shell" 2>&1
}

# The object store for the PostgreSQL backups (K11, platform/object-store), in its own namespace. Its S3 credentials are
# generated at random on the first run and kept on later runs (read back from the Secret in the app namespace), so
# re-runs never invalidate the backups' access. They go to two Secrets, never to a process's arguments or the repo:
#   object-store/object-store-s3-config   the identity SeaweedFS's S3 gateway accepts, limited to the bucket
#   polaris/object-store-credentials      the keys the ObjectStore (base/data/backup.yaml) signs its requests with
# Then the bucket is created inside the server's pod (`weed shell`), unless it exists.
install_object_store() {
  local key secret config buckets
  kctl apply -f "$K8S_DIR/base/namespace.yaml" >/dev/null
  kctl apply -k "$PLATFORM_DIR/object-store" >/dev/null
  key="$(secret_key "$K8S_NAMESPACE" "$OBJECT_STORE_CREDENTIALS" ACCESS_KEY_ID)"
  secret="$(secret_key "$K8S_NAMESPACE" "$OBJECT_STORE_CREDENTIALS" ACCESS_SECRET_KEY)"
  if [ -z "$key" ] || [ -z "$secret" ]; then
    log "generating the object store's S3 credentials (Secret $K8S_NAMESPACE/$OBJECT_STORE_CREDENTIALS)"
    key="cnpg-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    secret="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
  fi
  kctl -n "$K8S_NAMESPACE" create secret generic "$OBJECT_STORE_CREDENTIALS" \
    --from-env-file=<(printf 'ACCESS_KEY_ID=%s\nACCESS_SECRET_KEY=%s\n' "$key" "$secret") \
    --dry-run=client -o yaml | kctl apply -f - >/dev/null
  config="$(printf '{"identities":[{"name":"%s","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Read:%s","Write:%s","List:%s","Tagging:%s"]}]}' \
    "$BACKUP_BUCKET" "$key" "$secret" "$BACKUP_BUCKET" "$BACKUP_BUCKET" "$BACKUP_BUCKET" "$BACKUP_BUCKET")"
  kctl -n "$OBJECT_STORE_NAMESPACE" create secret generic object-store-s3-config \
    --from-file=seaweedfs_s3_config=<(printf '%s' "$config") --dry-run=client -o yaml | kctl apply -f - >/dev/null
  helm_install "$OBJECT_STORE_RELEASE" "$OBJECT_STORE_NAMESPACE" "$SEAWEEDFS_CHART" "$SEAWEEDFS_VERSION" \
    --repo "$SEAWEEDFS_REPO" --values "$PLATFORM_DIR/object-store/values.yaml"
  # `weed shell` talks to the server's master and filer (WEED_CLUSTER_* in the pod) and keeps its history in $HOME.
  buckets="$(weed_shell s3.bucket.list)" || die "could not list the object store's buckets: $buckets"
  if awk '{ print $1 }' <<<"$buckets" | grep -qx "$BACKUP_BUCKET"; then
    log "bucket $BACKUP_BUCKET already exists"
  else
    log "creating bucket $BACKUP_BUCKET"
    weed_shell "s3.bucket.create -name $BACKUP_BUCKET" >/dev/null
  fi
}

install_object_store
