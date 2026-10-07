#!/usr/bin/env bash
# Builds the application images from their Dockerfiles as $IMAGE_REGISTRY/<app>:<git-sha>, the tag the local overlay
# pins, checks that each runs as a numeric non-root UID, and loads it into the kind cluster (no registry needed, D9).
# Usage: scripts/k8s/images.sh [app...]   (no argument = all three)
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind
command -v docker >/dev/null || die "docker is required to build the images"
cluster_exists || die "kind cluster '$KIND_CLUSTER_NAME' doesn't exist; run 'make k8s-up' first"

[ "$#" -gt 0 ] || set -- "${APPS[@]}"
for app in "$@"; do
  [[ " ${APPS[*]} " == *" $app "* ]] || die "unknown app '$app' (one of: ${APPS[*]})"
done

write_image_pins
for app in "$@"; do
  image="$(app_image "$app")"
  log "building $image"
  docker build -f "$REPO_ROOT/apps/$app/Dockerfile" -t "$image" "$REPO_ROOT"
  "$REPO_ROOT/scripts/k8s/check-image-user.sh" "$image"
  log "loading $image into kind cluster '$KIND_CLUSTER_NAME'"
  kind load docker-image --name "$KIND_CLUSTER_NAME" "$image"
done
log "images loaded; overlays/local pins tag $IMAGE_TAG"
