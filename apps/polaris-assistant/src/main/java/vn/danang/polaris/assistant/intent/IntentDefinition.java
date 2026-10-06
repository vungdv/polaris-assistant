package vn.danang.polaris.assistant.intent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Plain immutable record representing an intent definition in the assistant taxonomy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IntentDefinition(
        String id,
        String description,
        List<String> examples,
        List<String> allowedTools,
        double confidenceThreshold
) {
    public IntentDefinition {
        examples = examples != null ? List.copyOf(examples) : List.of();
        allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        if (confidenceThreshold <= 0.0) {
            confidenceThreshold = 0.80;
        }
    }

    public IntentDefinition(String id, String description, List<String> examples) {
        this(id, description, examples, List.of(), 0.80);
    }

    /**
     * Convenience deserializer for a single IntentDefinition JSON string.
     */
    public static IntentDefinition empty(){
        return new IntentDefinition("", "", List.of());
    }
}
