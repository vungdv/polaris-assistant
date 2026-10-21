#!/usr/bin/env bash
# Smoke test for the cluster brought up by `make k8s-up`. Each slice adds its checks as a section in smoke-tests/.
# This file is the high-level list of use cases; the detail of each lives in smoke-tests/<section>.sh, on top of the
# helpers in smoke-tests/lib/. Runs every check, then exits non-zero if any failed.
#
# The sections are sourced, in order, into this shell: they share its helpers and a few variables (a section says so in
# its header when it depends on an earlier one). Each registers its own cleanup, run once on exit.
set -euo pipefail
# shellcheck source=SCRIPTDIR/lib.sh
source "$(dirname "$0")/lib.sh"

ensure_tools kind kubectl
cluster_exists && ensure_kubeconfig

SMOKE_DIR="$(dirname "${BASH_SOURCE[0]}")/smoke-tests"

# shellcheck source=SCRIPTDIR/smoke-tests/lib/report.sh
source "$SMOKE_DIR/lib/report.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/cleanup.sh
source "$SMOKE_DIR/lib/cleanup.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/kube.sh
source "$SMOKE_DIR/lib/kube.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/fixture.sh
source "$SMOKE_DIR/lib/fixture.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/probe.sh
source "$SMOKE_DIR/lib/probe.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/datastores.sh
source "$SMOKE_DIR/lib/datastores.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/kafka.sh
source "$SMOKE_DIR/lib/kafka.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/k6.sh
source "$SMOKE_DIR/lib/k6.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/telemetry.sh
source "$SMOKE_DIR/lib/telemetry.sh"
# shellcheck source=SCRIPTDIR/smoke-tests/lib/host.sh
source "$SMOKE_DIR/lib/host.sh"

# --- Use cases --------------------------------------------------------------------------------------------------

# Test fixture: lets the smoke probes through the default-deny NetworkPolicies for the duration of the run.
# shellcheck source=SCRIPTDIR/smoke-tests/probe-fixture.sh
source "$SMOKE_DIR/probe-fixture.sh"

# K9: Assistant. Chat across replicas, readiness and health, one trace Gateway -> assistant -> polaris, graceful drain.
# shellcheck source=SCRIPTDIR/smoke-tests/k09-assistant.sh
source "$SMOKE_DIR/k09-assistant.sh"

smoke_finish
