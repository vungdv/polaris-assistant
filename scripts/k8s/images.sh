#!/usr/bin/env bash
# Builds the application images from their Dockerfiles as $IMAGE_REGISTRY/<app>:<git-sha>, the tag the local overlay
# pins, checks that each runs as a numeric non-root UID, and loads it into the kind cluster (no registry needed, D9).
# Usage: scripts/k8s/images.sh [app...]   (no argument = all three)
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind kubectl
command -v docker >/dev/null || die "docker is required to build the images"
cluster_exists || die "kind cluster '$KIND_CLUSTER_NAME' doesn't exist; run 'make k8s-up' first"
ensure_kubeconfig

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
  # Pods created before the image was loaded wait in an image-pull back-off of up to 5 minutes (the tag isn't in any
  # registry they can pull from). Replace them so they start now; the ReplicaSet recreates them together.
  stuck="$(kctl -n "$K8S_NAMESPACE" get pods -l "app.kubernetes.io/name=$app" -o jsonpath='{range .items[*]}{.metadata.name} {.status.containerStatuses[0].image} {.status.containerStatuses[0].state.waiting.reason}{"\n"}{end}' 2>/dev/null |
    awk -v image="$image" '$2 == image && ($3 == "ImagePullBackOff" || $3 == "ErrImagePull") { print $1 }' || true)"
  if [ -n "$stuck" ]; then
    log "restarting the pods that were waiting for $image: $(tr '\n' ' ' <<<"$stuck")"
    # shellcheck disable=SC2086 # one pod name per word
    kctl -n "$K8S_NAMESPACE" delete pod $stuck --wait=false >/dev/null
  fi
done
log "images loaded; overlays/local pins tag $IMAGE_TAG"
