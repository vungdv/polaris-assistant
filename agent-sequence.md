# Polaris Assistant Communication Sequence

This document illustrates the main communication flow between [`AssistantChatService`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java), [`IntentResolver`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/intent/IntentResolver.java), [`IntentToolRegistry`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/intent/IntentToolRegistry.java), [`PolicyEngine`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/intent/PolicyEngine.java), [`AssistantModelClient`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/ai/AssistantModelClient.java), and [`PolicyToolManager`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/ExternalMcpHub.java).

Intent resolution runs **once per user turn**, before the ReAct loop starts, and narrows the tool set the model is offered. When the model proposes tool calls, `AssistantChatService` delegates the tool execution loop to [`ExternalMcpHub.handleToolCalls(...)`](apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/tools/ExternalMcpHub.java). Within `PolicyToolManager`, registry validation and policy authorization run **per proposed tool call** as a guard directly before tool dispatching, so the model is checked both proactively (smaller tool surface) and defensively (every actual call re-validated against the resolved intent).

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant ChatService as AssistantChatService
    participant IntentSvc as IntentResolver
    participant Registry as IntentToolRegistry
    participant ModelClient as AssistantModelClient
    participant McpHub as ExternalMcpHub
    participant Policy as PolicyEngine

    User->>ChatService: sendMessage(request, userId)
    activate ChatService

    %% 1. Discover available tools
    ChatService->>McpHub: discoverAllTools()
    activate McpHub
    McpHub-->>ChatService: List<Tool> availableTools
    deactivate McpHub

    %% 2. Resolve intent once per turn
    ChatService->>IntentSvc: resolve(userMessage, context)
    activate IntentSvc
    IntentSvc-->>ChatService: IntentClassification(intentId, confidence)
    deactivate IntentSvc
    Note over ChatService: Resolve intent:<br/>intentId, confidence

    ChatService->>Registry: allowedTools(intentId)
    activate Registry
    Registry-->>ChatService: filteredTools (subset of availableTools)
    deactivate Registry
    Note over ChatService: if confidence < intent threshold,<br/>fall back to full availableTools<br/>or ask a clarifying question

    %% 3. ReAct execution loop
    loop ReAct Loop (max 5 iterations)
        ChatService->>ModelClient: generateResponse(history, filteredTools)
        activate ModelClient
        ModelClient-->>ChatService: ModelResponse (ToolCalls or Text)
        deactivate ModelClient

        alt Model proposes Tool Call(s)
            ChatService->>+McpHub: handleToolCalls(toolCalls, context)
            loop For each ToolCall
                McpHub->>Registry: isValid(intentId, toolName)
                activate Registry
                Registry-->>McpHub: VALID / INVALID
                deactivate Registry

                alt tool not valid for intent
                    Note over McpHub: Tool not permitted for intent<br/>record corrective turn
                else tool valid
                    McpHub->>Policy: authorize(userId, requiredScope(toolName))
                    activate Policy
                    Policy-->>McpHub: ALLOW / DENY
                    deactivate Policy

                    alt policy denies
                        Note over McpHub: Policy denied<br/>mark policyDenied in result
                    else policy allows
                        McpHub->>McpHub: executeTool(toolName, arguments)
                        Note over McpHub: Record span event: agent.tool.call / result<br/>record tool result turn
                    end
                end
            end
            McpHub-->>-ChatService: ToolExecutionResult (turns, policyDenied)
            ChatService->>ChatService: Append turns to history
            alt policyDenied
                Note over ChatService: return denial reason to user<br/>and break loop
            end
        else Model returns final answer
            Note over ChatService: Set final reply<br/>and break loop
        end
    end

    ChatService-->>User: ChatMessageResponse(reply)
    deactivate ChatService
```

## Key Flow Steps

1. **Tool Discovery**: `AssistantChatService` calls `ExternalMcpHub.discoverAllTools()` to discover all registered tools from the MCP server.
2. **Intent Resolution (once per turn)**: `AssistantChatService` calls `IntentResolver.resolve(...)` with the user's message and conversation context, returning an `IntentClassification` (`intentId`, `confidence`).
3. **Tool Set Filtering & Threshold Check**:
   - `AssistantChatService` looks up the resolved intent in `IntentToolRegistry`.
   - If confidence is below the intent's configured threshold:
     - For mutating intents (`commerce.order.place`, `commerce.order.cancel`), the assistant immediately returns a clarifying question to the user without offering mutating tools or guessing.
     - For read-only intents, the assistant falls back to the full tool set (`availableTools`).
   - If confidence meets or exceeds threshold:
     - `AssistantChatService` calls `IntentToolRegistry.allowedTools(intentId, availableTools)` to offer only permissible tools to the model. For `general.conversation`, the filtered list is empty, allowing the model to answer directly.
4. **ReAct Loop Execution**:
   - `AssistantChatService` invokes `AssistantModelClient.generateResponse(...)` with the message history and the filtered tool set.
   - If the model returns a tool invocation, `AssistantChatService` delegates the batch to `ExternalMcpHub.handleToolCalls(toolCalls, toolContext)`.
   - For each tool call in the batch, `PolicyToolManager`:
     - Calls `IntentToolRegistry.isValid(intentId, toolName)` to defensively confirm the proposed tool is actually permitted for the resolved intent — catching cases where the model strays outside the offered set.
     - If tool is not valid: `PolicyToolManager` logs a warning and records a corrective message turn so the model can recover.
     - If tool is valid: `PolicyToolManager` calls `PolicyEngine.authorize(userId, requiredScope)` to confirm the caller's OAuth2/OIDC scopes permit the action (e.g. `order.read` vs `order.write`).
     - If policy denies: `PolicyToolManager` logs the policy denial and returns a `ToolExecutionResult` indicating policy denial. `AssistantChatService` breaks the loop and returns the denial reason to the user.
     - If policy allows: `PolicyToolManager` records an `agent.tool.call` span event, executes the tool via `executeTool(...)`, records `agent.tool.result`, and records the tool result turn.
   - `AssistantChatService` appends all returned model and tool turns to conversation history and continues the loop.
   - Once the model produces a final direct answer (or the iteration limit is reached), the loop terminates.
5. **Response Generation**: `AssistantChatService` stores the assistant's reply and returns `ChatMessageResponse` to the client.

## New Components

| Component | Responsibility | Public Interface / Methods |
|---|---|---|
| `IntentResolver` | Classifies the user's utterance into a taxonomy intent ID with a confidence score, once per turn. | `IntentClassification resolve(String userMessage, List<AssistantMessage> context)` |
| `IntentClassification` | Immutable record representing classification outcome. | `String intentId()`, `double confidence()` |
| `IntentTaxonomy` / `IntentDefinition` | Configuration model of `intentId` → description, examples, allowed tools, required OAuth2 scope, and confidence threshold. | Properties / YAML mapping |
| `IntentToolRegistry` | Loads taxonomy; answers allowed tools, intent metadata, and validates tool permissions. | `List<Tool> allowedTools(String intentId, List<Tool> availableTools)`, `boolean isValid(String intentId, String toolName)`, `String getRequiredScope(String toolName)`, `Optional<IntentDefinition> getIntent(String intentId)` |
| `PolicyEngine` | Authorizes an action against caller's token scopes (`decision.policy`); enforcement point for read/write distinctions. | `PolicyDecision authorize(String userId, String requiredScope)` |
| `PolicyDecision` | Immutable record indicating authorization outcome. | `boolean isAllowed()`, `String reason()` |

## Intent Taxonomy — mapped to the actual MCP tool set

This is the concrete `IntentTaxonomy` config derived from the current `/mcp` `tools/list` response (7 tools: `search_available_products`, `get_product_by_sku`, `get_order_status`, `get_order_details`, `place_order`, `list_customer_orders`, `cancel_order`) plus `general.conversation` for non-commerce inquiries:

```yaml
intents:
  - id: general.conversation
    description: "User engages in greetings, small talk, or general inquiries unrelated to catalog or orders"
    examples:
      - "hello"
      - "hi there"
      - "who are you"
      - "help me"
    allowedTools: []
    requiredScope: null
    confidenceThreshold: 0.50

  - id: catalog.product.search
    description: "User wants to browse or search for products by keyword, category, or price range"
    examples:
      - "show me running shoes under $100"
      - "what electronics do you have in stock"
      - "find chargers"
    allowedTools:
      - search_available_products
    requiredScope: catalog.read
    confidenceThreshold: 0.80

  - id: catalog.product.lookup
    description: "User wants details on a specific, already-identified product by SKU"
    examples:
      - "tell me more about SM-PH-001"
      - "is PROD-001 in stock"
      - "check sku NG-CHARGER-02"
    allowedTools:
      - get_product_by_sku
    requiredScope: catalog.read
    confidenceThreshold: 0.85

  - id: information.lookup.order.status
    description: "User wants a quick status check on a single known order — not full details"
    examples:
      - "where is my order ORD-1001"
      - "status of order 1234"
      - "track order ORD-55"
    allowedTools:
      - get_order_status
    requiredScope: order.read
    confidenceThreshold: 0.85

  - id: information.lookup.order.details
    description: "User wants full order contents — line items, pricing, fulfillment — not just status"
    examples:
      - "what did I order in ORD-1001"
      - "show me the full details of my last order"
      - "view details for order ORD-999"
    allowedTools:
      - get_order_details
    requiredScope: order.read
    confidenceThreshold: 0.85

  - id: information.lookup.order.history
    description: "User wants to see multiple past orders, optionally filtered by status"
    examples:
      - "show my order history"
      - "list my cancelled orders"
      - "show all my previous purchases"
    allowedTools:
      - list_customer_orders
    requiredScope: order.read
    confidenceThreshold: 0.80

  - id: commerce.order.place
    description: "User wants to place a new order for one or more items"
    examples:
      - "order 2 of NG-EARBUD-01"
      - "buy the wireless earbuds"
      - "place order for item PROD-1"
    allowedTools:
      - place_order
    requiredScope: order.write
    confidenceThreshold: 0.92   # mutating + financial — high bar before acting

  - id: commerce.order.cancel
    description: "User wants to cancel an existing order"
    examples:
      - "cancel my order ORD-1001"
      - "I want to cancel that last order"
    allowedTools:
      - cancel_order
    requiredScope: order.write
    confidenceThreshold: 0.92   # mutating — high bar before acting
```

Notes on thresholds: the two mutating intents (`commerce.order.place`, `commerce.order.cancel`) are deliberately set well above the read-only intents. Per the failure-mode handling above, falling below threshold on a read intent just widens the tool set the model sees; falling below threshold on a mutating intent triggers a clarifying question instead — preventing ambiguous guessing on mutating actions.

## Decision Observability Mapping (ADR-0014)

| Stage | `decision.action` | `decision.intent` | `decision.confidence` | `decision.policy` | `decision.outcome.status` |
|---|---|---|---|---|---|
| Intent resolved | `intent.resolve` | e.g. `information.lookup.order.status` | e.g. `0.96` | — | `RESOLVED` / `LOW_CONFIDENCE` |
| Registry check | `registry.validate` | same | — | — | `VALID` / `TOOL_MISMATCH` |
| Policy check | `policy.authorize` | same | — | e.g. `order.read` | `ALLOW` / `DENY` |
| Tool execution | `tool.execute` / `<toolName>` | same | — | — | `SUCCESS` / `FAILURE` |

This keeps intent resolution, registry validation, and policy enforcement entirely inside `apps/polaris-assistant`, consistent with ADR-0008 (assistant isolation) — no changes to Polaris Core or the MCP surface are required.