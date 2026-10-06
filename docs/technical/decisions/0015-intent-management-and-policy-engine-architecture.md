# ADR-0015: Intent Management, Tool Filtering, and Policy Engine Architecture

* **Status:** Accepted
* **Deciders:** Polaris Architecture Team, Core Platform Engineering, AI Agent Platform Lead
* **Date:** 2026-09-17
* **Technical Story:** Introducing proactive tool surface narrowing and defensive tool validation via an Intent Taxonomy and Registry (`IntentResolver`, `IntentToolRegistry`), alongside scope-based policy enforcement (`PolicyEngine`), integrated into `AssistantChatService` and correlated with structured decision events (ADR-0014).
* **Product Reference:** [PRD-005](../../business/prds/PRD-005-agent-turn-observability-and-lifecycle-events.md), [agent-sequence.md](../../../agent-sequence.md)
* **Work Order Reference:** [WO-015](../work-orders/WO-015-intent-management-and-policy-engine.md)

---

## 1. Context and Problem Statement

Polaris Assistant (`apps/polaris-assistant`) executes an autonomous ReAct loop querying Gemini and invoking tools exposed by Polaris Core over MCP (`PolicyToolManager`).

Prior architecture provided all 7 MCP tools to the model on every single turn regardless of user intent. This created several failure modes:
1. **Tool Hallucination & Indiscriminate Execution:** The model was exposed to mutating tools (`place_order`, `cancel_order`) even when a user only requested catalog browsing or order tracking.
2. **Missing Pre-Execution Scoping & Authorization:** Polaris Core enforced JWT token verification, but the AI assistant lacked defense-in-depth policy checks prior to tool invocation, allowing unauthorized or accidental tool triggers to proceed to the MCP transport.
3. **No Clarification Barrier for Mutating Actions:** Ambiguous queries on high-impact financial or order-altering actions could be guessed by the model with low confidence.
4. **Lack of Intent Observability:** Telemetry captured raw prompts in `decision.intent` rather than categorized taxonomy intent IDs and classifier confidence scores.

How should Polaris Assistant architect intent resolution, dynamic tool set filtering, defensive tool validation, and policy authorization while upholding ADR-0008 (assistant microservice isolation), ADR-0014 (decision observability), and backwards compatibility?

---

## 2. Decision Drivers

* **Simplicity via Standardization ([AGENTS.md Principle 1](../../../AGENTS.md)):** Align with standard OAuth 2.0 / OIDC scopes (`catalog.read`, `order.read`, `order.write`) for authorization.
* **Bounded Context Containment ([AGENTS.md Principle 2](../../../AGENTS.md)):** Keep all intent resolution, registry filtering, and policy enforcement inside `apps/polaris-assistant` without modifying Polaris Core or MCP schemas.
* **Proactive & Defensive Tool Safety:** Narrow the tool set before prompting the model (proactive) and validate proposed tool calls against resolved intent before execution (defensive).
* **High-Confidence Gate for Mutating Actions:** Mutating actions (`commerce.order.place`, `commerce.order.cancel`) require high confidence (>= 0.92); below threshold, trigger an explicit clarification question instead of guessing.
* **Full Decision Observability ([ADR-0014](0014-agent-decision-events-and-observability-schema.md)):** Emit structured decision events and span tags for `intent.resolve`, `registry.validate`, and `policy.authorize`.
* **Interface Stability & Backwards Compatibility:** Preserve all existing constructors in `AssistantChatService` and maintain zero breaking changes.

---

## 3. Considered Options

### Option 1: Monolithic Hardcoded Logic inside `AssistantChatService`
Implement if-else statements directly inside `AssistantChatService.executeTurn()` checking string tool names and permissions.
* **Cons:** Violates Anti-God Class / Single Responsibility ([AGENTS.md Principle 3.3](../../../AGENTS.md)); difficult to test, configure, or extend.

### Option 2: Core Server-Side Enforcement Only (MCP Filter)
Push intent filtering to Polaris Core MCP server.
* **Cons:** Violates ADR-0008 (assistant isolation); tightly couples assistant conversational logic with core commerce backends.

### Option 3: Dedicated Intent, Registry, and Policy Engine Subsystem (Selected)
Introduce modular components in package `vn.danang.polaris.assistant.intent`:
- `IntentDefinition` & `IntentTaxonomyProperties`: Configurable YAML/properties taxonomy mapping intent IDs to tools, scopes, and thresholds.
- `IntentResolver`: Classifies user utterances into taxonomy intents with confidence scores.
- `IntentToolRegistry`: Filters available MCP tools and validates proposed invocations.
- `PolicyEngine`: Authorizes actions against the caller's OAuth 2.0 token scopes.
- Integration in `AssistantChatService` and `PolicyToolManager`.

---

## 4. Decision Outcome & Detailed Technical Specification

**Chosen Option:** Option 3.

### 4.1. Intent Taxonomy Configuration
Taxonomy contains 8 intents:
1. `general.conversation`: greetings, small talk (`allowedTools: []`, `threshold: 0.50`)
2. `catalog.product.search`: browse/search products (`allowedTools: [search_available_products]`, scope: `catalog.read`, `threshold: 0.80`)
3. `catalog.product.lookup`: specific SKU lookup (`allowedTools: [get_product_by_sku]`, scope: `catalog.read`, `threshold: 0.85`)
4. `information.lookup.order.status`: quick status check (`allowedTools: [get_order_status]`, scope: `order.read`, `threshold: 0.85`)
5. `information.lookup.order.details`: full order details (`allowedTools: [get_order_details]`, scope: `order.read`, `threshold: 0.85`)
6. `information.lookup.order.history`: past orders list (`allowedTools: [list_customer_orders]`, scope: `order.read`, `threshold: 0.80`)
7. `commerce.order.place`: place order (`allowedTools: [place_order]`, scope: `order.write`, `threshold: 0.92`, `mutating: true`)
8. `commerce.order.cancel`: cancel order (`allowedTools: [cancel_order]`, scope: `order.write`, `threshold: 0.92`, `mutating: true`)

### 4.2. Operational Flow in `AssistantChatService`
1. **Resolve Intent Once Per Turn:** Calls `intentResolver.resolve(message, history)`. Emits `intent.resolve` decision event.
2. **Threshold & Risk Evaluation:**
   - If confidence < threshold and intent is mutating: prompt user with clarification question, do NOT offer mutating tools.
   - If confidence < threshold and intent is read-only: fall back to full `availableTools` tagged `LOW_CONFIDENCE`.
   - If confidence >= threshold: offer `intentToolRegistry.allowedTools(intentId, availableTools)`.
3. **ReAct Loop Execution:**
   - Proactive: Model generates response given filtered tools.
   - Defensive Registry Check: If proposed tool not permitted for intent, record `TOOL_MISMATCH` decision event, append corrective message to history, and repeat loop.
   - Defensive Policy Check: Verify `policyEngine.authorize(userId, requiredScope)`. If denied, record `POLICY_DENIED` decision event, break loop, and return denial reason.
   - Tool Execution: Execute tool, record `COMPLETED` decision event, append result, and continue loop.

---

## 5. Boundary & Interface Stability Analysis

| Criterion | Level | Justification |
|---|---|---|
| **B1: Boundary Enumeration** | Polaris Assistant Context | Strictly enclosed in `apps/polaris-assistant`. No change to Core APIs. |
| **B2: Stable vs Internal** | Explicitly Delineated | Stable Contract: `decision.*` tags, YAML taxonomy properties schema. Internal: `IntentResolver`, `IntentToolRegistry`, `PolicyEngine`. |
| **B3: Traceability** | Established | Tracked via ADR-0015 and implemented in WO-015. |
| **B4: Migration & Compatibility** | 100% Additive | Overloaded constructors preserved. Unconfigured taxonomy falls back to safe defaults. |
| **B5: Gate Enforcement** | TV Gate Defined | Monorepo reactor test pass (`mvn clean test`). |
| **B6: Downstream Impact** | Zero Disruption | Fully backwards compatible for external callers. |
| **B7: Reversibility** | Immediate / Low Risk | Purely internal service refinement with fallback safe defaults. |
