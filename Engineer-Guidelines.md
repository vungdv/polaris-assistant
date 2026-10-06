# Goals

Our goal is developing in an environment as close as possible to a production system and have full capacity of a local development: 
- testability: unit/integration tests, and end-2-end/performance tests.
- deployment pipeline like code & debug, build, then deploy 
- observe telemetry, metrics 

We don't need to do all of those tasks every time, but if we need we can do both as human developer or an AI code agent. 

# Guide Lines

These concise guidelines capture the repo-specific architectural patterns, operational conventions, and quality gates that human engineers and AI agents must maintain. They assume familiarity with `README.md` and `docker-compose.yml`.

---

## 1. Local Network, Routing & Security Conventions

- **Single Gateway Entrypoint (`:443`)**: All external HTTP/HTTPS traffic must route through the Nginx gateway. Application containers do not expose raw host ports; access is strictly via configured domains and sub-paths.
- **Canonical Endpoints & Domain Architecture**:
  - `https://polaris.local` — Unified Application Gateway routing all application services via sub-paths:
    - Core APIs: `/api/v1/products`, `/api/v1/categories`, `/api/v1/orders`
    - MCP Server: `/mcp/sse`, `/mcp/message`
    - AI Assistant: `/api/v1/assistant/**` (e.g. `/api/v1/assistant/chat`)
    - Swagger UI: `/swagger-ui/index.html` (multi-API selector for Core and Assistant)
  - `https://id.polaris.local` — Dedicated Identity Provider (Keycloak, `polaris` realm).
  - `https://grafana.polaris.local` — Observability & Telemetry UI.
- **TLS Truststore Requirement**: All services communicate over trusted local TLS created by `mkcert`. Any Java process connecting to internal HTTPS endpoints (e.g. Keycloak token validation) must mount and reference the truststore:
  ```bash
  JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/certs/truststore.jks"
  ```
  For host-based development, run `./scripts/setup-local-https-mac-m1.sh` once.
- **Zero-Bypass OAuth2 / OIDC**: Every business and MCP endpoint requires a valid Keycloak Bearer JWT. Swagger UI uses PKCE with client `polaris-app`. In automated unit/slice tests, generate tokens via `JwtMockFactory` rather than disabling security filters.

---

## 2. Code, Debug & Deployment Pipeline (Inner Loop)

- **Monorepo Layout & Packaging**:
  - `libs/polaris-common`: Shared kernel, security filter chain, global RFC 7807 exception handler, and Flyway migrations.
  - `apps/polaris`: Core service encapsulating Catalog, Order, and in-process MCP server (`/mcp/sse`).
  - `apps/polaris-assistant`: Decoupled AI Assistant service. Communicates with Polaris Core strictly via MCP.
- **Execution Modes**:
  - **Full Containerized Stack**:
    ```bash
    make up                # Boot entire stack (apps, DBs, Keycloak, LGTM telemetry)
    make status            # Inspect container health
    make restart-<service> # Fast single-service reload (e.g. make restart-polaris)
    make down              # Stop containers and preserve dev volumes
    ```
  - **Host Debugging (Fast Inner Loop)**: Keep infrastructure in Docker (`polaris-db`, `keycloak`, `nginx`, `otel-collector`) and run the target application locally:
    ```bash
    make run               # Run apps/polaris on host (port 8080)
    make run-assistant     # Run apps/polaris-assistant on host (port 8081)
    ```
- **Forward-Only Schema Migrations (Flyway)**:
  - Database schema evolution is managed strictly via Flyway scripts in `libs/polaris-common/src/main/resources/db/migration/V{N}__<description>.sql`.
  - Ad-hoc DDL or manual schema mutation is prohibited. Inspect the database directly using `make polaris-sql`.

---

## 3. Architecture & Bounded Context Containment

- **Vertical Slice Completeness**: Every feature or bug fix must be implemented as a complete vertical slice through its bounded context (`Controller` &rarr; `Service` &rarr; `Repository` &rarr; `DB`). Never commit unwired layers or placeholder stubs.
- **Strict Bounded Context Isolation (ADR-0008)**:
  - `polaris-assistant` is decoupled from core commerce databases. It must **never** import Polaris entities/repositories or query core DB tables directly.
  - Cross-context capabilities are consumed exclusively via Model Context Protocol (`/mcp/sse`, `/mcp/message`).
- **Inventory Concurrency & Invariants (ADR-0007)**:
  - Stock reservations during checkout must use pessimistic write locking (`PESSIMISTIC_WRITE`) on inventory records to eliminate race conditions and dirty data under concurrent load.
  - Order state machine transitions strictly follow `CREATED` &rarr; `PROCESSING` &rarr; `SHIPPED` (or `CANCELLED`). Shipped orders cannot be cancelled; invalid state transitions must return `409 Conflict`.
- **Standard RFC 7807 Problem Details**: All error responses must use `ProblemDetail` via `GlobalExceptionHandler`. Never return `200 OK` with an error payload.

---

## 4. Testability & Verification Strategy

Verification follows a strict multi-tier hierarchy:

- **Tier 1: Automated Unit & Integration Tests**:
  - Run `mvn clean test` (or `mvn test -pl apps/polaris`) before completing any slice.
  - Mock only external boundary seams; exercise domain logic, validation rules, and persistence deterministically.
- **Tier 2: API Performance & Concurrency Validation (k6)**:
  - Located under `tests/perf/`. Run easily via Makefile or directly via Docker:
    ```bash
    # Contract, latency (p95 < 200ms), and W3C traceparent verification:
    make test-perf
    # Or directly: docker run --network host --rm -i -v $(pwd)/tests/perf:/scripts -w /scripts grafana/k6 run api-test.js

    # High-concurrency inventory race condition audit:
    make test-concurrency
    # Or directly: docker run --network host --rm -i -v $(pwd)/tests/perf:/scripts -w /scripts -e SKU=NG-CHARGER-02 grafana/k6 run order-concurrency-test.js
    ```
- **Tier 3: Live End-to-End Session Proofs (Playwright CLI)**:
  - Playwright CLI captures deterministic browser evidence against the live stack (`make playwright-ui`, `tests/e2e/`). Refer to `.agents/skills/playwright-cli/SKILL.md` and `.agents/skills/polaris-dev/SKILL.md`.
  - Session artifacts, snapshots, and screenshots must be stored in `.playwright-cli/` to serve as auditable proof.
- **Architect Verification Gate**: Slice Work Orders (`WO-xxx`) require dual-tier sign-off (clean automated test logs + live Playwright CLI session proof or k6 performance logs) before merge.

---

## 5. Observability & Telemetry by Default

- **Distributed Tracing (W3C)**:
  - Inbound and outbound requests must propagate standard W3C `traceparent` headers (`00-{traceId}-{spanId}-01`).
  - `TraceFilter` echoes the matching `X-Trace-Id` on all HTTP responses.
- **Telemetry Pipeline**:
  - Applications push metrics, traces, and logs via OTLP HTTP to `http://otel-collector:4318`.
  - The collector routes traces to Tempo (`:3200`), logs to Loki (`:3100`), and metrics to Prometheus (`:9090`).
- **Telemetry Inspection (UI & CLI)**:
  - Access Grafana at [https://grafana.polaris.local](https://grafana.polaris.local) (OAuth login via Keycloak or default `admin`/`admin`).
  - Access in-terminal telemetry directly via `make gcx` (interactive TTY) or non-interactive/agent CLI via `./gcx.sh` (e.g. `make gcx-exec CMD="traces query '{resource.service.name=\"polaris\"}'"` or `./gcx.sh traces get <trace-id>`). See `.agents/skills/polaris-dev/SKILL.md` for CLI query recipes.

---

## 6. Fleet Roles & Operating Cadence

When collaborating as a human engineer or autonomous AI agent fleet, maintain clear boundaries and active feedback loops:
- **`coordinator-agent`**: Mediates delivery, preserves role focus, and facilitates bidirectional feedback between `arch-agent` and `domain-dev-agent` without prompt bleed.
- **`arch-agent`**: Owns the high-level big picture: defines business value, acceptance criteria, PRDs (`docs/business/prds/`), ADRs (`docs/technical/decisions/`), interface contracts, and Slice Work Orders (`WO-xxx`). Conducts dual-tier verification audits and business sign-off.
- **`domain-dev-agent`**: Focuses on tightly scoped, bite-sized vertical slices within a single bounded context according to the work order. Provides bottom-up feedback on task sizing, missing infrastructure, or contract friction.