# Slice Work Order: WO-013
## Title: AI Assistant Turn Enclosing Span and ReAct Lifecycle Events

- **Target Modules:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0013](../decisions/0013-agent-turn-span-and-lifecycle-events.md)
- **Product Reference:** [PRD-005](../../business/prds/PRD-005-agent-turn-observability-and-lifecycle-events.md)
- **Bounded Contexts:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Status:** READY FOR DEV

---

## 1. Objective & Scope

Instrument `AssistantChatService` in `apps/polaris-assistant` with an enclosing distributed tracing span named `agent.turn` and structured Micrometer/OpenTelemetry span events representing the complete ReAct agent lifecycle:
1. **Enclosing Span & Active Scope:** Wrap the entire turn execution in `agent.turn`, putting it into scope via `tracer.withSpan(span)` so that child model and tool spans naturally nest beneath it.
2. **Structured Lifecycle Events:** Record the exact sequence of 9 lifecycle milestone events:
   - `agent.request.received`
   - `tools.discovered`
   - `agent.iteration.started`
   - `model.request`
   - `model.response`
   - `agent.tool.call`
   - `agent.tool.result`
   - `agent.response.generated`
   - `agent.completed`
3. **Semantic Tags & Attributes:** Tag `agent.name`, `agent.framework`, `agent.session_id`, `agent.user_id`, `agent.iterations.count`, and `agent.tools.count`.
4. **Resilience & Fail-Safe:** Ensure non-blocking execution, null safety when `tracer == null`, and proper error capture (`span.error(ex)`, `error="true"`).

**Constraint Checklist:**
- [x] Maintain backwards compatibility of all existing `AssistantChatService` constructors.
- [x] All tracing logic must be non-blocking and fail-safe against null `Tracer` beans.
- [x] Event names must match the exact string literals specified by leadership.
- [x] Enclosing span must activate `SpanInScope` so that child spans (`gemini.generate_content`, `mcp.tool_call`, `mcp.list_tools`) nest automatically.
- [x] Comprehensive automated unit tests verifying event emission, tag recording, and null tracer fallback.

---

## 2. Detailed Technical Tasks

### Task 1: Update `AssistantChatService.java` Constructors & Dependencies
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`
1. Inject `ObjectProvider<Tracer> tracerProvider` in `@Autowired` constructor:
   ```java
   @Autowired
   public AssistantChatService(
           AssistantModelClient modelClient,
           ExternalMcpHub toolManager,
           ObjectMapper objectMapper,
           ObjectProvider<Tracer> tracerProvider) {
       this(modelClient, toolManager, objectMapper, tracerProvider != null ? tracerProvider.getIfAvailable() : null);
   }
   ```
2. Store `@Nullable private final Tracer tracer;`.
3. Provide overloaded constructors maintaining binary compatibility:
   - `public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub toolManager, ObjectMapper objectMapper)`
   - `public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub toolManager)`
   - `public AssistantChatService(AssistantModelClient modelClient)`
   - `public AssistantChatService(AssistantModelClient modelClient, ExternalMcpHub toolManager, ObjectMapper objectMapper, @Nullable Tracer tracer)`

### Task 2: Instrument Enclosing Span `agent.turn`
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`
1. In `sendMessage(ChatMessageRequest request, String userId)`:
   - Check if `this.tracer == null`. If null, proceed with standard untraced execution.
   - If `this.tracer != null`:
     - Create span:
       ```java
       Span span = this.tracer.customNextSpan().name("agent.turn");
       span.tag("agent.name", "assistant-chat");
       span.tag("agent.framework", "polaris-assistant");
       if (sessionId != null) {
           span.tag("agent.session_id", sessionId);
       }
       span.tag("agent.user_id", userId != null ? userId : "anonymous");
       span.start();
       ```
     - Wrap execution in `try (Tracer.SpanInScope ws = this.tracer.withSpan(span))`:
       - Record start event: `span.event("agent.request.received");`
       - Wrap with `try ... catch (Exception ex) { span.error(ex); span.tag("error", "true"); throw ex; } finally { span.end(); }`.

### Task 3: Instrument Lifecycle Span Events
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`
Record events at these precise locations (helper method `recordEvent(Span span, String eventName)` is recommended):
1. `span.event("agent.request.received")` -> At method entry.
2. `span.event("tools.discovered")` -> Immediately after `toolManager.discoverAllTools()`. Also record `span.tag("agent.tools.count", String.valueOf(availableTools.size()))`.
3. While-loop (`iterations < MAX_TOOL_ITERATIONS`):
   - Inside loop start: `span.event("agent.iteration.started")`.
   - Before model invocation: `span.event("model.request")`.
   - After model invocation: `span.event("model.response")`.
   - In tool execution loop (for each `toolCall` in `modelResponse.toolCalls()`):
     - Before tool invocation: `span.event("agent.tool.call")`.
     - After tool invocation: `span.event("agent.tool.result")`.
4. After loop exit:
   - Once final reply text is resolved: `span.event("agent.response.generated")`.
   - Tag iteration count: `span.tag("agent.iterations.count", String.valueOf(iterations))`.
5. Before return:
   - `span.event("agent.completed")`.

### Task 4: Add Comprehensive Unit Tests
**File:** `apps/polaris-assistant/src/test/java/vn/danang/polaris/assistant/service/AssistantChatServiceTest.java`
Add unit tests verifying:
1. `sendMessage_withToolCall_emitsFullLifecycleSpanEventsInOrder`:
   - Mock `Tracer`, `Span`, `SpanInScope`.
   - Execute turn with 1 tool call that loops to final reply.
   - Verify `tracer.customNextSpan()` and `span.name("agent.turn")`.
   - Verify `span.tag("agent.name", "assistant-chat")`, `span.tag("agent.session_id", ...)` etc.
   - Verify events in exact order:
     - `agent.request.received`
     - `tools.discovered`
     - `agent.iteration.started`
     - `model.request`
     - `model.response`
     - `agent.tool.call`
     - `agent.tool.result`
     - `agent.iteration.started`
     - `model.request`
     - `model.response`
     - `agent.response.generated`
     - `agent.completed`
   - Verify `span.end()` is invoked.
2. `sendMessage_withoutToolCalls_emitsDirectLifecycleEvents`:
   - Verify events when model responds directly: `agent.request.received`, `tools.discovered`, `agent.iteration.started`, `model.request`, `model.response`, `agent.response.generated`, `agent.completed`.
   - Verify `agent.tool.call` and `agent.tool.result` are never emitted.
3. `sendMessage_whenExceptionOccurs_tagsErrorAndRecordsExceptionOnSpan`:
   - Throw exception during model call or input validation.
   - Verify `span.error(any())`, `span.tag("error", "true")`, and `span.end()`.
4. `sendMessage_withoutTracer_executesCleanly`:
   - Verify all existing tests run without tracer and behave identically.

---

## 3. Verification & Acceptance Criteria

Run the test suite:
```bash
mvn clean test -pl apps/polaris-assistant
mvn clean test
```
All unit tests in `polaris-assistant` and across the monorepo reactor must pass with 0 failures and 0 errors.
