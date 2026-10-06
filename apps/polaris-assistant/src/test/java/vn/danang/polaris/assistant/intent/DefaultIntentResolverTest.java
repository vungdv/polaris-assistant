package vn.danang.polaris.assistant.intent;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * Verifies DefaultIntentResolver's orchestration: blank-input short-circuiting, delegation
 * to {@link IntentClassifier}, and threshold-based tool gating. Taxonomy loading is covered by
 * {@link DefaultIntentManagerTest}. The semantic quality of classification itself belongs to
 * {@link IntentClassifier} implementations (see {@link TypeSafeIntentClassifierTest}), not this
 * class.
 */
class DefaultIntentResolverTest {

    private StubIntentClassifier classifier;
    private DefaultIntentResolver resolver;

    @BeforeEach
    void setUp() {
        classifier = new StubIntentClassifier();
        resolver = new DefaultIntentResolver(classifier);
    }

    // =========================================================================
    // 1. Happy path — main successful flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a non-blank query, when resolved, then delegates to the IntentClassifier and returns its classification")
        void delegates_classification_to_intent_classifier() {
            classifier.nextResult = new IntentClassification("catalog.product.search", 0.93);

            IntentClassification result = resolver.resolve("find chargers in stock", List.of());

            assertThat(result.intentId()).isEqualTo("catalog.product.search");
            assertThat(result.confidence()).isEqualTo(0.93);
            assertThat(classifier.lastQuery).isEqualTo("find chargers in stock");
            assertThat(classifier.invocationCount).isEqualTo(1);
        }

        @Test
        @DisplayName("Given classification result and available tools, when resolve called, then returns ResolvedIntent with filtered accepted tools")
        void resolves_intent_and_filters_accepted_tools() {
            classifier.nextResult = new IntentClassification("catalog.product.search", 0.93);
            Tool searchTool = Tool.builder("search_available_products", Map.of()).build();
            Tool orderTool = Tool.builder("place_order", Map.of()).build();
            List<Tool> allTools = List.of(searchTool, orderTool);

            ResolvedIntent resolved = resolver.resolve("search for wireless headphones", List.of(), allTools);

            assertThat(resolved.intentId()).isEqualTo("catalog.product.search");
            assertThat(resolved.confidence()).isEqualTo(0.93);
            assertThat(resolved.meetsThreshold()).isTrue();
            assertThat(resolved.acceptedTools()).extracting(Tool::name).containsExactly("search_available_products");
        }

        @Test
        @DisplayName("Given non-blank query, when resolved, then classifier receives the full loaded intent taxonomy")
        void passes_full_taxonomy_to_classifier() {
            classifier.nextResult = new IntentClassification("general.conversation", 0.9);

            resolver.resolve("hello", List.of());

            assertThat(classifier.lastIntents)
                    .extracting(IntentDefinition::id)
                    .contains("general.conversation", "catalog.product.search", "commerce.order.place");
        }
    }

    // =========================================================================
    // 3. Edge cases — classifier fallback behavior, history extraction
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {
        @Test
        @DisplayName("Given default constructor with no TypeSafe API key configured, when resolved, then fails closed instead of calling the network")
        void operates_safely_with_default_constructor_and_no_api_key_configured() {
    DefaultIntentResolver defaultResolver = new DefaultIntentResolver();

            IntentClassification result = defaultResolver.resolve("hello", List.of());

            assertThat(result.intentId()).isEqualTo("general.conversation");
        }

        @Test
        @DisplayName("Given a low-confidence classification below threshold, when resolve called with tools, then falls back to general.conversation with no tool")
        void falls_back_to_general_conversation_when_confidence_below_threshold() {
            classifier.nextResult = new IntentClassification("general.conversation", 0.1);
            List<Tool> allTools = tools("search_available_products", "get_product_by_sku", "get_order_status",
                    "search_customers_by_name", "place_order", "cancel_order", "unknown_tool");

            ResolvedIntent resolved = resolver.resolve("xyzzy completely unknown query 12345", List.of(), allTools);

            assertThat(resolved.intentId()).isEqualTo("general.conversation");
            assertThat(resolved.meetsThreshold()).isFalse();
            assertThat(resolved.acceptedTools()).isEmpty();
        }

        @Test
        @DisplayName("Given commerce.order.place below its threshold (e.g. a vague 'yes do it'), when resolved, then falls back to general.conversation with no tool")
        void falls_back_to_general_conversation_when_order_intent_is_below_threshold() {
            classifier.nextResult = new IntentClassification("commerce.order.place", 0.6);
            List<Tool> allTools = tools("search_available_products", "stage_order_draft", "cancel_order");

            ResolvedIntent resolved = resolver.resolve("yes do it", List.of(), allTools);

            assertThat(resolved.meetsThreshold()).isFalse();
            assertThat(resolved.confidence()).isEqualTo(0.6);
            assertThat(resolved.intentId()).isEqualTo("general.conversation");
            assertThat(resolved.intentDefinition().id()).isEqualTo("general.conversation");
            assertThat(resolved.acceptedTools()).isEmpty();
        }

        @Test
        @DisplayName("Given an unknown intent id, when resolved, then the empty intent exposes no tool")
        void offers_no_tools_for_unknown_intent() {
            classifier.nextResult = new IntentClassification("does.not.exist", 0.99);
            List<Tool> allTools = tools("search_available_products", "place_order", "cancel_order");

            ResolvedIntent resolved = resolver.resolve("do the thing", List.of(), allTools);

            assertThat(resolved.acceptedTools()).isEmpty();
        }

        @Test
        @DisplayName("Given a custom taxonomy and classifier choice, when resolved, then returns exactly what the classifier chose")
        void returns_exactly_what_classifier_chose_for_custom_taxonomy() {
            List<IntentDefinition> customIntents = List.of(
                    new IntentDefinition("first.intent", "First", List.of("same utterance"), List.of(), 0.80),
                    new IntentDefinition("second.intent", "Second", List.of("same utterance"), List.of(), 0.80)
            );
            classifier.nextResult = new IntentClassification("first.intent", 1.0);
            DefaultIntentResolver customResolver = new DefaultIntentResolver(classifier, customIntents);

            IntentClassification result = customResolver.resolve("same utterance", List.of());

            assertThat(result.intentId()).isEqualTo("first.intent");
            assertThat(result.confidence()).isEqualTo(1.0);
        }
    }

    private static List<Tool> tools(String... names) {
        return Arrays.stream(names)
                .map(name -> Tool.builder(name, Map.of()).build())
                .toList();
    }

    private static class StubIntentClassifier implements IntentClassifier {
        IntentClassification nextResult = new IntentClassification(DefaultIntentResolver.DEFAULT_INTENT, 1.0);
        boolean shouldThrow = false;
        String lastQuery;
        Collection<IntentDefinition> lastIntents;
        int invocationCount = 0;

        @Override
        public IntentClassification classify(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents) {
            invocationCount++;
            lastQuery = query;
            lastIntents = intents;
            if (shouldThrow) {
                throw new RuntimeException("stub classifier failure");
            }
            return nextResult;
        }
    }
}
