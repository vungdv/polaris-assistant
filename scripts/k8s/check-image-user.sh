#!/usr/bin/env bash
# Fails unless the image's configured user is a numeric, non-root UID (optionally :GID), which Kubernetes
# `runAsNonRoot` can verify without resolving a user name (TR-K10), and the container really runs as that UID.
# Usage: scripts/k8s/check-image-user.sh <image>
set -euo pipefail

image="${1:?usage: $0 <image>}"
user="$(docker image inspect --format '{{.Config.User}}' "$image")"
uid="${user%%:*}"

if ! [[ "$user" =~ ^[0-9]+(:[0-9]+)?$ ]]; then
  echo "FAIL: $image USER is '$user', not a numeric UID[:GID]" >&2
  exit 1
fi
if [ "$uid" -eq 0 ]; then
  echo "FAIL: $image runs as root (USER $user)" >&2
  exit 1
fi
actual="$(docker run --rm --entrypoint id "$image" -u)"
if [ "$actual" != "$uid" ]; then
  echo "FAIL: $image declares UID $uid but runs as $actual" >&2
  exit 1
fi
echo "OK: $image runs as non-root UID $user"
