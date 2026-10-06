package vn.danang.polaris.assistant.intent;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link IntentManager} backed by the intent taxonomy loaded locally from classpath
 * {@code intents.json}, falling back to an empty taxonomy if it cannot be loaded or parsed.
 */
@Component
public class DefaultIntentManager implements IntentManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final List<IntentDefinition> intents;

    public DefaultIntentManager() {
        this(loadDefaultIntents());
    }

    public DefaultIntentManager(List<IntentDefinition> intents) {
        this.intents = intents != null ? List.copyOf(intents) : List.of();
    }

    @Override
    public List<IntentDefinition> listIntents() {
        return intents;
    }

    @Override
    public Optional<IntentDefinition> getIntent(String intentId) {
        if (intentId == null) {
            return Optional.empty();
        }
        return intents.stream()
                .filter(def -> intentId.equals(def.id()))
                .findFirst();
    }

    /**
     * Loads the intent taxonomy from the classpath {@code intents.json} resource, falling back
     * to an empty taxonomy if the resource is missing or fails to parse.
     */
    private static List<IntentDefinition> loadDefaultIntents() {
        try (InputStream is = DefaultIntentManager.class.getClassLoader().getResourceAsStream("intents.json")) {
            if (is != null) {
                return loadIntents(is);
            }
            log.warn("Classpath resource [intents.json] not found");
        } catch (Exception e) {
            log.error("Failed to load default intents from classpath:intents.json: {}", e.getMessage(), e);
        }
        return List.of();
    }

    /**
     * Deserializes intents from an {@link InputStream}.
     */
    static List<IntentDefinition> loadIntents(InputStream inputStream) {
        if (inputStream == null) {
            return List.of();
        }
        try {
            IntentDefinition[] array = OBJECT_MAPPER.readValue(inputStream, IntentDefinition[].class);
            return array != null ? List.of(array) : List.of();
        } catch (Exception e) {
            log.error("Failed to parse intents from InputStream: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
