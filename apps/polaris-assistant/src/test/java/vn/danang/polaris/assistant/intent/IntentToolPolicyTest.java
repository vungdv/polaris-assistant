package vn.danang.polaris.assistant.intent;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Verifies which tools a turn's {@link ResolvedIntent} accepts, against the shipped {@code intents.json}
 * and edge cases.
 */
class IntentToolPolicyTest {

    private final DefaultIntentManager shipped = new DefaultIntentManager();

    private ResolvedIntent resolved(String intentId) {
        return new ResolvedIntent(intentId, 0.99, true, List.of(), shipped.getIntent(intentId).orElseThrow());
    }

    @Test
    @DisplayName("Given a resolved intent, then exactly its allowed tools are accepted")
    void accepts_the_intents_allowed_tools() {
        ResolvedIntent place = resolved("commerce.order.place");

        assertThat(IntentToolPolicy.acceptsTool(place, "stage_order_draft")).isTrue();
        assertThat(IntentToolPolicy.acceptsTool(place, "search_customers_by_name")).isTrue();
        assertThat(IntentToolPolicy.acceptsTool(place, "get_order_status")).isFalse();
    }

    @Test
    @DisplayName("Given general.conversation (the low-confidence fallback), then no tool is accepted")
    void general_conversation_accepts_no_tool() {
        assertThat(IntentToolPolicy.acceptsTool(resolved("general.conversation"), "search_available_products")).isFalse();
        assertThat(IntentToolPolicy.acceptsTool(resolved("general.conversation"), "stage_order_draft")).isFalse();
    }

    @Test
    @DisplayName("Given a resolved intent without a definition, then the turn's accepted tools are used")
    void falls_back_to_accepted_tools_without_definition() {
        ResolvedIntent resolved = new ResolvedIntent("custom", 0.9, true, List.of(Tool.builder("custom_tool", Map.of()).build()));

        assertThat(IntentToolPolicy.acceptsTool(resolved, "custom_tool")).isTrue();
        assertThat(IntentToolPolicy.acceptsTool(resolved, "other_tool")).isFalse();
    }

    @Test
    @DisplayName("Given shipped taxonomy, place_order is declared by no intent: only the confirm endpoint places orders (S7)")
    void place_order_is_not_in_the_taxonomy() {
        assertThat(shipped.listIntents()).noneMatch(def -> def.allowedTools().contains("place_order"));
    }

    @Test
    @DisplayName("Given a null intent or tool name, then nothing is accepted")
    void accepts_nothing_for_null_input() {
        assertThat(IntentToolPolicy.acceptsTool(null, "search_available_products")).isFalse();
        assertThat(IntentToolPolicy.acceptsTool(resolved("catalog.product.search"), null)).isFalse();
    }
}
