# Initial
We are going to build a front-end app for polaris-assistant. 
The app should support following features: 
- User can chat to ask about a product, place an order, see more detail in the @intents.json
- The product information, order draft to be confirming should display in product card or order card like a popup when user click on the product/order link for more detail. 

# Initial Design
We choose to follow a guide here https://martinfowler.com/articles/micro-frontends.html
Tech-stack for assistant app is: vuejs
the app composer hostname: app.polaris.local

# Plan
We will implement in multiple slices with trunk-base approaches: 
- Slice-1: initial micro-frontend infrastructure, just a hello world app for assistant
- Slice-2: sharing auth component
- Slice-3: simple chat turn with send request/response
- Other slices: TBD

---

# Slice-1: Micro-frontend infrastructure (Hello World)

Status: **Proposed**. Decisions D1-D5 are recommendations awaiting confirmation.

## Goal
`https://app.polaris.local/` serves a **container** (shell) that loads the **assistant** micro-frontend (Vue) at runtime and renders "Hello from Assistant".

## Non-goals
- No authentication or shared auth component (Slice-2).
- No calls to the assistant backend, no chat UI (Slice-3).
- No product/order cards, no routing between multiple micro-frontends.
- No production (k8s) rollout; Slice-1 targets docker-compose + nginx edge only.

## Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | **Integration: runtime via Web Components** (W3C Custom Elements + ES modules). See [ADR-0023](../../technical/decisions/0023-micro-frontend-composition-method.md). | Browser standard instead of a bundler-specific protocol. Full options and consequences are in the ADR. |
| D2 | **Container is framework-free**: static `index.html` + a small ES module loader. | The shell only composes; no framework to couple to or upgrade. |
| D3 | **Assistant built with Vue 3 + Vite**, exported via `defineCustomElement` as `<polaris-assistant>`. | Matches the chosen stack; Vite library mode emits a single ES module. |
| D4 | **Two deployable units in this repo**: `apps/container-web` and `apps/assistant-web`, each with its own build, Dockerfile and tests. | Separate bounded contexts and independent deployability (AC-3), without multi-repo overhead. Each is its own change/PR. |
| D5 | **Edge**: add `app.polaris.local` vhost to `docker/nginx/nginx.conf`: `/` -> container, `/mfe/assistant/` -> assistant static assets. Same TLS cert/CA flow as `polaris.local` (must add the SAN), plus compose network alias. | Reuses the existing edge, traceparent injection and JSON access logs. |

## Integration contract (container <-> assistant)

**Artifact**: ES module at `/mfe/assistant/assistant.js` (published URL configured in the container via `window.__POLARIS_CONFIG__.remotes.assistant`, injected at deploy time, not hardcoded; 12-factor).

**Custom element**: `<polaris-assistant>`
- Registered by the module on import (idempotent: re-import must not throw on duplicate `customElements.define`).
- Attributes (Slice-1): none required. Reserved for later: `api-base-url`, `locale`.
- Lifecycle: mount = element attached to DOM (`connectedCallback`); unmount = element removed (`disconnectedCallback`) and must release listeners/timers.
- Events (outbound, `CustomEvent`, bubbles + composed): `polaris:assistant:ready` fired once after first render. Event names are namespaced `polaris:<mfe>:<event>`.
- Styling: Shadow DOM; no global CSS leakage in either direction.
- Container -> remote communication is via attributes/properties; remote -> container via DOM events only (no shared globals).

**Container loader behaviour**
1. Reads the remote URL from config.
2. `import(url)` with a timeout (default 5 s).
3. On success: appends `<polaris-assistant>` into `#assistant-slot`.
4. On failure or timeout: renders a fallback message in the slot and logs one structured error (`remote=assistant`, `reason`, `url`); the shell remains functional.

## Acceptance criteria

| ID | Criterion | Verified by |
|----|-----------|-------------|
| AC-1 | `GET https://app.polaris.local/` returns `200 text/html` containing the container shell with `#assistant-slot`. | e2e (HTTP) |
| AC-2 | After load, the page contains `<polaris-assistant>` whose shadow DOM text includes "Hello from Assistant". | e2e (Playwright) |
| AC-3 | `assistant-web` builds, tests and ships its image without building `container-web`, and vice versa. Redeploying only the assistant changes what the page renders with no container rebuild. | CI job per app; compose check |
| AC-4 | If the assistant asset returns 404/5xx or exceeds the timeout, the shell still renders, the slot shows a fallback message, and no uncaught exception reaches `window.onerror`. | container unit test + e2e with the asset blocked |
| AC-5 | The module can be imported twice without error; removing and re-adding the element works (mount/unmount/mount). | assistant unit test |
| AC-6 | `polaris:assistant:ready` is dispatched exactly once per mounted element and reaches a listener on the container. | assistant unit test + e2e |
| AC-7 | Remote URL comes from runtime config; changing it requires no rebuild. | container unit test |
| AC-8 | The edge serves `app.polaris.local` over TLS with the existing CA; requests carry/propagate `traceparent` and appear in the nginx JSON access log. | e2e + log assertion |
| AC-9 | Assistant assets are served with cache headers: hashed files `immutable`, entry module `no-cache` (revalidate). | e2e (HTTP headers) |

## Repo layout

```
apps/
  container-web/          # shell: index.html, loader.js, config template, Dockerfile, tests
  assistant-web/          # Vue 3 + Vite, <polaris-assistant>, Dockerfile, tests
docker/nginx/nginx.conf   # + app.polaris.local vhost
docker-compose.yml        # + container-web, assistant-web services; app.polaris.local alias
tests/                    # + frontend e2e (Playwright) against the compose stack
```

## Change breakdown (each is a vertically complete, independently mergeable PR)
1. **assistant-web**: custom element + build + image + unit tests (AC-5, AC-6).
2. **container-web**: shell + loader + fallback + image + unit tests (AC-4, AC-7).
3. **edge + compose + e2e**: vhost, cert SAN, wiring, Playwright tests (AC-1, 2, 3, 8, 9).

## Observability & quality notes
- Browser errors from the loader are logged as single-line JSON (`level`, `remote`, `reason`, `url`) so they are correlatable once a telemetry sink exists; browser-side OTel is deferred.
- Tests per AGENTS.md Principle 3: unit tests mock only the network boundary (`import()`); e2e runs against the real nginx edge.

## Open questions
- Is a trusted dev certificate for `app.polaris.local` regenerated from the existing `docker/nginx/certs` tooling, or a new cert?
- Playwright in CI: run in the existing `maven.yml` workflow or a new workflow?
- Should `apps/*-web` be wired into the Maven reactor (`pom.xml`) or built independently via npm? (Recommended: independent npm + a Makefile target.)

