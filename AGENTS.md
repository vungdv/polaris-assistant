# Architecture, Design & Code Principles

## Principle 1: Simplicity via Standardization & Lower-Layer Alignment

> **Core Tenet:** For every problem, align directly with standard, established lower-layer protocols and industry specifications rather than bespoke abstractions.

### 1. REST APIs & HTTP Semantics
- **Strict Protocol Conformance (RFC 9110):** Use HTTP verbs (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`) strictly according to standard semantics.
- **Accurate Status Codes:** Return standard HTTP status codes; never return `200 OK` with an error message payload.
- **Standard Headers:** Leverage standard headers for cache control, authorization, content negotiation, and idempotency (`Idempotency-Key`).
- **Standard Error Payloads (RFC 7807):** Format all API error responses as Problem Details.

### 2. Architecture & Security Standards
- **Industry-Standard Security:** Adhere strictly to OAuth 2.0, OIDC, PKCE, and JWT/JWKS; never implement custom auth or crypto schemes.
- **Contract & Event Standards:** Align schemas with OpenAPI 3.x and event messaging with CloudEvents.
- **Configuration as Data (12-Factor):** Source all config and credentials from the environment or config service; never hardcode credentials or secrets.
- **Resilience Patterns:** Use named cross-service resilience patterns (timeouts, backoff retries, circuit breakers) rather than bespoke retry loops.

### 3. Business & Operational Alignment
- **Domain Boundaries:** Align service and module boundaries with DDD bounded contexts and operational ownership.
- **Walkable Architecture:** Keep end-to-end request flows transparent without unexplained indirection or excessive proxy layers.
- **Traceable Integration Points:** Every capability claimed in documentation must map to a visible component and walkable path.
- **Testing Alignment:** Contract and integration tests must exercise the same standard interfaces (REST, OIDC, OTLP) used in production.

### 4. Data & Schema Standards
- **Schema Migrations:** Manage database changes via version-controlled, forward-only migrations (Flyway/Liquibase) without ad-hoc DDL.
- **Dev-Prod Parity:** Ensure local development database behavior does not diverge from production semantics in ways that mask defects.
- **Explicit Versioning:** Use additive-first API versioning with documented deprecation windows for breaking changes.

---

## Principle 2: Change Scope — Minimal, Bounded, and Vertically Complete

> **Core Tenet:** Every change should be as small as possible, but never smaller than one complete vertical slice through a single bounded context.

### 1. Lower Bound — Vertical Completeness
- **End-to-End Slices:** Every change must touch all layers necessary to function end-to-end within its bounded context (controller → service → repository → DB); never leave layers stubbed or unwired.
- **Seam Splitting:** If a full slice is too large, split strictly along internal seams (e.g. controller↔service) defined by explicit, contract-tested interfaces.
- **Test Alignment:** Exercise seam contracts in automated tests identically to production behavior.

### 2. Upper Bound — Context Containment
- **Strict Boundary Isolation:** Never directly access domain logic, repositories, or database tables belonging to another bounded context.
- **Contract-Based Integration:** Cross-context interactions must occur exclusively through published contracts (OpenAPI REST endpoints or CloudEvents).
- **Per-Context Changes:** Multi-context features must be split into independent changes per context, each satisfying vertical completeness.

### 3. Sizing Discipline & Verification
- **Minimal Feasible Diff:** Avoid bundling unrelated features, speculative abstractions, or out-of-scope improvements.
- **Boundary Warning Signals:** Treat edits to another context's internals as a signal to introduce a published contract rather than widening change scope.
- **Pre-Merge Self-Check:** Verify: (1) Does it work end-to-end? (2) Does it modify only one bounded context? (3) Are cross-context interactions via published contracts?

---

## Principle 3: Cross-Cutting Engineering Discipline — Observability, Quality & Anti-Monolith Modularity

> **Core Tenet:** Cross-cutting concerns are foundational architectural requirements. Every component must be observable by default, verified through automated tests, and decomposed into single-responsibility units.

### 1. Observability by Default (Tracing, Logging & Metrics)
- **Distributed Tracing:** Propagate standard W3C Trace Context (`traceparent`) across all external entrypoints (HTTP, messaging, RPC/MCP) and record status and exceptions on active spans.
- **Correlated Structured Logging:** Emit machine-readable logs (JSON/key-value) injecting active `trace_id` and `span_id`; isolate logs from protocol `stdout`.
- **Telemetry Metrics:** Standardize core operational metrics (invocations, latency histograms, error rates) and export asynchronously via standard protocols (OTLP/Prometheus).

### 2. Test-Driven Verification by Default
- **Automated Verification Floor:** Require automated tests for every feature, bug fix, and refactor before merge; never bypass verification.
- **Seam & Contract Testing:** Test against explicit interface seams; mock only at external boundary interfaces and verify business logic deterministically.
- **Comprehensive Scenarios:** Test happy paths, edge cases, error conditions, resilience timeouts, and telemetry propagation.

### 3. Anti-God Class & Anti-God File Architecture
- **Single Responsibility:** Deconstruct monolithic classes and files into dedicated single-purpose modules (`config`, `auth`, `client`, `domain`, `server`, `telemetry`, `cli`).
- **Unidirectional Dependencies:** Ensure dependencies flow strictly in one direction (orchestration depends on abstractions; infrastructure implements interfaces) with no circular references.
- **Preserve Contracts via Facades:** Use Facade patterns when refactoring legacy classes to maintain backwards compatibility while delegating to modular subsystems.