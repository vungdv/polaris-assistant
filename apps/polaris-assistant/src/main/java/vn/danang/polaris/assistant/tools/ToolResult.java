package vn.danang.polaris.assistant.tools;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.dto.ChatWidget;

/**
 * Represents the execution outcome of an individual {@link ToolCall}.
 *
 * @param toolCall the tool call requested by the model
 * @param result the text result or explanation from the tool execution
 * @param status the outcome status of the execution (SUCCESS, DENIED, ERROR)
 * @param errorDescription a semantic note explaining the failure context when the tool execution is not successful, or null if successful
 * @param widget structured card for the chat client produced by a successful call, or null
 * @param retractsWidget widget type an earlier call of the same turn produced that this successful call makes
 *                       stale (e.g. discarding a draft retracts its {@code ORDER_DRAFT} card), or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        @JsonProperty("toolCall") ToolCall toolCall,
        @JsonProperty("result") String result,
        @JsonProperty("status") Status status,
        @JsonProperty("error-description")
        @JsonAlias({"error_description", "errorDescription"})
        String errorDescription,
        @JsonProperty("widget") ChatWidget widget,
        @JsonProperty("retractsWidget") String retractsWidget
) {

    public enum Status {
        SUCCESS,
        DENIED,
        ERROR
    }

    public ToolResult(ToolCall toolCall, String result, Status status) {
        this(toolCall, result, status, null, null, null);
    }

    public ToolResult(ToolCall toolCall, String result, Status status, String errorDescription) {
        this(toolCall, result, status, errorDescription, null, null);
    }

    // Widgets and retractions are carried only by successful results.
    public ToolResult {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (result == null) {
            result = "";
        }
        if (status != Status.SUCCESS) {
            if (errorDescription == null || errorDescription.isBlank()) {
                errorDescription = defaultSemanticNote(status, toolCall, result);
            }
            widget = null;
            retractsWidget = null;
        } else {
            errorDescription = null;
        }
    }

    /**
     * Attaches a card for the chat client (e.g. {@code ORDER_DRAFT}). The text {@link #result} stays what
     * the model sees; the widget only reaches the response.
     */
    public ToolResult withWidget(ChatWidget widget) {
        return new ToolResult(toolCall, result, status, errorDescription, widget, retractsWidget);
    }

    /**
     * Marks a card type produced earlier in the turn as stale, so the response doesn't show it.
     */
    public ToolResult retractingWidget(String widgetType) {
        return new ToolResult(toolCall, result, status, errorDescription, widget, widgetType);
    }

    private static String defaultSemanticNote(Status status, ToolCall toolCall, String result) {
        String toolName = (toolCall.name() != null && !toolCall.name().isBlank()) ? toolCall.name() : "unnamed_tool";
        if (status == Status.DENIED) {
            if (result != null && !result.isBlank()) {
                return "Execution of tool '" + toolName + "' was denied: " + result;
            }
            return "Execution of tool '" + toolName + "' was denied by policy or intent validation.";
        }
        if (status == Status.ERROR) {
            if (result != null && !result.isBlank()) {
                return "Execution of tool '" + toolName + "' failed: " + result;
            }
            return "Execution of tool '" + toolName + "' encountered an error.";
        }
        return "Tool execution did not succeed.";
    }

    public static ToolResult success(ToolCall toolCall, String result) {
        return new ToolResult(toolCall, result, Status.SUCCESS, null);
    }

    public static ToolResult denied(ToolCall toolCall, String reason) {
        return new ToolResult(toolCall, reason, Status.DENIED, null);
    }

    public static ToolResult denied(ToolCall toolCall, String reason, String errorDescription) {
        return new ToolResult(toolCall, reason, Status.DENIED, errorDescription);
    }

    public static ToolResult error(ToolCall toolCall, String error) {
        return new ToolResult(toolCall, error, Status.ERROR, null);
    }

    public static ToolResult error(ToolCall toolCall, String error, String errorDescription) {
        return new ToolResult(toolCall, error, Status.ERROR, errorDescription);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    @JsonIgnore
    public boolean isDenied() {
        return status == Status.DENIED;
    }

    @JsonIgnore
    public boolean isError() {
        return status == Status.ERROR;
    }
}
