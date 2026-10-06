# ADR-0006: Dedicated HTTPS Domain Routing, TLS Termination, and OIDC SSO for Grafana (grafana.polaris.local)

* Status: Accepted
* Deciders: Polaris Fleet Architect, Platform Engineering
* Date: 2026-09-10
* Technical Story: Establishing dedicated, trusted HTTPS edge routing for Grafana under the canonical domain `https://grafana.polaris.local`, standardizing reverse proxy TLS termination, HTTP-to-HTTPS protocol redirection, WebSocket proxying, and OIDC SSO integration with Keycloak.

---

## Context and Problem Statement

Polaris standardizes its local infrastructure under the canonical domain namespace `*.polaris.local`:
- Application & APIs: `https://polaris.local`
- Identity & Access Management (Keycloak): `https://id.polaris.local`

Historically, Grafana was accessed over an insecure, unencrypted raw host port: `http://localhost:3000`. This introduced several architectural problems:
1. **Security Context & Cookie Separation ([AGENTS.md: Principle 1.2](../../../AGENTS.md)):** Redirecting between a secure HTTPS identity provider (`https://id.polaris.local`) and an unencrypted `http://localhost:3000` endpoint causes browser origin warnings, mixed-content issues, and insecure cookie transmission.
2. **Dev-Prod Parity & Protocol Alignment ([AGENTS.md: Principle 1.1 & 1.4](../../../AGENTS.md)):** Production observability platforms operate behind hardened TLS reverse proxies on standard port 443 with dedicated hostnames, rather than exposing raw application container ports.
3. **Inconsistent Developer Experience:** Developers had to remember arbitrary numeric ports (`3000`) rather than standard domain naming conventions (`grafana.polaris.local`).

How should Polaris architect Grafana access to provide unified HTTPS ingress, seamless OIDC SSO via Keycloak, and support for real-time Grafana Live streaming without disrupting existing automated tooling?

---

## Decision Drivers

* **Protocol Conformance & Transport Security ([AGENTS.md: Principle 1.1 & 1.2](../../../AGENTS.md)):** Enforce HTTPS (`:443`) as the default entrypoint for all web interfaces; provide automatic HTTP-to-HTTPS (`301 Moved Permanently`) redirection.
* **Unified Domain Architecture:** Align Grafana with the existing local domain hierarchy (`grafana.polaris.local`).
* **Standard OIDC Flow ([AGENTS.md: Principle 1.2](../../../AGENTS.md)):** Ensure Grafana's OAuth2 Generic driver generates public authorization redirect URIs matching `https://grafana.polaris.local/login/generic_oauth`, validated strictly by Keycloak.
* **WebSocket & Streaming Support:** Support Grafana Live (real-time telemetry and dashboard streaming) by proxying `Upgrade` and `Connection` headers across the reverse proxy.
* **Backward Compatibility & Tooling Continuity:** Retain internal container networking (`http://grafana:3000`) for `gcx-cli` and host port `3000` mapping for emergency debugging.

---

## Considered Options

### Option 1: Native TLS Termination within the Grafana Container
Configure Grafana (`grafana.ini`) with TLS certificates and bind it directly to host port 443.
* *Drawbacks:* Requires managing separate certificates inside the Grafana container; conflicts with Nginx already bound to host ports 80/443; violates the single edge gateway pattern.

### Option 2: Nginx Reverse Proxy with Shared mkcert Certificate & Keycloak OIDC (Selected)
Leverage the existing `nginx` container as the unified TLS edge router. Route `https://grafana.polaris.local` to internal `http://grafana:3000`, extend local CA certificates to include `grafana.polaris.local`, and register the canonical redirect URI in Keycloak IAM.
* *Advantages:* Centralizes TLS termination and certificate management in Nginx; enforces strict HTTP-to-HTTPS redirection; preserves isolated container networking; fully aligns with AGENTS.md principles.

---

## Decision Outcome

Chosen Option: **Option 2: Nginx Reverse Proxy with Shared mkcert Certificate & Keycloak OIDC**.

### Architectural Specifications

#### 1. Ingress & TLS Termination (Nginx)
In `infra/nginx/nginx.conf`:
- **Port 80 Redirect:** Update HTTP server block to redirect requests for `grafana.polaris.local` to HTTPS:
  ```nginx
  server {
    listen 80;
    server_name polaris.local id.polaris.local grafana.polaris.local;
    return 301 https://$host$request_uri;
  }
  ```
- **Port 443 SSL Server Block:** Terminate TLS using `polaris.local+1.pem` and reverse-proxy to `http://grafana:3000` with WebSocket upgrade headers:
  ```nginx
  server {
    listen 443 ssl;
    server_name grafana.polaris.local;

    ssl_certificate     /etc/nginx/certs/polaris.local+1.pem;
    ssl_certificate_key /etc/nginx/certs/polaris.local+1-key.pem;

    location / {
      proxy_pass http://grafana:3000;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host $host;
      proxy_set_header X-Forwarded-Port 443;

      # WebSocket support for Grafana Live
      proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";
    }
  }
  ```

#### 2. Service Orchestration & DNS Aliasing (Docker Compose)
- In `docker-compose.yml`, register `grafana.polaris.local` as an alias on `polaris-net` for the `nginx` service so intra-network requests resolve correctly.
- In `docker-compose.override.yml`:
  - Set `GF_SERVER_ROOT_URL=https://grafana.polaris.local/`.
  - Declare `nginx` service dependency on `grafana` (`depends_on: [grafana]`).
  - Maintain `GRAFANA_SERVER=http://grafana:3000` for `gcx-cli`.

#### 3. IAM & OIDC Client Registration (Keycloak)
In `infra/keycloak/realm-export.json`:
- Register `https://grafana.polaris.local/login/generic_oauth` in `redirectUris`.
- Register `https://grafana.polaris.local` in `webOrigins`.
- Configure `post.logout.redirect.uris` attribute to `+` to permit standard post-logout redirection.

#### 4. Certificate & Host Resolution (Local Automation)
- Update `scripts/setup-local-https-mac-m1.sh`:
  - Expand `DOMAINS` array: `("polaris.local" "id.polaris.local" "grafana.polaris.local")`.
  - Issue certificate covering `polaris.local`, `*.polaris.local`, `id.polaris.local`, and `grafana.polaris.local`.
  - Ensure `/etc/hosts` automatically maps `127.0.0.1 grafana.polaris.local`.

---

## Consequences

### Positive
* **Unified HTTPS Experience:** All developer portals (`polaris.local`, `id.polaris.local`, `grafana.polaris.local`) operate uniformly over trusted TLS on port 443.
* **Strict OIDC Security:** Eliminates cross-origin mixed HTTP/HTTPS issues during Keycloak authentication.
* **Seamless Real-Time Dashboards:** Native WebSocket proxying supports Grafana Live streaming.
* **Non-Breaking:** `gcx-cli` and direct host port `3000` access remain functional.

### Negative / Operational Trade-offs
* Developers must ensure `/etc/hosts` contains the `grafana.polaris.local` entry (handled automatically by `scripts/setup-local-https-mac-m1.sh`).
