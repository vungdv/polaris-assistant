package vn.danang.polaris.assistant.intent;

import java.util.Collection;
import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Judges which taxonomy intent a user message expresses.
 * Implementations may consult an external model; callers should treat a thrown
 * exception as "unable to classify" rather than a definitive answer.
 */
@FunctionalInterface
public interface IntentClassifier {

    /**
     * Classifies a user query against the given intent taxonomy.
     *
     * @param query non-blank user utterance to classify
     * @param history conversation history providing disambiguating context (e.g. pronouns, follow-ups)
     * @param intents the candidate intent taxonomy to choose from
     * @return the classified intent and its confidence
     */
    IntentClassification classify(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents);
}
