# Slice Work Order: WO-017
## Title: Tool Execution Span Enrichment, Audit Governance, and Argument Sanitization

- **Target Modules:** `apps/polaris-assistant`
- **Owner / Assignee:** `domain-dev-agent`
- **Architecture Reference:** [ADR-0016](../decisions/0016-genai-observability-and-mcp-audit-standards.md)
- **Product Reference:** [PRD-006](../../business/prds/PRD-006-genai-observability-and-tool-audit.md)
- **Bounded Contexts:** Polaris Assistant Context (`apps/polaris-assistant`)
- **Status:** COMPLETED

---

## 1. Objective & Scope

Implement tool execution auditability, governance attribution, and PII/PCI argument sanitization on MCP tool execution spans (`mcp.tool_call`) in `apps/polaris-assistant`:

1. Implement `ArgumentSanitizer` in `vn.danang.polaris.assistant.observability`:
   - Recursively sanitize tool invocation arguments, redacting sensitive PII/PCI keys (`password`, `token`, `secret`, `card`, `cvv`, `ssn`, `address`, `phone`, `email`, etc.) with `"[REDACTED]"`.
   - Produce a safe, bounded JSON summary string (`mcp.tool.args_summary`) capped at 256 characters.
2. Update `PolicyToolManager`:
   - Tag `mcp.tool_call` distributed tracing spans with standard `gen_ai.tool.name`.
3. Direct Tool Execution via `PolicyToolManager` *(superseded Task 3: AgentDecisionRecorder decommissioned)*:
   - Tool execution managed directly by `ExternalMcpHub.handleToolCalls` / `executeTool`.
4. Deliver comprehensive unit tests in `ArgumentSanitizerTest` and `ExternalToolManagerTest`.

**Constraint Checklist:**
- [x] Zero raw PII/PCI data attached to distributed tracing spans.
- [x] Strict bounding of argument summary string (max 256 chars).
- [x] Maintain 100% binary and source backwards compatibility for `PolicyToolManager`.
- [x] Complete fail-safe behavior: sanitization or logging errors must never abort tool execution.
- [x] Unit test coverage must achieve 100% pass rate across `polaris-assistant` and monorepo reactor.

---

## 2. Detailed Technical Tasks

### Task 1: Implement `ArgumentSanitizer`
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/observability/ArgumentSanitizer.java`

```java
package vn.danang.polaris.assistant.observability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Utility for sanitizing tool arguments prior to trace span tagging.
 * Redacts sensitive PII and PCI fields and generates length-bounded summaries.
 */
public final class ArgumentSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ArgumentSanitizer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String REDACTED = "[REDACTED]";
    public static final int DEFAULT_MAX_SUMMARY_LENGTH = 256;

    private static final Set<String> SENSITIVE_KEY_PATTERNS = Set.of(
            "password", "token", "secret", "authorization", "auth",
            "card", "cardnumber", "cvv", "pan", "account",
            "ssn", "tax_id",
            "email", "phone", "mobile",
            "address", "street", "postal", "zip",
            "customer_id", "user_id"
    );

    private ArgumentSanitizer() {}

    public static Map<String, Object> sanitize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        return sanitizeMap(arguments);
    }

    public static String sanitizeToSummary(Map<String, Object> arguments) {
        return sanitizeToSummary(arguments, DEFAULT_MAX_SUMMARY_LENGTH);
    }

    public static String sanitizeToSummary(Map<String, Object> arguments, int maxLength) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            Map<String, Object> sanitized = sanitizeMap(arguments);
            String json = OBJECT_MAPPER.writeValueAsString(sanitized);
            if (json.length() > maxLength) {
                return json.substring(0, maxLength - 3) + "...";
            }
            return json;
        } catch (Exception e) {
            log.warn("Failed to serialize sanitized arguments summary: {}", e.getMessage());
            return "{\"sanitization_error\":\"true\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeMap(Map<String, Object> map) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (isSensitiveKey(key)) {
                result.put(key, REDACTED);
            } else if (value instanceof Map<?, ?> nestedMap) {
                result.put(key, sanitizeMap((Map<String, Object>) nestedMap));
            } else if (value instanceof List<?> list) {
                result.put(key, sanitizeList(list));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sanitizeList(List<?> list) {
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> nestedMap) {
                result.put(sanitizeMap((Map<String, Object>) nestedMap));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String pattern : SENSITIVE_KEY_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
```

### Task 2: Update `PolicyToolManager` Span Tagging
**File:** `tools`

In `executeTool(String toolName, Map<String, Object> arguments)`:
- Add `span.tag("gen_ai.tool.name", toolName != null ? toolName : "unknown");` to the `mcp.tool_call` span alongside existing `mcp.tool.name`.

### Task 3: Enhance `AgentDecisionRecorder.recordToolExecution` *(Superseded: Decommissioned)*
**File:** `apps/polaris-assistant/src/main/java/vn/danang/polaris/assistant/observability/AgentDecisionRecorder.java` *(Decommissioned)*

1. Add enriched overload:
   ```java
   public CallToolResult recordToolExecution(
           String sessionId,
           String intent,
           double confidence,
           int iteration,
           String toolName,
           boolean validationResult,
           String policyDecision,
           @Nullable String policyReason,
           @Nullable String requiredScope,
           @Nullable Map<String, Object> arguments,
           List<Tool> availableTools,
           @Nullable Span span,
           Supplier<CallToolResult> toolExecution)
   ```
2. In this overload:
   - Attach audit tags to `targetSpan`:
     * `span.tag("gen_ai.tool.name", toolName);`
     * `span.tag("agent.iteration", String.valueOf(iteration));`
     * `span.tag("agent.tool.validation_result", validationResult ? "VALID" : "TOOL_MISMATCH");`
     * `span.tag("agent.policy.decision", policyDecision != null ? policyDecision : "ALLOW");`
     * `span.tag("agent.policy.reason", policyReason != null ? policyReason : "None");`
     * `span.tag("agent.policy.required_scope", requiredScope != null ? requiredScope : "none");`
     * `String argsSummary = ArgumentSanitizer.sanitizeToSummary(arguments);`
     * `span.tag("mcp.tool.args_summary", argsSummary);`
   - Execute the supplier.
   - Upon result:
     * Compute result size bytes:
       ```java
       int resultBytes = 0;
       if (result != null && result.content() != null) {
           for (var c : result.content()) {
               if (c instanceof TextContent tc && tc.text() != null) {
                   resultBytes += tc.text().getBytes(StandardCharsets.UTF_8).length;
               }
           }
       }
       span.tag("agent.tool.result_size_bytes", String.valueOf(resultBytes));
       ```
     * If `result.isError()`: `span.tag("error", "true");`
   - On exception:
     * `span.tag("error", "true");`
3. Retain all existing `recordToolExecution` method signatures as overloads delegating with safe default values (`iteration = 1`, `validationResult = true`, `policyDecision = "ALLOW"`, `arguments = Map.of()`).

### Task 4: Unit Test Suite
1. **Author `ArgumentSanitizerTest.java`** in `src/test/java/vn/danang/polaris/assistant/observability/`:
   - `sanitize_redactsSensitiveFields`: verify `cardNumber`, `cvv`, `password`, `address` are replaced by `[REDACTED]`.
   - `sanitize_preservesNonSensitiveFields`: verify `query`, `category`, `limit` remain untouched.
   - `sanitize_handlesNestedMaps`: verify nested objects are recursively sanitized.
   - `sanitizeToSummary_boundsLength`: verify long payloads are truncated to 256 characters with `...`.
   - `sanitizeToSummary_handlesNullAndEmpty`: returns `"{}"` without error.
2. **Update `ExternalMcpHubTest.java`**:
   - Verify `gen_ai.tool.name` is tagged on the span.

---

## 3. Verification & Acceptance Criteria

```bash
mvn clean test -pl apps/polaris-assistant -Dtest="ArgumentSanitizerTest,ExternalMcpHubTest"
mvn clean test -pl apps/polaris-assistant
```
All tests must pass with 0 failures and 0 errors.
