package vn.danang.polaris.assistant.intent;

import java.util.List;
import java.util.Optional;

/**
 * Provides access to the intent taxonomy, abstracting over where intents are actually
 * stored (local classpath resource, a Redis-backed store, etc.).
 */
public interface IntentManager {

    /**
     * @return all known {@link IntentDefinition}s
     */
    List<IntentDefinition> listIntents();

    /**
     * @param intentId the intent identifier to look up
     * @return the matching {@link IntentDefinition}, or {@link Optional#empty()} if none matches
     */
    Optional<IntentDefinition> getIntent(String intentId);
}
