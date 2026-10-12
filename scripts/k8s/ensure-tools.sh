#!/usr/bin/env bash
# Ensures the pinned tools (kind, kubectl, helm; see tools.sh) and docker are available. Safe to re-run.
echo "Tools (kind kubectl helm) checking..."
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind kubectl helm
command -v docker >/dev/null || die "docker is required by kind"

echo "Tools (kind kubectl helm) Done!"
