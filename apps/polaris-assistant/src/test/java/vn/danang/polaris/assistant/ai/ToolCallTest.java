package vn.danang.polaris.assistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // 1. Happy path — record construction and property access
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given all fields including id, name, args, thoughtSignature, when constructed, then exposes all properties")
        void constructor_withAllFields_setsPropertiesCorrectly() {
            Map<String, Object> args = Map.of("query", "phone", "max_price", 100);
            ToolCall toolCall = new ToolCall("call_123", "search_products", args, "sig_abc");

            assertThat(toolCall.id()).isEqualTo("call_123");
            assertThat(toolCall.name()).isEqualTo("search_products");
            assertThat(toolCall.args()).isEqualTo(args);
            assertThat(toolCall.arguments()).isEqualTo(args);
            assertThat(toolCall.thoughtSignature()).isEqualTo("sig_abc");
        }

        @Test
        @DisplayName("Given name and args, when constructed via 2-arg constructor, then id and thoughtSignature default to null")
        void constructor_withNameAndArgs_defaultsOptionalFieldsToNull() {
            Map<String, Object> args = Map.of("sku", "SKU-001");
            ToolCall toolCall = new ToolCall("get_product_detail", args);

            assertThat(toolCall.id()).isNull();
            assertThat(toolCall.name()).isEqualTo("get_product_detail");
            assertThat(toolCall.args()).isEqualTo(args);
            assertThat(toolCall.arguments()).isEqualTo(args);
            assertThat(toolCall.thoughtSignature()).isNull();
        }

        @Test
        @DisplayName("Given id, name, and args, when constructed via 3-arg constructor, then thoughtSignature defaults to null")
        void constructor_withIdNameAndArgs_setsFieldsCorrectly() {
            Map<String, Object> args = Map.of("order_id", "ORD-999");
            ToolCall toolCall = new ToolCall("call_456", "get_order_status", args);

            assertThat(toolCall.id()).isEqualTo("call_456");
            assertThat(toolCall.name()).isEqualTo("get_order_status");
            assertThat(toolCall.args()).isEqualTo(args);
            assertThat(toolCall.thoughtSignature()).isNull();
        }

        @Test
        @DisplayName("Given name, args, and thoughtSignature, when constructed via 3-arg constructor, then id defaults to null")
        void constructor_withNameArgsAndThoughtSignature_setsFieldsCorrectly() {
            Map<String, Object> args = Map.of("category", "electronics");
            ToolCall toolCall = new ToolCall("browse_category", args, "sig_xyz");

            assertThat(toolCall.id()).isNull();
            assertThat(toolCall.name()).isEqualTo("browse_category");
            assertThat(toolCall.args()).isEqualTo(args);
            assertThat(toolCall.thoughtSignature()).isEqualTo("sig_xyz");
        }
    }

    // =========================================================================
    // 2. Invalid input & defaults
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & defaults")
    class InvalidInput {

        @Test
        @DisplayName("Given null args in canonical constructor, then defaults to empty map")
        void constructor_withNullArgs_defaultsToEmptyMap() {
            ToolCall toolCall = new ToolCall("call_1", "test_tool", null, null);

            assertThat(toolCall.args()).isNotNull().isEmpty();
            assertThat(toolCall.arguments()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("Given null args in 2-arg constructor, then defaults to empty map")
        void constructor_withNullArgsInTwoArgConstructor_defaultsToEmptyMap() {
            ToolCall toolCall = new ToolCall("test_tool", null);

            assertThat(toolCall.args()).isNotNull().isEmpty();
            assertThat(toolCall.arguments()).isNotNull().isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases & JSON compatibility
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given JSON with id, name, and args, when deserialized, then populates fields")
        void jsonDeserialization_withStandardLlmFields_deserializesCorrectly() throws Exception {
            String json = """
                    {
                      "id": "call_gemini_01",
                      "name": "search_products",
                      "args": {
                        "query": "laptop"
                      }
                    }
                    """;

            ToolCall toolCall = objectMapper.readValue(json, ToolCall.class);

            assertThat(toolCall.id()).isEqualTo("call_gemini_01");
            assertThat(toolCall.name()).isEqualTo("search_products");
            assertThat(toolCall.args()).containsEntry("query", "laptop");
            assertThat(toolCall.thoughtSignature()).isNull();
        }

        @Test
        @DisplayName("Given JSON with legacy 'arguments' and 'thought_signature', when deserialized, then aliases map correctly")
        void jsonDeserialization_withLegacyAliases_mapsToRecordComponents() throws Exception {
            String json = """
                    {
                      "name": "filter_deals",
                      "arguments": {
                        "discount": 20
                      },
                      "thought_signature": "snake_case_sig"
                    }
                    """;

            ToolCall toolCall = objectMapper.readValue(json, ToolCall.class);

            assertThat(toolCall.id()).isNull();
            assertThat(toolCall.name()).isEqualTo("filter_deals");
            assertThat(toolCall.args()).containsEntry("discount", 20);
            assertThat(toolCall.arguments()).containsEntry("discount", 20);
            assertThat(toolCall.thoughtSignature()).isEqualTo("snake_case_sig");
        }

        @Test
        @DisplayName("Given ToolCall serialized to JSON, when deserialized back, then values match")
        void jsonSerialization_roundTrip_preservesAllProperties() throws Exception {
            ToolCall original = new ToolCall("call_rt", "roundtrip_tool", Map.of("key", "val"), "sig_rt");

            String json = objectMapper.writeValueAsString(original);
            ToolCall deserialized = objectMapper.readValue(json, ToolCall.class);

            assertThat(deserialized.id()).isEqualTo(original.id());
            assertThat(deserialized.name()).isEqualTo(original.name());
            assertThat(deserialized.args()).isEqualTo(original.args());
            assertThat(deserialized.thoughtSignature()).isEqualTo(original.thoughtSignature());
        }
    }
}
