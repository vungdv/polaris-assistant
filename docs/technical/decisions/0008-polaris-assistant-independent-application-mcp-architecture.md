# ADR-0008: Isolation of Polaris Assistant as an Independent Application with Model Context Protocol (MCP) Integration

* Status: Accepted
* Deciders: Polaris Architecture Team, Core Platform Engineering
* Date: 2026-09-11
* Technical Story: Decoupling `modules/polaris-assistant` into an autonomous runnable application (`apps/polaris-assistant`) communicating with Polaris Core exclusively via Model Context Protocol (MCP), supporting external tool/MCP integration, dedicated containerization, and independent domain routing at `assistant.polaris.local`.

---

## Context and Problem Statement

Initially, Polaris embedded its experimental AI Assistant capabilities as an internal Maven module (`modules/polaris-assistant`), packaged directly within the core Spring Boot backend runtime (`apps/polaris-server`). While convenient for early prototyping, this embedded architecture created critical liabilities as the assistant grew:

1. **Tight Coupling & Classpath Leakage:** The assistant resided within the same execution unit as core domain services, creating the risk of direct dependency coupling and violating [`AGENTS.md`](../../../AGENTS.md) Principle 2 (Bounded Context Isolation).
2. **Monolithic Scaling & Resource Contention:** AI workload orchestration (model inference loops, token parsing, tool dispatching) shares CPU and memory threads with high-throughput transactional commerce operations (inventory reservation, checkout).
3. **Rigid Tool Integration:** An embedded assistant is restricted to local service calls rather than standard, discoverable lower-layer protocols.
4. **Single-Domain Presentation:** Both core commerce endpoints and chat endpoints were forced under `https://polaris.local`, preventing independent routing, canary deployments, or isolated TLS policies.

How should Polaris architect its repository and deployment model to transform the Polaris Assistant into a fully isolated application that consumes Polaris product catalog and order capabilities via standard MCP, integrates arbitrary external MCP tools, and runs under its own domain and container lifecycle?

---

## Decision Drivers

* **Bounded Context Isolation ([AGENTS.md: Principle 2.2](../../../AGENTS.md)):** Strict physical and compile-time isolation. The Assistant App must never import Catalog or Order entities, repositories, or services.
* **Standard Protocol Alignment ([AGENTS.md: Principle 1.1 & 1.2](../../../AGENTS.md)):** Inter-application communication must use standard protocols. Tool discovery and invocation between the Assistant and Polaris Core must adhere strictly to Model Context Protocol (MCP, specification version 2024-11-05).
* **Extensibility for External Tools & MCPs:** The Assistant App must serve as a multi-MCP client gateway, able to aggregate tools from Polaris Core and external MCP servers (e.g. Search, Weather, Logistics, CRM).
* **Operational Autonomy & Dedicated Ingress:** Independent `Dockerfile`, container, health check lifecycle, and virtual host routing at `https://assistant.polaris.local`.
* **End-to-End Observability ([AGENTS.md: Principle 3.1](../../../AGENTS.md)):** Unbroken distributed tracing (W3C `traceparent`) and correlated structured logging across Web UI -> Assistant App -> Polaris Core MCP Server -> PostgreSQL.

---

## Decision Outcome

Chosen Option: **Decoupled Autonomous Application (`apps/polaris-assistant`) with Multi-MCP Client Hub**.

### Key Architectural Specifications:

1. **Repository & Build Separation:**
   - Moved `modules/polaris-assistant` to `apps/polaris-assistant`.
   - `apps/polaris-assistant` is an executable Spring Boot application with its own main class (`PolarisAssistantApp`), application configuration, and packaging.
   - `apps/polaris-server` removes `polaris-assistant` from its dependencies. Both applications build independently under the parent reactor POM.

2. **Inter-Application Communication via MCP:**
   - Polaris Core (`apps/polaris-server`) acts as the **MCP Server** via `modules/polaris-mcp`, exposing tools over `/mcp/sse` and `/mcp/message`.
   - Polaris Assistant (`apps/polaris-assistant`) acts as the **MCP Client** (`PolarisMcpClient`, `HttpPolarisMcpClient`), invoking tools (`search_available_products`, `get_product_by_sku`, `place_order`, etc.) over HTTP/SSE.

3. **Extensible Multi-MCP Client Hub:**
   - Introduced `PolicyToolManager` in `apps/polaris-assistant` to dynamically aggregate tool definitions from Polaris Core and registered external MCP servers.
   - Dispatches model tool calls dynamically to the correct MCP connection based on tool capability matching.

4. **Dedicated Ingress & TLS Routing:**
   - Nginx is configured with virtual host routing:
     - `polaris.local`: Routes to `polaris:8080` (Core REST APIs & MCP Server).
     - `assistant.polaris.local`: Routes to `polaris-assistant:8081` (Assistant Chat API, SSE streaming).
   - Local TLS certificates updated via `setup-local-https-mac-m1.sh` with Subject Alternative Name (SAN) covering `assistant.polaris.local`.

5. **Containerization & Deployment (`docker-compose.yml`):**
   - Separate multi-stage Dockerfiles: `apps/polaris-server/Dockerfile` and `apps/polaris-assistant/Dockerfile`.
   - Both services orchestrate within `polaris-net` and export OpenTelemetry traces, metrics, and logs to `otel-collector:4318`.

---

## Positive Consequences

* **Zero Domain Coupling:** Catalog and Order domains can evolve without risk of breaking assistant internals, and assistant LLM logic can be refactored without touching commerce core.
* **Unified Tool Ecosystem:** Adding external MCP servers (e.g. documentation search, real-time logistics) requires only registering them with `PolicyToolManager`, without modifying Polaris Core.
* **Independent Scaling:** AI workloads can scale horizontally independent of core transactional databases.
* **Standards Compliance:** Full adherence to RFC 9110, RFC 7807, MCP 2024-11-05, and W3C Trace Context.
