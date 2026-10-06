package vn.danang.polaris.assistant.intent;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Forwards classification requests to an {@link IntentClassifier}, turning its result into a
 * {@link ResolvedIntent} scoped to the matched {@link IntentDefinition}. The intent taxonomy
 * itself is provided by an {@link IntentManager}, which abstracts over where intents actually
 * live (local classpath resource by default, a remote store in other implementations).
 * Below the intent's confidence threshold the turn falls back to {@value #DEFAULT_INTENT} and is offered
 * only that intent's tools, so an ambiguous message can't reach a write tool.
 */
@Component
@Primary
public class DefaultIntentResolver implements IntentResolver {
    public static final String DEFAULT_INTENT = "general.conversation";

    private final IntentClassifier intentClassifier;
    private final IntentManager intentManager;

    @Autowired
    public DefaultIntentResolver(IntentClassifier intentClassifier, IntentManager intentManager) {
        this.intentClassifier = intentClassifier != null ? intentClassifier : defaultClassifier();
        this.intentManager = intentManager != null ? intentManager : defaultIntentManager();
    }

    public DefaultIntentResolver(IntentClassifier intentClassifier) {
        this(intentClassifier, defaultIntentManager());
    }

    public DefaultIntentResolver() {
        this(defaultClassifier(), defaultIntentManager());
    }

    public DefaultIntentResolver(IntentClassifier intentClassifier, Collection<IntentDefinition> intents) {
        this(intentClassifier, new DefaultIntentManager(intents != null ? List.copyOf(intents) : List.of()));
    }

    private static IntentClassifier defaultClassifier() {
        return new TypeSafeIntentClassifier(new AssistantTypeSafeProperties());
    }

    private static IntentManager defaultIntentManager() {
        return new DefaultIntentManager();
    }

    @Override
    public ResolvedIntent resolve(String messageText, List<AssistantMessage> history, List<Tool> tools) {
        List<IntentDefinition> intents = intentManager.listIntents();
        IntentClassification classification = intentClassifier.classify(messageText, history, intents);
        String intentId = classification.intentId();

        IntentDefinition definition = intentManager.getIntent(intentId).orElse(IntentDefinition.empty());

        double confidence = classification.confidence();
        boolean meetsThreshold = confidence >= definition.confidenceThreshold();
        if (!meetsThreshold) {
            // Low confidence: fall back to general conversation and offer its tools only
            intentId = DEFAULT_INTENT;
            definition = intentManager.getIntent(DEFAULT_INTENT).orElse(IntentDefinition.empty());
        }

        return new ResolvedIntent(intentId, confidence, meetsThreshold, filterTools(definition, tools), definition);
    }

    /**
     * Classifies user message and history against the loaded intent taxonomy, without any
     * tool filtering.
     */
    public IntentClassification resolve(String messageText, List<AssistantMessage> history) {
        return intentClassifier.classify(messageText, history, intentManager.listIntents());
    }

    private List<Tool> filterTools(IntentDefinition definition, List<Tool> availableTools) {
        if (availableTools == null) {
            return List.of();
        }
        if (definition == null) {
            return availableTools;
        }
        List<String> permitted = definition.allowedTools();
        if (permitted == null || permitted.isEmpty()) {
            return List.of();
        }
        return availableTools.stream()
                .filter(t -> permitted.contains(t.name()))
                .toList();
    }
}
