# ADR-0009: Unified Gateway Sub-Path Routing for Application Microservices (polaris.local/api/v1/*)

* Status: Accepted
* Deciders: Polaris Fleet Architect, Platform Engineering
* Date: 2026-09-12
* Technical Story: Transitioning multi-service application ingress from per-microservice subdomains (`assistant.polaris.local`) to unified gateway sub-path routing under canonical `https://polaris.local` (e.g. `/api/v1/assistant/**` routing to `polaris-assistant:8081`), while preserving strict physical and cryptographic domain isolation for Keycloak Identity Provider at `https://id.polaris.local`.

---

## Context and Problem Statement

Following [ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md), Polaris decomposed its AI assistant into an autonomous application (`apps/polaris-assistant`). Initially, this service was exposed via a dedicated subdomain: `https://assistant.polaris.local`.

However, maintaining discrete subdomains for each internal application service introduced operational frictions and architectural trade-offs:

1. **Origin Fragmentation & CORS Complexity ([AGENTS.md: Principle 1.1 & 1.2](../../../AGENTS.md)):** Separate origins (`polaris.local` and `assistant.polaris.local`) complicate client web integration, requiring cross-origin resource sharing (CORS) preflights, distinct OIDC client registrations, and fragmented browser cookie boundaries.
2. **Infrastructure & DNS Overhead:** Introducing each new microservice required updating `/etc/hosts`, generating expanded Subject Alternative Names (SANs) in TLS certificates, and configuring separate Nginx server blocks.
3. **Fragmented Developer Experience:** Developers and interactive API users had to navigate distinct Swagger UI portals across multiple hostnames rather than inspecting the system through a unified API gateway.
4. **Boundary Distinction between Applications and Identity:** While application microservices share client sessions and business orchestration context, Identity & Access Management (Keycloak) requires strict domain and cookie isolation to adhere to OIDC Discovery (RFC 8414) and prevent session hijacking.

How should Polaris architect edge ingress routing to unify application microservices under a single canonical gateway while maintaining necessary security boundaries for identity?

---

## Decision Drivers

* **Single Origin for Business APIs ([AGENTS.md: Principle 1.1](../../../AGENTS.md)):** Consolidate all application services under `https://polaris.local` to simplify browser client calls, eliminate cross-origin complexity, and align with standard API Gateway routing patterns.
* **Domain Isolation for Identity ([AGENTS.md: Principle 1.2](../../../AGENTS.md)):** Retain `https://id.polaris.local` as an independent domain for Keycloak to uphold standard OIDC discovery specs, protect auth session cookies, and preserve existing token issuer guarantees (`iss: https://id.polaris.local/realms/polaris`).
* **Clean Bounded Context Routing ([AGENTS.md: Principle 2.2](../../../AGENTS.md)):** Ensure gateway routing strictly respects service boundaries via explicit sub-paths (`/api/v1/assistant/*` &rarr; `polaris-assistant:8081`, Core Catalog/Order/MCP &rarr; `polaris:8080`).
* **Unified OpenAPI Discovery:** Enable developers to inspect both Core Commerce and Assistant APIs from a centralized Swagger UI interface (`https://polaris.local/swagger-ui/index.html`).
* **Zero-Coupling Transport:** Maintain HTTP/1.1 streaming and Server-Sent Events (SSE) support for the AI assistant without requiring application code changes.

---

## Considered Options

### Option 1: Retain Subdomain-Per-Microservice (`*.polaris.local`)
Keep `assistant.polaris.local` alongside `polaris.local`.
* *Drawbacks:* Forces multiple TLS SANs, complex hosts file maintenance, CORS overhead across application boundaries, and fragmented API documentation portals.

### Option 2: Full Single-Domain Migration (Including Identity Provider)
Move all applications and Keycloak under `polaris.local` (e.g. `polaris.local/auth`).
* *Drawbacks:* Violates cookie isolation between IdP and client apps; complicates OIDC token endpoint discovery; requires extensive Keycloak realm reconfigurations; breaks existing client token validation.

### Option 3: Unified Application Gateway with Sub-Path Ingress + Dedicated Identity Domain (Selected)
Retain `https://id.polaris.local` for Keycloak and `https://grafana.polaris.local` for Observability, while consolidating all application services behind `https://polaris.local` using prefix-based gateway sub-paths:
* `/api/v1/products`, `/api/v1/categories`, `/api/v1/orders` &rarr; Core App (`polaris:8080`)
* `/mcp/` &rarr; Core App MCP Server (`polaris:8080`)
* `/api/v1/assistant/` &rarr; Polaris Assistant (`polaris-assistant:8081`)
* `/swagger-ui/` &rarr; Unified API Portal (`polaris:8080`)

---

## Decision Outcome

Chosen Option: **Option 3: Unified Application Gateway with Sub-Path Ingress + Dedicated Identity Domain**.

### Architectural Specifications

#### 1. Ingress & Reverse Proxy Routing (`docker/nginx/nginx.conf`)
- Removed `assistant.polaris.local` from port 80 HTTP redirection and deleted its standalone port 443 `server` block.
- Configured prefix routing on `polaris.local` (`:443`):
  ```nginx
  server {
    listen 443 ssl;
    server_name polaris.local;

    # Core Application & Default Ingress
    location / {
      proxy_pass http://polaris:8080;
      ...
    }

    # MCP SSE Endpoint Buffer Disabling
    location /mcp/ {
      proxy_pass http://polaris:8080;
      ...
    }

    # Standalone Swagger UI Portal
    location /swagger-ui {
      proxy_pass http://swagger-ui:8080;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host $host;
      proxy_set_header X-Forwarded-Port 443;
    }

    location = /swagger-ui.html {
      return 301 https://$host/swagger-ui/index.html;
    }

    # AI Assistant OpenAPI Specification
    location /v3/api-docs/assistant {
      proxy_pass http://polaris-assistant:8081;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host $host;
      proxy_set_header X-Forwarded-Port 443;
    }

    # AI Assistant Application (Sub-path routing)
    location /api/v1/assistant {
      proxy_pass http://polaris-assistant:8081;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host $host;
      proxy_set_header X-Forwarded-Port 443;

      # Real-time SSE streaming support
      proxy_http_version 1.1;
      proxy_set_header Connection '';
      proxy_buffering off;
      proxy_cache off;
    }
  }
  ```

#### 2. Service Orchestration & DNS Aliasing (`docker-compose.yml`)
- Introduced dedicated `swagger-ui` container service (`swaggerapi/swagger-ui:v5.17.14`) attached to `polaris-net`.
- Configured multi-spec `URLS` in `swagger-ui`:
  ```yaml
  swagger-ui:
    image: swaggerapi/swagger-ui:v5.17.14
    container_name: swagger-ui
    environment:
      URLS: >
        [
          {url: "/v3/api-docs", name: "Product Catalog API"},
          {url: "/v3/api-docs/assistant", name: "Assistant API"}
        ]
      BASE_URL: /swagger-ui
      OAUTH_CLIENT_ID: polaris-app
      OAUTH_USE_PKCE: "true"
    expose:
      - "8080"
    networks:
      - polaris-net
  ```
- Declared `swagger-ui` as a dependency for `nginx` (`nginx.depends_on: [..., swagger-ui]`).
- Removed `assistant.polaris.local` alias from `nginx` container on `polaris-net`.
- Network aliases for `nginx` are now strictly:
  - `polaris.local` (Primary application gateway)
  - `id.polaris.local` (Identity Provider)
  - `grafana.polaris.local` (Observability)

#### 3. OpenAPI Specification Path Alignment
- Configured `apps/polaris-assistant` to publish its OpenAPI specification at `/v3/api-docs/assistant`.
- Nginx reverse-proxies `/v3/api-docs/assistant` directly to `http://polaris-assistant:8081`, while `/v3/api-docs` routes to `http://polaris:8080` (Product Catalog API).
- Developers access `https://polaris.local/swagger-ui/index.html` and switch between "Product Catalog API" and "Assistant API" via the dropdown selector, with preconfigured Keycloak OAuth2 PKCE authorization (`polaris-app`).

#### 4. Local Developer Environment (`scripts/setup-local-https-mac-m1.sh`)
- Updated `DOMAINS` array to `("polaris.local" "id.polaris.local" "grafana.polaris.local")`, removing obsolete `/etc/hosts` mapping and cert SAN for `assistant.polaris.local`.

---

## Consequences

### Positive
* **Simplified Client Integration:** Single origin (`https://polaris.local`) eliminates CORS issues and extra Keycloak redirect URIs.
* **Unified API Portal:** Centralized Swagger UI provides consolidated API discovery for all application microservices.
* **Standards Compliance:** Full preservation of OIDC standards and cookie boundaries at `id.polaris.local`.
* **Zero Application Code Changes:** `AssistantChatController` path mapping (`/api/v1/assistant/chat`) directly matches gateway prefix routing.
* **Reduced Operational Overhead:** No new DNS entries or certificate SANs needed when adding future application sub-paths.

### Negative / Trade-offs
* Gateway path conflicts must be governed: microservices must namespace their REST endpoints (e.g. `/api/v1/<service-name>/**`) to prevent routing collisions.
