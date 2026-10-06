# ADR-0010: MCP Client Authentication & User Token Forwarding Strategy

* Status: Accepted
* Deciders: Polaris Fleet Architect, Security & Platform Engineering
* Date: 2026-09-15
* Technical Story: Establishing an authentication and authorization strategy for `HttpPolarisMcpClient` communicating with the Polaris Core Model Context Protocol (MCP) server over HTTP/SSE (`/mcp/sse` and `/mcp/message`), propagating Keycloak user Bearer tokens with service token fallback.

---

## Context and Problem Statement

Following [ADR-0008](0008-polaris-assistant-independent-application-mcp-architecture.md) and [ADR-0009](0009-gateway-subpath-routing-for-applications.md), Polaris decomposed its AI assistant into an independent application (`apps/polaris-assistant`) that consumes Polaris Core transactional tools (`search_available_products`, `get_product_by_sku`, `place_order`, etc.) exclusively across the Model Context Protocol (MCP) boundary over HTTP SSE.

Both Polaris Core (`apps/polaris`) and Polaris Assistant (`apps/polaris-assistant`) authenticate users via Keycloak (`https://id.polaris.local`, realm `polaris`) using OAuth 2.0 and OpenID Connect (OIDC). Polaris Core secures its `/mcp/**` endpoints with Spring Security's OAuth 2.0 Resource Server filter chain (`.anyRequest().authenticated()`), rejecting unauthenticated calls with `401 Unauthorized`.

However, `HttpPolarisMcpClient` in `apps/polaris-assistant` was initially implemented without authentication headers. In secure environments, this causes tool discovery and tool execution to fail with HTTP 401.

How should `HttpPolarisMcpClient` authenticate requests against the Polaris Core MCP server while upholding security, user auditability, and operational simplicity?

---

## Decision Drivers

* **Zero-Bypass OAuth2 / OIDC ([AGENTS.md: Principle 1.2](../../../AGENTS.md) & [Engineer-Guidelines.md](../../../Engineer-Guidelines.md)):** All external and inter-service entrypoints must require standard Bearer JWT authentication; custom headers or bypassing security filters is prohibited.
* **Audit & User Context Preservation:** Commercial operations executed via tools (such as `place_order`) must preserve the calling user's identity (`sub`, `preferred_username`, scopes) on Polaris Core for transactional auditing and ownership validation.
* **Resilience & Connection Lifecycle ([AGENTS.md: Principle 1.2](../../../AGENTS.md)):** MCP HTTP/SSE transport (`HttpClientSseClientTransport`) involves both a long-lived SSE stream (`GET /mcp/sse`) and discrete JSON-RPC message calls (`POST /mcp/message`). The client must handle multi-tenant/multi-user turns, token expiration, and automatic reconnection cleanly.
* **Dev-Prod Parity & Testability ([AGENTS.md: Principle 1.4](../../../AGENTS.md)):** The solution must support automated slice tests and local development where security may use mock tokens or static test tokens without requiring a running Keycloak server.

---

## Considered Options

### Option 1: Direct User Bearer Token Forwarding (Delegation)
Extract the end-user Bearer JWT from `SecurityContextHolder.getContext().getAuthentication()` on the active request thread and forward it to Polaris Core via `Authorization: Bearer <token>`.
* **Pros:**
  * Exact end-user identity (`sub`, `roles`) is preserved on Polaris Core.
  * No separate service credentials or client secrets to manage.
  * Aligns with zero-trust identity propagation.
* **Drawbacks:**
  * Cannot authenticate background jobs, startup tool discovery, or anonymous chat requests where no user `SecurityContext` is present.
  * Long-lived SSE streams initialized under one user's token might expire or conflict across different users unless transport customizers inject tokens per request.

### Option 2: Service-to-Service Machine-to-Machine (M2M) Token (Client Credentials Grant)
Configure `polaris-assistant` as a confidential OAuth2 client in Keycloak using the Client Credentials grant to acquire a service-level access token for MCP communication.
* **Pros:**
  * Independent of user sessions; stable for background health checks and tool discovery.
  * Token renewal is managed cleanly in background threads.
* **Drawbacks:**
  * Requires managing and rotating client secrets in configuration.
  * Erases end-user identity on Polaris Core; all tool calls appear as `polaris-assistant-service`, breaking user-level order ownership and audit trails unless bespoke user ID parameters are trusted.

### Option 3: Hybrid Token Forwarding — User Token Propagation with Static/Service Fallback (Selected)
Dynamically resolve the Bearer token by checking:
1. Active user `JwtAuthenticationToken` / `Jwt` in Spring Security's `SecurityContextHolder`.
2. Fallback to a configured service/auth token (`polaris.mcp.core.auth-token`) when in background/static contexts.
Inject the resolved token dynamically into all outgoing MCP HTTP requests (`GET /mcp/sse` and `POST /mcp/message`) via `McpSyncHttpClientRequestCustomizer`.
* **Pros:**
  * Preserves user identity for interactive chat turns.
  * Supports background tool discovery and isolated integration testing via fallback.
  * Dynamic per-request injection ensures that new tokens are applied immediately without restarting the client.
  * Self-healing: if an unauthorized error or connection reset occurs, the client automatically resets and re-negotiates on the next call.
* **Drawbacks:**
  * Requires thread-local context preservation across the controller-service boundary (naturally satisfied by Spring WebMVC request threads).

### Option 4: OAuth 2.0 Token Exchange (RFC 8693) / On-Behalf-Of (OBO)
Exchange the incoming user token at Keycloak for a scoped downstream token specifically targeted to the `polaris-core` audience.
* **Pros:**
  * Cryptographic audience restriction (`aud: polaris-core`).
* **Drawbacks:**
  * High operational complexity; requires Keycloak token exchange preview features and introduces additional network round-trips per turn. Over-engineering for current monorepo architecture.

---

## Decision Outcome

Chosen Option: **Option 3: Hybrid Token Forwarding (User Token Propagation with Static/Service Fallback)**.

### Architectural Specifications

#### 1. Dynamic Request Customization via `McpSyncHttpClientRequestCustomizer`
`HttpPolarisMcpClient` configures `HttpClientSseClientTransport` with an `httpRequestCustomizer`:
```java
HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUri)
        .sseEndpoint(ssePath)
        .jsonMapper(jsonMapper)
        .connectTimeout(Duration.ofSeconds(properties.getCore().getTimeoutSeconds()))
        .httpRequestCustomizer((requestBuilder, method, uri, body, transportContext) -> {
            String token = resolveBearerToken();
            if (token != null && !token.isBlank()) {
                requestBuilder.setHeader("Authorization", "Bearer " + token);
            }
        })
        .build();
```
Because `httpRequestCustomizer` is invoked for every outgoing HTTP request (both `GET /mcp/sse` stream setup and `POST /mcp/message` JSON-RPC calls), each request carries the token of the currently executing thread's security context.

#### 2. Token Resolution Precedence
The `resolveBearerToken()` method inspects the environment in strict priority order:
1. `SecurityContextHolder.getContext().getAuthentication()`:
   - If `JwtAuthenticationToken`: extracts `jwt.getTokenValue()`.
   - If principal is `Jwt`: extracts `jwt.getTokenValue()`.
   - If credentials is `AbstractOAuth2Token`: extracts `token.getTokenValue()`.
   - If credentials is a non-blank string: returns raw token.
2. `properties.getCore().getAuthToken()`: returns configured fallback token (if present).
3. If neither is available: returns `null` (permitting unauthenticated attempts for local dev/unsecured mocks).

#### 3. Self-Healing Connection Reset on Communication Failure
If `client.listTools()` or `client.callTool(...)` throws an exception (e.g. HTTP 401 Unauthorized due to expired token or connection dropped), `HttpPolarisMcpClient` closes and clears the cached `McpSyncClient` (`resetClient()`), ensuring that the next request establishes a fresh SSE session with the updated token.

---

## Consequences

### Positive
* **Seamless Keycloak Integration:** End-to-end security works without bespoke authentication hacks or disabling security filters.
* **Zero Trust User Auditability:** Polaris Core receives the authentic user JWT, enabling user-specific operations (e.g. order placement) with complete auditability.
* **Dev & Test Friendly:** Tests can inject mock tokens via `SecurityContextHolder` or configured properties without spin-up of external identity servers.
* **Resilient:** Stale connections and expired tokens are automatically discarded and reconnected.

### Negative / Risks
* If long asynchronous multi-turn workflows are moved to background executor pools, `SecurityContext` must be propagated using `DelegatingSecurityContextExecutor` or `SecurityContextHolder.setContext(...)`. Currently, `AssistantChatService` executes synchronously on the request thread.

---

## Verification Plan

1. **Unit Testing (`HttpPolarisMcpClientTest`):**
   - Verify `resolveBearerToken()` extracts token from `JwtAuthenticationToken`.
   - Verify fallback to `properties.getCore().getAuthToken()` when `SecurityContext` is empty.
   - Verify `Authorization: Bearer <token>` header injection on request builder.
   - Verify `resetClient()` on communication failure.
2. **Integration Verification:**
   - Verify all tests across `polaris-common`, `polaris`, and `polaris-assistant` pass with `mvn clean test`.
