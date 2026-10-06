# Slice Work Order: WO-014
## Title: Agent Decision Events Schema and Observability Recording

- **Target Modules:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0014](../decisions/0014-agent-decision-events-and-observability-schema.md)
- **Product Reference:** [PRD-005](../../business/prds/PRD-005-agent-turn-observability-and-lifecycle-events.md)
- **Bounded Contexts:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Status:** DECOMMISSIONED / SUPERSEDED (Components removed in favor of native OTel GenAI standards)

---

## 1. Objective & Scope

Implement structured agent decision event recording within `apps/polaris-assistant` to capture alternative evaluation, policy constraints, confidence scores, and structured outcomes for both tool calls and direct responses:
1. Create domain records (`DecisionEvent`, `EvaluatedAlternative`, `Outcome`) in `vn.danang.polaris.assistant.observability` with snake_case JSON serialization.
2. Implement `AgentDecisionRecorder` component capturing active `trace_id` and `span_id` from Micrometer `Tracer`, emitting stateful JSON log lines (`[STARTING]`, `[COMPLETED]`, `[FAILED]`, `[ERROR]`), and attaching `decision.*` tags to the active span.
3. Integrate `AgentDecisionRecorder` into `AssistantChatService` while preserving all existing constructors for binary backwards compatibility.
4. Provide comprehensive unit tests in `DecisionEventTest` and `AgentDecisionRecorderTest`.

**Constraint Checklist:**
- [x] All JSON serialization properties must be strict `snake_case`.
- [x] Maintain backwards compatibility of all existing `AssistantChatService` constructors.
- [x] Telemetry must be fail-safe against null `Tracer` beans or null active spans.
- [x] `decision.outcome.latency_ms` and other span attributes must be recorded on the active `agent.turn` span.
- [x] Unit test coverage must achieve 100% pass rate across `polaris-assistant` and monorepo reactor.

---

## 2. Detailed Technical Tasks

### Task 1: Create Domain Records
**Package:** `vn.danang.polaris.assistant.observability`
1. `Outcome.java`:
   ```java
   package vn.danang.polaris.assistant.observability;
   import com.fasterxml.jackson.annotation.JsonProperty;
   public record Outcome(
           @JsonProperty("status") String status,
           @JsonProperty("detail") String detail,
           @JsonProperty("latency_ms") long latencyMs
   ) {}
   ```
2. `EvaluatedAlternative.java`:
   ```java
   package vn.danang.polaris.assistant.observability;
   import com.fasterxml.jackson.annotation.JsonProperty;
   public record EvaluatedAlternative(
           @JsonProperty("action") String action,
           @JsonProperty("reason_considered") String reasonConsidered,
           @JsonProperty("selected") boolean selected,
           @JsonProperty("reason_rejected") String reasonRejected
   ) {}
   ```
3. `DecisionEvent.java`:
   ```java
   package vn.danang.polaris.assistant.observability;
   import java.time.Instant;
   import java.util.List;
   import com.fasterxml.jackson.annotation.JsonInclude;
   import com.fasterxml.jackson.annotation.JsonProperty;
   import io.micrometer.tracing.Span;

   public record DecisionEvent(
           @JsonProperty("trace_id") String traceId,
           @JsonProperty("span_id") String spanId,
           @JsonProperty("agent_id") String agentId,
           @JsonProperty("session_id") String sessionId,
           @JsonProperty("timestamp") Instant timestamp,
           @JsonProperty("intent") String intent,
           @JsonProperty("evaluated_alternatives") List<EvaluatedAlternative> evaluatedAlternatives,
           @JsonProperty("selected_action") String selectedAction,
           @JsonProperty("confidence") Double confidence,
           @JsonProperty("policy_constraint") String policyConstraint,
           @JsonProperty("outcome") @JsonInclude(JsonInclude.Include.NON_NULL) Outcome outcome
   ) {
       public DecisionEvent withOutcome(Outcome outcome) {
           return new DecisionEvent(
                   this.traceId, this.spanId, this.agentId, this.sessionId,
                   this.timestamp, this.intent, this.evaluatedAlternatives,
                   this.selectedAction, this.confidence, this.policyConstraint,
                   outcome
           );
       }

       public void recordOn(Span span) {
           if (span == null) return;
           if (selectedAction != null) span.tag("decision.action", selectedAction);
           if (intent != null) span.tag("decision.intent", intent);
           if (confidence != null) span.tag("decision.confidence", String.valueOf(confidence));
           if (policyConstraint != null) span.tag("decision.policy", policyConstraint);
           if (outcome != null) {
               if (outcome.status() != null) span.tag("decision.outcome.status", outcome.status());
               if (outcome.detail() != null) span.tag("decision.outcome.detail", outcome.detail());
               span.tag("decision.outcome.latency_ms", String.valueOf(outcome.latencyMs()));
           }
       }
   }
   ```

### Task 2: Implement `AgentDecisionRecorder`
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/observability/AgentDecisionRecorder.java`
1. Define Spring `@Component` with `ObjectMapper` and `@Nullable Tracer`.
2. Extract active `trace_id` and `span_id` from `tracer.currentSpan()`. If absent, use fallback `"00000000000000000000000000000000"` and `"0000000000000000"`.
3. Implement `recordDirectResponseDecision`:
   - Build alternatives: all available tools marked `selected=false`, `reasonRejected="Alternative tool not selected for current intent"`; add `reply_to_user` marked `selected=true`, `reasonConsidered="Provide direct conversational answer to user"`.
   - Construct `Outcome("SUCCESS", "Direct conversational response generated", latencyMs)`.
   - Log `Agent decision [COMPLETED]: <json>`.
   - Record decision tags on active span.
4. Implement `recordToolExecution`:
   - Build alternatives: selected tool marked `selected=true`; other tools marked `selected=false`.
   - Log `Agent decision [STARTING]: <json>` (outcome is null).
   - Execute tool lambda/supplier.
   - On success:
     * If `toolResult.isError()`: Log `Agent decision [ERROR]: <json>` with `Outcome("FAILURE", errorDetail, latencyMs)` and record span tags.
     * Else: Log `Agent decision [COMPLETED]: <json>` with `Outcome("SUCCESS", summary, latencyMs)` and record span tags.
   - On exception:
     * Log `Agent decision [FAILED]: <json>` with `Outcome("FAILURE", ex.getMessage(), latencyMs)`, record span tags, and rethrow.
5. Implement `buildEvaluatedAlternatives`:
   - Support both direct response and tool selection modes.

### Task 3: Integrate with `AssistantChatService`
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/service/AssistantChatService.java`
1. Inject `AgentDecisionRecorder decisionRecorder`.
2. Maintain backwards-compatible overloaded constructors. If not passed, instantiate default `new AgentDecisionRecorder(objectMapper, tracer)`.
3. Inside ReAct while-loop:
   - When executing tool calls: wrap execution with `decisionRecorder.recordToolExecution(...)`.
   - When exiting loop with direct reply: invoke `decisionRecorder.recordDirectResponseDecision(...)`.

### Task 4: Unit Test Suite
**Target Package:** `vn.danang.polaris.assistant.observability`
1. Author `DecisionEventTest.java`:
   - `starting_and_recordOn_attachesAttributesToSpan`: verify span tags recorded properly.
   - `withOutcome_recordsOutcomeAndGeneratesLogFields`: verify JSON output with snake_case and non-null outcome.
2. Author `AgentDecisionRecorderTest.java`:
   - `recordDirectResponseDecision_recordsSuccessEvent`
   - `recordToolExecution_success_returnsResult`
   - `recordToolExecution_withException_recordsFailureAndRethrows`
   - `recordToolExecution_withErrorResult_recordsFailureOutcome`
   - `buildEvaluatedAlternatives_forDirectResponse_marksToolsRejected`
   - `buildEvaluatedAlternatives_marksSelectedAndRejectsAlternatives`
3. Update `AssistantChatServiceTest.java`:
   - Ensure all existing tests pass with default or mocked `AgentDecisionRecorder`.

---

## 3. Verification & Acceptance Criteria

```bash
mvn clean test -pl apps/polaris-assistant
mvn clean test
```
All tests must pass with 0 failures and 0 errors across the entire reactor.
