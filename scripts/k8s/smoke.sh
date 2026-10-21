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

# K1: the namespace exists and Pod Security `restricted` is enforced, not just labelled.
# shellcheck source=SCRIPTDIR/smoke-tests/k01-namespace.sh
source "$SMOKE_DIR/k01-namespace.sh"

# Test fixture: lets the smoke probes through the default-deny NetworkPolicies for the duration of the run.
# shellcheck source=SCRIPTDIR/smoke-tests/probe-fixture.sh
source "$SMOKE_DIR/probe-fixture.sh"

# K3: edge. TLS, Gateway and DNS work from a pod; sends the traced request that K4 looks for.
# shellcheck source=SCRIPTDIR/smoke-tests/k03-edge.sh
source "$SMOKE_DIR/k03-edge.sh"

# K4: observability pipeline. The Collector receives the Gateway's span and access log, with Kubernetes attributes.
# shellcheck source=SCRIPTDIR/smoke-tests/k04-observability.sh
source "$SMOKE_DIR/k04-observability.sh"

# K5: data stores. PostgreSQL survives losing its primary pod; Redis is configured as in Compose.
# shellcheck source=SCRIPTDIR/smoke-tests/k05-data-stores.sh
source "$SMOKE_DIR/k05-data-stores.sh"

# K6: Kafka. Losing one node loses no acks=all write.
# shellcheck source=SCRIPTDIR/smoke-tests/k06-kafka.sh
source "$SMOKE_DIR/k06-kafka.sh"

# K7: identity. Keycloak issues tokens through the Gateway, from a pod and from the host, and exports spans.
# shellcheck source=SCRIPTDIR/smoke-tests/k07-identity.sh
source "$SMOKE_DIR/k07-identity.sh"

# K8: Order & Catalog and Swagger UI. Flyway, outbox relay, one trace into polaris, load and rolling restart.
# shellcheck source=SCRIPTDIR/smoke-tests/k08-order-catalog.sh
source "$SMOKE_DIR/k08-order-catalog.sh"

# K9: Assistant. Chat across replicas, readiness and health, one trace Gateway -> assistant -> polaris, graceful drain.
# shellcheck source=SCRIPTDIR/smoke-tests/k09-assistant.sh
source "$SMOKE_DIR/k09-assistant.sh"

# K10: Fulfilment. The emulator's wiring, the order flow end to end, and one trace across Kafka.
# shellcheck source=SCRIPTDIR/smoke-tests/k10-fulfilment.sh
source "$SMOKE_DIR/k10-fulfilment.sh"

# K11: hardening. NetworkPolicies, Pod Security, autoscaling, backup and restore, node drain under load.
# shellcheck source=SCRIPTDIR/smoke-tests/k11-hardening.sh
source "$SMOKE_DIR/k11-hardening.sh"

# K12: local LGTM stack. Tempo, Loki, Prometheus and Grafana hold the signals, with SSO through Keycloak.
# shellcheck source=SCRIPTDIR/smoke-tests/k12-lgtm.sh
source "$SMOKE_DIR/k12-lgtm.sh"

smoke_finish
