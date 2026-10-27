# Assistant Frontend (Vue micro-frontend)

- **Scope:** a browser app for `polaris-assistant`: Vue micro-frontend architecture, Keycloak sign-in, chat, and product/order cards shown as popups.
- **Out of scope:** backend changes (separate plan if the chat API needs new widgets), payments, mobile apps, store-staff features.
- **Depends on:** PRD-003 (FR-1..FR-13), existing `POST /api/v1/assistant/chat` and draft endpoints · **Unblocks:** further micro-frontends (staff console, order history, ...)
- **Status:** Draft for review
- **Seed:** the initial intent note in [`assistant-frontend.spec.md`](../specs/assistant-frontend.spec.md). Its two feature bullets are carried by TR-4..TR-6 below.

## Goal

A shopper opens the web app, signs in with Keycloak, chats with the assistant about products and orders, and opens a popup card for a product or a staged order draft. New features can be added later as independent micro-frontends without changing the container.

## Technical Requirements

| ID | Requirement |
|:--|:--|
| TR-1 | A **container** (composer) app loads **micro-frontends** at runtime through one small render/unmount contract (SC-1), following [Fowler's micro-frontends guide](https://martinfowler.com/articles/micro-frontends.html). Each micro-frontend is built and deployed independently and also runs standalone. Adding one needs no change to the container or the others. |
| TR-2 | The frontend builds into a container image served by the existing edge, configured at runtime from the environment (no rebuild per environment, no secrets in the bundle). |
| TR-3 | The user signs in with Keycloak using Authorization Code + PKCE (S256), and every API call carries `Authorization: Bearer`. An unauthenticated visitor is redirected to login (PRD-003 FR-9, Scenarios 10 and 14). |
| TR-4 | The user can chat with the assistant; replies render in a conversation view and `session_id` survives a page refresh. |
| TR-5 | The product information appears as a card in a popup when the user opens a product link or card. |
| TR-6 | The order draft awaiting confirmation appears as a card in a popup with confirm and cancel actions. |
| TR-7 | API errors (RFC 7807) render as Problem Cards (PRD-003 FR-7). |
| TR-8 | The app propagates W3C `traceparent` on API calls and is covered by automated tests at the production-equivalent seams. |

## Shared Contracts

| ID | Contract | Decision | Owning slice |
|:--|:--|:--|:--|
| SC-1 | Micro-frontend contract | Runtime JS integration. A micro-frontend script defines `window.render<Name>(containerId, context)` and `window.unmount<Name>(containerId)`. `context` is `{ config, navigate, apiClient }` (+ `auth` from S2). It is registered in a `micro-frontends.json` manifest the container reads at runtime. Communication between micro-frontends is by URL/route only. | S1 |
| SC-2 | Runtime config | `/config.json` served next to the container, holding API base URL, Keycloak issuer and client id. | S1 |
| SC-3 | Auth context | The container owns the OIDC session and passes `auth.getAccessToken()` in `context`. Micro-frontends never talk to Keycloak. | S2 |
| SC-4 | Chat API | `ChatMessageResponse { sessionId, role, reply, createdAt, widgets[] }`, `ChatWidget { type, payload }` as defined by `polaris-assistant`. The frontend only consumes it. | backend (existing) |

## Decisions

| ID | Decision | Status | Resolved in |
|:--|:--|:--|:--|
| D1 | Micro-frontend mechanism | **proposed:** runtime JS integration with `<script>` loading and the SC-1 render/unmount contract (Fowler); Vue 3 + TypeScript + Vite per app. Build-time (npm package) integration is rejected. Alternatives: Module Federation, single-spa, web components. | S1 |
| D2 | Repo location | **proposed:** `apps/polaris-assistant-container` (composer) and `apps/polaris-assistant-mfe` (the assistant micro-frontend), each with its own build and image. Alternative: one npm workspace under `apps/web/`. | S1 |
| D5 | CSS isolation | **proposed:** scoped styles with a `pa-` prefix per micro-frontend. Alternative: Shadow DOM. | S1 |
| D6 | Shared dependencies | **proposed:** each micro-frontend bundles its own Vue (accept duplication, per Fowler). Revisit only if payload size becomes a problem. | S1 |
| D3 | Keycloak client | **proposed:** new public client `polaris-assistant-web` in realm `polaris`, PKCE S256, issuer `https://id.polaris.local/realms/polaris`. | S2 |
| D4 | How product/order links reach the UI | open: the reply text has no links today, and only `ORDER_DRAFT` / confirmed cards exist as widgets. Needs a decision, and maybe a backend slice. | S4 |

## Slices

### Slice Tracker

| # | Slice | Title | Spec | Status | External | Branch | PR | Notes |
|:--|:--|:--|:--|:--|:--|:--|:--|:--|
| 1 | S1 | Micro-frontend container and hello assistant | [spec](../specs/assistant-frontend/S1-microfrontend-foundation.md) | `todo` | — | | | spec: draft |
| 2 | S2 | Keycloak authentication (OIDC PKCE) | `not written` | `todo` | Keycloak client (D3) | | | |
| 3 | S3 | Chat in the assistant micro-frontend | `not written` | `todo` | — | | | needs S2 (API requires a token) |
| 4 | S4 | Product and order cards (popup) | `not written` | `todo` | D4 | | | |

### S1: Micro-frontend foundation
**Covers:** TR-1, TR-2, TR-8 (build, serve, trace). **Spec:** [S1](../specs/assistant-frontend/S1-microfrontend-foundation.md). **Depends on:** —
- The container app (header, navigation, routing, loader for micro-frontends) and the first micro-frontend, `assistant`, that only shows "Hello, this is Polaris Assistant".
- Runtime `micro-frontends.json` and `config.json`; separate images and CI jobs; an edge route. No auth and no backend calls yet.

### S2: Keycloak authentication
**Covers:** TR-3. **Spec:** not yet written. **Depends on:** S1
- Container performs the OIDC code + PKCE flow, silent refresh, logout, and redirects unauthenticated visitors (SC-3).
- Keycloak client provisioned in the realm export.

### S3: Chat in the assistant micro-frontend
**Covers:** TR-4, TR-7. **Spec:** not yet written. **Depends on:** S1, S2
- The assistant micro-frontend gets a chat view that calls `POST /api/v1/assistant/chat`, keeps `session_id` in `sessionStorage`, renders Problem Cards.

### S4: Product and order cards
**Covers:** TR-5, TR-6. **Spec:** not yet written. **Depends on:** S3, D4
- Popup for product details and for the order draft with confirm/cancel through the draft endpoints.

## Risks and Open Questions

| ID | Risk / question | Affects | Mitigation / owner |
|:--|:--|:--|:--|
| R1 | Backend has no widget for product cards or links in replies (D4). | S4 | Decide early; a backend slice may be needed first. |
| R2 | Duplicated dependencies increase payload (each micro-frontend bundles Vue). | S1 | Accepted for now (D6); measure and set a size budget. |
| R5 | Global `window.render*` names can collide. | S1 | Name comes from the manifest; the container fails fast on duplicates. |
| R3 | CORS or same-origin routing between the web app and the assistant API. | S3 | Serve both behind the edge on one origin. |

## Definition of Done

- [ ] Every slice is `done` and every TR maps to at least one passing test.
- [ ] A browser e2e path: sign in, chat, open a card popup.
- [ ] Open Decisions are resolved or explicitly deferred.

## Change Log

| Date | Change | Reason | Slices affected |
|:--|:--|:--|:--|
| 2026-10-27 | Initial plan: S1 technical foundation, S2 Keycloak | user decision | S1, S2 |
| 2026-10-27 | Aligned with Fowler's micro-frontends guide: container first, runtime JS contract, a hello-world assistant micro-frontend in S1 | user request | S1, D1, SC-1 |
