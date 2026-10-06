package vn.danang.polaris.assistant.intent;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DefaultIntentManager}'s taxonomy loading (from classpath {@code intents.json}
 * or an arbitrary {@link java.io.InputStream}, with graceful fallback on malformed JSON) as well
 * as its listing and lookup behavior.
 */
class DefaultIntentManagerTest {

    private static final IntentDefinition SEARCH_INTENT =
            new IntentDefinition("catalog.product.search", "Search", List.of("find product"));
    private static final IntentDefinition ORDER_INTENT =
            new IntentDefinition("commerce.order.place", "Place order", List.of("buy this"));

    @Test
    @DisplayName("Given intents provided at construction, when listIntents called, then returns all of them")
    void lists_all_configured_intents() {
        IntentManager manager = new DefaultIntentManager(List.of(SEARCH_INTENT, ORDER_INTENT));

        assertThat(manager.listIntents())
                .extracting(IntentDefinition::id)
                .containsExactly("catalog.product.search", "commerce.order.place");
    }

    @Test
    @DisplayName("Given a known intent id, when getIntent called, then returns the matching definition")
    void gets_intent_by_id_when_present() {
        IntentManager manager = new DefaultIntentManager(List.of(SEARCH_INTENT, ORDER_INTENT));

        assertThat(manager.getIntent("commerce.order.place")).contains(ORDER_INTENT);
    }

    @Test
    @DisplayName("Given an unknown intent id, when getIntent called, then returns empty")
    void returns_empty_when_intent_id_not_found() {
        IntentManager manager = new DefaultIntentManager(List.of(SEARCH_INTENT));

        assertThat(manager.getIntent("no.such.intent")).isEmpty();
    }

    @Test
    @DisplayName("Given a null intent id, when getIntent called, then returns empty")
    void returns_empty_when_intent_id_null() {
        IntentManager manager = new DefaultIntentManager(List.of(SEARCH_INTENT));

        assertThat(manager.getIntent(null)).isEmpty();
    }

    @Test
    @DisplayName("Given no intents provided, when default constructor used, then loads the standard taxonomy from classpath intents.json")
    void loads_default_taxonomy_from_classpath() {
        IntentManager manager = new DefaultIntentManager();

        assertThat(manager.listIntents())
                .extracting(IntentDefinition::id)
                .contains("general.conversation", "catalog.product.search", "commerce.order.place");
    }

    @Test
    @DisplayName("Given valid JSON array stream, when loaded, then parses into IntentDefinition list")
    void loads_intents_from_valid_json_stream() {
        String json = """
                [
                  {
                    "id": "custom.order.track",
                    "description": "Track a custom order",
                    "examples": ["track custom order"],
                    "allowedTools": ["get_order_status"],
                    "confidenceThreshold": 0.85
                  }
                ]
                """;

        List<IntentDefinition> intents = DefaultIntentManager.loadIntents(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(intents).hasSize(1);
        IntentDefinition def = intents.getFirst();
        assertThat(def.id()).isEqualTo("custom.order.track");
        assertThat(def.description()).isEqualTo("Track a custom order");
        assertThat(def.examples()).containsExactly("track custom order");
        assertThat(def.allowedTools()).containsExactly("get_order_status");
        assertThat(def.confidenceThreshold()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("Given malformed JSON stream, when loaded, then recovers gracefully with empty list")
    void returns_empty_list_for_malformed_json() {
        String malformed = "{ this is not valid json }";

        List<IntentDefinition> result = DefaultIntentManager.loadIntents(
                new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8)));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Given null input stream, when loadIntents called, then returns empty list")
    void returns_empty_list_for_null_input_stream() {
        assertThat(DefaultIntentManager.loadIntents(null)).isEmpty();
    }
}
