package vn.danang.polaris.assistant.intent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelCallResult;

/**
 * Value object representing an intent classification outcome.
 *
 * @param intentId unique identifier of the classified intent
 * @param confidence classification confidence score between 0.0 and 1.0
 * @param fallback whether this result came from a fail-safe default rather than an actual
 *                 classifier judgment (e.g. missing config, request error, malformed response)
 * @param fallbackReason short machine-readable reason code for the fallback, or {@code null} when
 *                        {@code fallback} is {@code false}
 * @param modelCall the TypeSafe call behind this classification, or {@code null} when the model was not
 *                  consulted (missing key, empty taxonomy); never serialized
 */
public record IntentClassification(
        @JsonProperty("intent_id") String intentId,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("fallback") boolean fallback,
        @JsonProperty("fallback_reason") String fallbackReason,
        @JsonIgnore ModelCall modelCall
) implements ModelCallResult {

    public IntentClassification(String intentId, double confidence, boolean fallback, String fallbackReason) {
        this(intentId, confidence, fallback, fallbackReason, null);
    }

    /**
     * Convenience constructor for a genuine (non-fallback) classification result.
     */
    public IntentClassification(String intentId, double confidence) {
        this(intentId, confidence, false, null);
    }

    /**
     * Builds a fallback classification, always with zero confidence, tagged with the reason it
     * occurred so callers (e.g. tracing) can surface it clearly instead of it looking like a
     * genuine low-confidence judgment.
     */
    public static IntentClassification fallback(String intentId, String reason) {
        return new IntentClassification(intentId, 0.0, true, reason);
    }

    public IntentClassification withModelCall(ModelCall modelCall) {
        return new IntentClassification(intentId, confidence, fallback, fallbackReason, modelCall);
    }
}
