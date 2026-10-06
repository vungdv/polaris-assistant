package vn.danang.polaris.assistant.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import vn.danang.polaris.assistant.ai.ToolCall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // 1. Happy path — construction, factory methods, and semantic notes
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid ToolCall, result text, and SUCCESS status, when constructed, then exposes properties with null errorDescription")
        void constructor_withSuccessStatus_populatesPropertiesAndNullErrorDescription() {
            ToolCall toolCall = new ToolCall("call_1", "search_products", Map.of("q", "phone"));
            ToolResult result = new ToolResult(toolCall, "Found 2 products", ToolResult.Status.SUCCESS);

            assertThat(result.toolCall()).isEqualTo(toolCall);
            assertThat(result.result()).isEqualTo("Found 2 products");
            assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCESS);
            assertThat(result.errorDescription()).isNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isDenied()).isFalse();
            assertThat(result.isError()).isFalse();
        }

        @Test
        @DisplayName("Given ToolCall and text, when success factory invoked, then creates SUCCESS result with null errorDescription")
        void factorySuccess_createsResultWithSuccessStatusAndNullErrorDescription() {
            ToolCall toolCall = new ToolCall("lookup_user", Map.of("id", "123"));
            ToolResult result = ToolResult.success(toolCall, "User found");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCESS);
            assertThat(result.result()).isEqualTo("User found");
            assertThat(result.errorDescription()).isNull();
        }

        @Test
        @DisplayName("Given ToolCall and reason without note, when denied factory invoked, then generates default semantic note")
        void factoryDenied_generatesDefaultSemanticNote() {
            ToolCall toolCall = new ToolCall("delete_account", Map.of());
            ToolResult result = ToolResult.denied(toolCall, "Scope missing");

            assertThat(result.isDenied()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.DENIED);
            assertThat(result.result()).isEqualTo("Scope missing");
            assertThat(result.errorDescription()).isEqualTo("Execution of tool 'delete_account' was denied: Scope missing");
        }

        @Test
        @DisplayName("Given ToolCall, reason, and explicit note, when denied factory invoked, then preserves custom semantic note")
        void factoryDenied_preservesCustomSemanticNote() {
            ToolCall toolCall = new ToolCall("delete_account", Map.of());
            ToolResult result = ToolResult.denied(toolCall, "Scope missing", "Caller lacks required admin role");

            assertThat(result.isDenied()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.DENIED);
            assertThat(result.result()).isEqualTo("Scope missing");
            assertThat(result.errorDescription()).isEqualTo("Caller lacks required admin role");
        }

        @Test
        @DisplayName("Given ToolCall and error message without note, when error factory invoked, then generates default semantic note")
        void factoryError_generatesDefaultSemanticNote() {
            ToolCall toolCall = new ToolCall("failing_tool", Map.of());
            ToolResult result = ToolResult.error(toolCall, "Connection timeout");

            assertThat(result.isError()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
            assertThat(result.result()).isEqualTo("Connection timeout");
            assertThat(result.errorDescription()).isEqualTo("Execution of tool 'failing_tool' failed: Connection timeout");
        }

        @Test
        @DisplayName("Given ToolCall, error message, and explicit note, when error factory invoked, then preserves custom semantic note")
        void factoryError_preservesCustomSemanticNote() {
            ToolCall toolCall = new ToolCall("failing_tool", Map.of());
            ToolResult result = ToolResult.error(toolCall, "Connection timeout", "Downstream MCP service unreachable");

            assertThat(result.isError()).isTrue();
            assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
            assertThat(result.result()).isEqualTo("Connection timeout");
            assertThat(result.errorDescription()).isEqualTo("Downstream MCP service unreachable");
        }
    }

    // =========================================================================
    // 2. Invalid input & validation
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & validation")
    class InvalidInput {

        @Test
        @DisplayName("Given null ToolCall, when constructed, then throws NullPointerException")
        void constructor_withNullToolCall_throwsNullPointerException() {
            assertThatThrownBy(() -> new ToolResult(null, "some result", ToolResult.Status.SUCCESS))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("toolCall must not be null");
        }

        @Test
        @DisplayName("Given null status, when constructed, then throws NullPointerException")
        void constructor_withNullStatus_throwsNullPointerException() {
            ToolCall toolCall = new ToolCall("test_tool", Map.of());
            assertThatThrownBy(() -> new ToolResult(toolCall, "some result", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("status must not be null");
        }

        @Test
        @DisplayName("Given SUCCESS status with non-null errorDescription, when constructed, then forces errorDescription to null")
        void constructor_withSuccessStatusAndErrorDescription_forcesErrorDescriptionToNull() {
            ToolCall toolCall = new ToolCall("test_tool", Map.of());
            ToolResult result = new ToolResult(toolCall, "Success text", ToolResult.Status.SUCCESS, "Unexpected note");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.errorDescription()).isNull();
        }
    }

    // =========================================================================
    // 3. Edge cases & serialization
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given null result text, when constructed with ERROR status, then defaults to empty string and generates fallback note")
        void constructor_withNullResult_defaultsToEmptyStringAndGeneratesFallbackNote() {
            ToolCall toolCall = new ToolCall("test_tool", Map.of());
            ToolResult result = new ToolResult(toolCall, null, ToolResult.Status.ERROR);

            assertThat(result.result()).isEmpty();
            assertThat(result.errorDescription()).isEqualTo("Execution of tool 'test_tool' encountered an error.");
        }

        @Test
        @DisplayName("Given blank errorDescription, when constructed with DENIED status, then falls back to default semantic note")
        void constructor_withBlankErrorDescription_fallsBackToDefaultSemanticNote() {
            ToolCall toolCall = new ToolCall("test_tool", Map.of());
            ToolResult result = new ToolResult(toolCall, "Forbidden", ToolResult.Status.DENIED, "   ");

            assertThat(result.errorDescription()).isEqualTo("Execution of tool 'test_tool' was denied: Forbidden");
        }

        @Test
        @DisplayName("Given successful ToolResult, when serialized to JSON, then omits error-description")
        void jsonSerialization_omitsErrorDescriptionOnSuccess() throws Exception {
            ToolCall toolCall = new ToolCall("call_99", "check_inventory", Map.of("sku", "SKU-1"));
            ToolResult original = ToolResult.success(toolCall, "In stock: 5");

            String json = objectMapper.writeValueAsString(original);
            assertThat(json).doesNotContain("error-description");
            assertThat(json).doesNotContain("error_description");
        }

        @Test
        @DisplayName("Given failing ToolResult, when serialized to JSON, then writes error-description")
        void jsonSerialization_writesErrorDescriptionOnFailure() throws Exception {
            ToolCall toolCall = new ToolCall("call_99", "check_inventory", Map.of("sku", "SKU-1"));
            ToolResult original = ToolResult.error(toolCall, "Timeout", "Downstream error");

            String json = objectMapper.writeValueAsString(original);
            assertThat(json).contains("\"error-description\":\"Downstream error\"");
        }

        @Test
        @DisplayName("Given JSON with error-description or alias, when deserialized, then correctly parses errorDescription")
        void jsonDeserialization_supportsAliases() throws Exception {
            String jsonKebab = """
                    {
                        "toolCall": {"name": "test_tool", "args": {}},
                        "result": "Fail",
                        "status": "ERROR",
                        "error-description": "Kebab error"
                    }
                    """;
            ToolResult resultKebab = objectMapper.readValue(jsonKebab, ToolResult.class);
            assertThat(resultKebab.errorDescription()).isEqualTo("Kebab error");

            String jsonSnake = """
                    {
                        "toolCall": {"name": "test_tool", "args": {}},
                        "result": "Fail",
                        "status": "ERROR",
                        "error_description": "Snake error"
                    }
                    """;
            ToolResult resultSnake = objectMapper.readValue(jsonSnake, ToolResult.class);
            assertThat(resultSnake.errorDescription()).isEqualTo("Snake error");
        }
    }
}
