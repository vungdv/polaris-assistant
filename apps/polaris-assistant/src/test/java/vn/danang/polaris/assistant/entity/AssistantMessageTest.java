package vn.danang.polaris.assistant.entity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AssistantMessageTest {

    // =========================================================================
    // 1. Happy path — factory construction and role mapping
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given user message text, when of(message) is invoked, then creates message with USER role and current timestamp")
        void of_withMessage_createsUserMessage() {
            String text = "Tell me about Polaris";

            AssistantMessage msg = AssistantMessage.of(text);

            assertThat(msg.getRole()).isEqualTo(MessageRole.USER);
            assertThat(msg.getContent()).isEqualTo(text);
            assertThat(msg.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Given message, ASSISTANT role, and thought signature, when of(...) is invoked, then assigns role and signature")
        void of_withAssistantRoleAndThoughtSignature_assignsRoleAndSignature() {
            String text = "Here are your orders";
            String signature = "sig_thought_token_123";

            AssistantMessage msg = AssistantMessage.of(text, MessageRole.ASSISTANT, signature);

            assertThat(msg.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(msg.getContent()).isEqualTo(text);
            assertThat(msg.getThoughtSignature()).isEqualTo(signature);
            assertThat(msg.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Given message, TOOL role, and null signature, when of(...) is invoked, then assigns TOOL role")
        void of_withToolRole_assignsToolRole() {
            String text = "Tool output: success";

            AssistantMessage msg = AssistantMessage.of(text, MessageRole.TOOL, null);

            assertThat(msg.getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(msg.getContent()).isEqualTo(text);
            assertThat(msg.getThoughtSignature()).isNull();
        }
    }

    // =========================================================================
    // 2. Invalid input & defaults
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & defaults")
    class InvalidInput {

        @Test
        @DisplayName("Given null role in of(...), then defaults role to USER")
        void of_withNullRole_defaultsToUserRole() {
            AssistantMessage msg = AssistantMessage.of("Test message", null, "sig-123");

            assertThat(msg.getRole()).isEqualTo(MessageRole.USER);
        }
    }

    // =========================================================================
    // 3. Edge cases — entity attributes and lifecycle
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given full property mutators, then correctly stores widget and tool call attributes")
        void manages_widget_and_tool_call_attributes() {
            AssistantMessage msg = new AssistantMessage();
            Instant created = Instant.parse("2026-09-20T12:00:00Z");

            msg.setId(101L);
            msg.setRole(MessageRole.ASSISTANT);
            msg.setContent("Products widget");
            msg.setWidgetType("PRODUCT_CAROUSEL");
            msg.setWidgetPayload("{\"items\":[1,2,3]}");
            msg.setToolCallId("call_search_456");
            msg.setThoughtSignature("sig_xyz");
            msg.setCreatedAt(created);

            assertThat(msg.getId()).isEqualTo(101L);
            assertThat(msg.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(msg.getContent()).isEqualTo("Products widget");
            assertThat(msg.getWidgetType()).isEqualTo("PRODUCT_CAROUSEL");
            assertThat(msg.getWidgetPayload()).isEqualTo("{\"items\":[1,2,3]}");
            assertThat(msg.getToolCallId()).isEqualTo("call_search_456");
            assertThat(msg.getThoughtSignature()).isEqualTo("sig_xyz");
            assertThat(msg.getCreatedAt()).isEqualTo(created);
        }

        @Test
        @DisplayName("Given newly instantiated message, then createdAt is initialized automatically")
        void initializes_createdAt_by_default() {
            AssistantMessage msg = new AssistantMessage();

            assertThat(msg.getCreatedAt()).isNotNull();
        }
    }
}
