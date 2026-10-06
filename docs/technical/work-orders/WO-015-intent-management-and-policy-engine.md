# Slice Work Order: WO-015
## Title: Intent Management, Tool Filtering, and Policy Engine Implementation

- **Target Modules:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0015](../decisions/0015-intent-management-and-policy-engine-architecture.md), [agent-sequence.md](../../../agent-sequence.md)
- **Product Reference:** [PRD-005](../../business/prds/PRD-005-agent-turn-observability-and-lifecycle-events.md)
- **Bounded Contexts:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Status:** COMPLETED

---

## 1. Objective & Scope

Implement proactive tool set narrowing, defensive tool registry checks, OAuth2 scope authorization, and intent observability in `apps/polaris-assistant`:
1. **Domain models & taxonomy configuration:** `IntentDefinition`, `IntentClassification`, `PolicyDecision`, and `IntentTaxonomyProperties`.
2. **Core components:** `IntentResolver` & `DefaultIntentResolver`, `IntentToolRegistry`, `PolicyEngine` & `DefaultPolicyEngine`.
3. **Observability extensions:** Trace context integration in `PolicyToolManager` for intent and policy validation (`AgentDecisionRecorder` decommissioned).
4. **Service integration:** Update `AssistantChatService` to run intent resolution once per turn, clarify low-confidence mutating intents, proactively offer filtered tools, defensively guard tool invocations, and enforce OAuth2 scopes while preserving 100% binary/source backwards compatibility.
5. **Configuration:** Wire built-in taxonomy in `application.yml`.
6. **Testing:** Deliver comprehensive unit test suites achieving 100% pass rate.

**Constraint Checklist:**
- [x] All changes strictly isolated within `apps/polaris-assistant` (ADR-0008, AGENTS.md Principle 2).
- [x] Zero changes to Polaris Core domain or MCP server schemas.
- [x] Maintain 100% binary and source backwards compatibility for `AssistantChatService` constructors.
- [x] Proactive tool narrowing: filter tools before providing them to `AssistantModelClient`.
- [x] Defensive tool validation: re-validate every proposed tool against the resolved intent before execution (`TOOL_MISMATCH`).
- [x] Low-confidence mutating action gate: clarify directly if confidence < threshold on mutating actions.
- [x] Policy engine authorization: check caller's OAuth2 scopes (`order.read`, `order.write`, `catalog.read`) before MCP execution (`POLICY_DENIED`).
- [x] Structured decision telemetry: record span attributes and JSON logs for all decision steps (ADR-0014).
- [x] Unit test coverage: 100% pass rate across `polaris-assistant` and monorepo reactor.

---

## 2. Detailed Technical Tasks

### Task 1: Domain Models & Properties
**Package:** `vn.danang.polaris.assistant.intent`
- `IntentClassification.java`: Immutable record `(String intentId, double confidence)`.
- `IntentDefinition.java`: Configuration model for taxonomy intents:
  - `id`: unique intent identifier (e.g. `catalog.product.search`).
  - `description`: human-readable intent description.
  - `examples`: list of user utterance examples.
  - `allowedTools`: list of MCP tool names allowed for this intent.
  - `requiredScope`: OAuth2 scope required (e.g. `catalog.read`, `order.write`).
  - `confidenceThreshold`: minimum confidence score to offer filtered tools (e.g. 0.80, 0.92).
  - `mutating`: boolean indicating whether the intent modifies state or financial assets.
- `PolicyDecision.java`: Immutable record `(boolean isAllowed, String reason)`.
- `IntentTaxonomyProperties.java`: Spring `@ConfigurationProperties(prefix = "polaris.assistant.intent")` loading the taxonomy with 8 built-in defaults:
  1. `general.conversation` (allowedTools: `[]`, threshold: 0.50)
  2. `catalog.product.search` (allowedTools: `[search_available_products, search_products]`, scope: `catalog.read`, threshold: 0.80)
  3. `catalog.product.lookup` (allowedTools: `[get_product_by_sku]`, scope: `catalog.read`, threshold: 0.85)
  4. `information.lookup.order.status` (allowedTools: `[get_order_status]`, scope: `order.read`, threshold: 0.85)
  5. `information.lookup.order.details` (allowedTools: `[get_order_details]`, scope: `order.read`, threshold: 0.85)
  6. `information.lookup.order.history` (allowedTools: `[list_customer_orders]`, scope: `order.read`, threshold: 0.80)
  7. `commerce.order.place` (allowedTools: `[place_order]`, scope: `order.write`, threshold: 0.92, mutating: true)
  8. `commerce.order.cancel` (allowedTools: `[cancel_order]`, scope: `order.write`, threshold: 0.92, mutating: true)

### Task 2: Core Components
**Package:** `vn.danang.polaris.assistant.intent`
- `IntentResolver.java` & `DefaultIntentResolver.java`:
  - Analyzes user utterance against taxonomy examples and regex keywords.
  - Computes confidence based on exact matches, keyword overlap, and token similarity.
  - Falls back to `general.conversation` or highest match when below threshold.
- `IntentToolRegistry.java`:
  - `allowedTools(String intentId, List<Tool> availableTools)`: filters available MCP tools.
  - `isValid(String intentId, String toolName)`: confirms if tool is allowed for intent.
  - `getRequiredScope(String toolName)`: returns the required OAuth2 scope for a tool.
  - `getIntent(String intentId)`: retrieves definition.
- `PolicyEngine.java` & `DefaultPolicyEngine.java`:
  - `authorize(String userId, String requiredScope)`: verifies caller authorities from Spring `SecurityContextHolder`.
  - Checks for `SCOPE_<scope>`, `<scope>`, or `ROLE_<scope>` in `Authentication.getAuthorities()`.
  - Supports JWT claims `scope` or `scp`.
  - Non-anonymous callers in non-secured test setups are permitted for test portability; anonymous callers are denied when a scope is required.

### Task 3: Observability Extensions (Decommissioned)
**File:** `vn.danang.polaris.assistant.observability.AgentDecisionRecorder` *(Note: Decommissioned and removed in favor of standard OTel spans and events)*
- Added `recordIntentResolution(IntentClassification classification, double threshold, boolean isMutating)`:
  - Tags `decision.action="intent.resolve"`, `decision.intent`, `decision.confidence`.
  - Tags `decision.outcome.status="RESOLVED"` or `"LOW_CONFIDENCE"`.
- Added `recordRegistryValidation(String toolName, String intentId, boolean isValid)`:
  - Tags `decision.action="registry.validate"`, `decision.outcome.status="VALID"` or `"TOOL_MISMATCH"`.
- Added `recordPolicyAuthorization(String toolName, String requiredScope, PolicyDecision decision)`:
  - Tags `decision.action="policy.authorize"`, `decision.policy=requiredScope`, `decision.outcome.status="ALLOW"` or `"DENY"`.

### Task 4: Service Integration
**File:** `vn.danang.polaris.assistant.service.AssistantChatService`
- Injected `IntentResolver`, `IntentToolRegistry`, and `PolicyEngine` with safe fallback instantiations.
- Preserved all existing 4 overloaded constructors to guarantee 100% binary/source backwards compatibility.
- Operational workflow in `sendMessage`:
  1. Intent resolved once per turn.
  2. If confidence < threshold and mutating: immediately return clarification reply to user; do not guess or offer mutating tools.
  3. If confidence < threshold and read-only: offer full `availableTools` tagged `LOW_CONFIDENCE`.
  4. If confidence >= threshold: offer filtered subset via `registry.allowedTools(...)`.
  5. In ReAct loop:
     - On proposed tool call: defensively check `registry.isValid(intentId, toolName)`.
     - If invalid: record `TOOL_MISMATCH`, append corrective message to history, repeat loop.
     - Defensively check `policyEngine.authorize(userId, requiredScope)`.
     - If denied: record `POLICY_DENIED`, break loop, return `"Action denied: " + decision.reason()`.
     - If allowed: execute MCP tool via `recordToolExecution(...)`.

### Task 5: Application Configuration
**File:** `apps/polaris-assistant/src/main/resources/application.yml`
- Added full `polaris.assistant.intent.intents` taxonomy with 8 default intents, descriptions, examples, allowed tools, scopes, thresholds, and mutating flags.

### Task 6: Unit Test Suite
- `IntentToolRegistryTest.java`: verifies tool filtering, validation checks, and scope mappings.
- `DefaultIntentResolverTest.java`: tests greetings, exact matches, keywords, order status/cancel, and low-confidence fallbacks.
- `DefaultPolicyEngineTest.java`: tests unconstrained tools, authenticated users with required scopes, missing scopes, and non-secured fallback.
- `AssistantChatServiceTest.java`: added 5 tests for:
  - `toolNarrowing_proactivelyOffersFilteredToolsForSearchIntent`
  - `mutatingIntent_lowConfidence_promptsClarificationWithoutExecuting`
  - `defensiveRegistry_mismatchTool_recordsWarningAndRecovers`
  - `policyEngine_deniedScope_shortCircuitsExecution`
  - `generalConversation_offersEmptyToolSetAndAnswersDirectly`

---

## 3. Verification & Acceptance Criteria

### Technical Verification Summary
- **Module Verification:**
  ```bash
  mvn clean test -pl apps/polaris-assistant
  ```
  - **Result:** Tests run: 114, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS.
- **Monorepo Reactor Verification:**
  ```bash
  mvn clean test
  ```
  - `polaris-parent`: SUCCESS
  - `polaris-common`: Tests run: 8, Failures: 0, Errors: 0 (SUCCESS)
  - `polaris` (Core): Tests run: 12, Failures: 0, Errors: 0 (SUCCESS)
  - `polaris-assistant`: Tests run: 114, Failures: 0, Errors: 0 (SUCCESS)
  - **Result:** 4/4 modules passed, BUILD SUCCESS.
