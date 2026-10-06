# ADR-0005: Architectural Strategy: Transition to Polyglot Monorepo with Maven Multi-Module Domain Architecture

* Status: Accepted
* Deciders: Polaris Architecture Team, Core Platform Engineering
* Date: 2026-09-10
* Technical Story: Transitioning the Polaris codebase from a flat, single-module Spring Boot project into a standardized Polyglot Monorepo with a Maven multi-module Java reactor, decoupled web chat application, Python MCP client package, and consolidated infrastructure perimeter, strictly enforcing AGENTS.md Principle 1 (Lower-Layer Standardization), Principle 2 (Context Containment & Vertical Slices), and Principle 3 (Anti-Monolith Modularity).

---

## Context and Problem Statement

Polaris has matured from a single Spring Boot demo service into an enterprise polyglot ecosystem comprising:
1. Product Catalog and Order Management bounded contexts.
2. An AI Assistant cognitive tier (Agency orchestrator, deterministic rules, SSE streaming).
3. A dual-transport Model Context Protocol tier (native Spring Boot in-process server and Python MCP CLI).
4. An interactive Web Chat client with Keycloak PKCE authentication.
5. Production-grade container infrastructure (Nginx TLS proxy, Keycloak IdP, PostgreSQL persistence, and LGTM observability stack).
6. Dual-tier verification suites (k6 performance scripts and Playwright CLI live browser proofs).

Previously, the entire repository was structured as a flat Java project where the root folder was conflated with `pom.xml`, with all Java classes sharing a single global classpath. This resulted in:
- **Violation of Context Containment ([AGENTS.md: Principle 2.2](../../../AGENTS.md)):** Lack of compile-time boundaries allowed cross-context JPA entity references (e.g. Order directly coupling to Product entities).
- **Violation of Anti-Monolith Modularity ([AGENTS.md: Principle 3.3](../../../AGENTS.md)):** Heavy third-party dependencies (MCP SDK, OpenTelemetry instrumentation, Flyway) were included globally rather than scoped to the specific contexts requiring them.
- **Polyglot & Architectural Friction:** Python MCP tools (`mcp/`), frontend assets (`src/main/resources/static/chat`), test suites (`tests/k6`), and platform infrastructure (`nginx/`, `keycloak/`, `telemetry/`) lacked structured, standardized locations.

How should Polaris architect its repository and build system to provide strict compile-time domain boundary enforcement while maintaining developer ergonomics, polyglot capability, and zero disruption to the containerized local development stack?

---

## Decision Drivers

* **Compile-Time Bounded Context Isolation ([AGENTS.md: Principle 2.2](../../../AGENTS.md)):** Physically enforce boundary separation between Catalog, Order, Assistant, and Gateway contexts via Maven module boundaries.
* **Anti-Monolith Modularity & Single Responsibility ([AGENTS.md: Principle 3.3](../../../AGENTS.md)):** Deconstruct the monolith into dedicated modules with unidirectional dependency flow and minimal transitive leakage.
* **Standardized Polyglot Taxonomy:** Clearly partition deployables (`apps/`), internal domain libraries (`modules/`), platform environments (`infra/`), and verification suites (`tests/`).
* **Preserved Developer Ergonomics:** Maintain single-command workflows (`mvn test`, `make up`, `make down`, `make test`) with zero breaking changes to public REST or MCP contracts.

---

## Considered Options

* **Option 1: Flat Java Repository with Strict Linter Rules (Status Quo)**
  - Retain single root `pom.xml` and enforce boundaries purely through package conventions and ArchUnit tests.
  - *Drawbacks:* Fails to provide physical classpath isolation; heavy dependencies remain global; does not solve polyglot directory sprawl.
* **Option 2: Polyglot Monorepo with Maven Multi-Module Reactor (Selected)**
  - Restructure root into `apps/`, `modules/`, `infra/`, and `tests/`.
  - Root `pom.xml` acts as reactor parent with `<packaging>pom</packaging>` and `<dependencyManagement>`.
  - Java backend decomposed into 6 modules:
    - `modules/polaris-common`: Shared kernel, RFC 7807 problem details, tracing filters, validation, security helpers.
    - `modules/polaris-catalog`: Catalog Bounded Context entities, repositories, services, and REST controllers.
    - `modules/polaris-order`: Order Bounded Context entities, repositories, state machine, and REST controllers.
    - `modules/polaris-assistant`: Assistant Bounded Context engine, models, session/draft stores, and SSE controllers.
    - `modules/polaris-mcp`: Native Spring Boot MCP Server (`McpSyncServer`, tool registry).
    - `apps/polaris-server`: Spring Boot application entrypoint, composite security chain, and Flyway migrations.
  - Decouple Web Chat into `apps/web-chat` and Python MCP tooling into `apps/mcp-cli`.
  - Consolidate platform into `infra/` (`infra/nginx`, `infra/keycloak`, `infra/telemetry`).
* **Option 3: Multi-Repo Split**
  - Break Polaris into separate Git repositories for backend, frontend, MCP, and infra.
  - *Drawbacks:* High cross-repo coordination overhead, fragmented versioning, complex CI/CD pipelines, and severe disruption to the Polaris Agent Fleet delivery lifecycle.

---

## Decision Outcome

Chosen Option: **Option 2: Polyglot Monorepo with Maven Multi-Module Reactor**.

### Key Architectural Specifications:

1. **Top-Level Taxonomy:**
   - `apps/`: Runnable applications (`polaris-server`, `web-chat`, `mcp-cli`).
   - `modules/`: Internal Java domain modules (`polaris-common`, `polaris-catalog`, `polaris-order`, `polaris-assistant`, `polaris-mcp`).
   - `infra/`: Platform infrastructure (`nginx`, `keycloak`, `telemetry`).
   - `tests/`: System verification (`e2e`, `perf`).
   - `scripts/`: Platform tooling and certificate management.
   - `docs/`: ADRs and Agent Fleet specifications.

2. **Unidirectional Dependency Flow:**
   ```text
   polaris-common (zero domain dependencies)
      ▲               ▲              ▲
      │               │              │
   polaris-catalog  polaris-order  polaris-assistant
      ▲               ▲              │
      │               │              │
      └───────┬───────┘              │
              │                      │
         polaris-mcp                 │
              ▲                      │
              │                      │
              └───────┬──────────────┘
                      │
                polaris-server (Assembly / Runner)
   ```

3. **Multi-Stage Container Build (`dockerfile`):**
   - Copies root and module POMs to resolve dependency layers efficiently.
   - Compiles and packages `apps/polaris-server` into a single, executable Spring Boot runtime container.

4. **Web Chat Asset Bundling:**
   - `apps/web-chat` is the single source of truth for the browser chat client.
   - Packaged automatically into `polaris-server`'s `static/chat` at build time via `apps/polaris-server/pom.xml` resources configuration.

---

## Consequences & Verification

### Positive:
* Strict compile-time enforcement of DDD bounded contexts (`polaris-order` cannot inadvertently reference `polaris-catalog` database entities).
* Isolated, blazing-fast module test execution (`mvn test -pl modules/polaris-catalog`).
* Full backward compatibility: `mvn test` runs all 83 tests across all modules with 100% pass rate.
* Containerized multi-stage build verified (`docker compose build polaris` produces identical production image).
* Zero breaking changes to external HTTP/REST endpoints, SSE events, or MCP tool contracts.

### Negative / Operational Trade-offs:
* Managing multiple child `pom.xml` files requires centralized `<dependencyManagement>` governance in the parent reactor POM.
