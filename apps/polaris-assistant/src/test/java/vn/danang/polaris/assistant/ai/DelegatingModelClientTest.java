package vn.danang.polaris.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.ModelProviderProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;

class DelegatingModelClientTest {

    private final AssistantModelClient gemini = mock(AssistantModelClient.class);
    private final AssistantModelClient other = mock(AssistantModelClient.class);
    private final List<AssistantMessage> messages = List.of(new AssistantMessage());
    private final List<Tool> tools = List.of();

    private DelegatingModelClient delegatingTo(String provider) {
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProvider(provider);
        return new DelegatingModelClient(properties, Map.of("gemini", gemini, "other", other));
    }

    @Test
    @DisplayName("Given the default provider, when generating, then the Gemini client handles every call shape")
    void delegates_every_call_to_the_selected_provider() {
        DelegatingModelClient client = new DelegatingModelClient(new ModelProviderProperties(),
                Map.of("gemini", gemini, "other", other));
        ModelRequestContext context = ModelRequestContext.empty();
        ModelResponse expected = new ModelResponse("hi", List.of());
        when(gemini.generateResponse(messages, tools, context)).thenReturn(expected);
        when(gemini.generateResponse(messages, tools)).thenReturn(expected);
        when(gemini.chat(messages)).thenReturn("hi");

        assertThat(client.generateResponse(messages, tools, context)).isSameAs(expected);
        assertThat(client.generateResponse(messages, tools)).isSameAs(expected);
        assertThat(client.chat(messages)).isEqualTo("hi");
        verify(other, never()).generateResponse(any(), any(), any());
        verify(other, never()).chat(any());
    }

    @Test
    @DisplayName("Given another registered provider is selected, when generating, then only that provider is called")
    void selects_the_named_provider() {
        ModelResponse expected = new ModelResponse("other", List.of());
        when(other.generateResponse(messages, tools, ModelRequestContext.empty())).thenReturn(expected);

        assertThat(delegatingTo("other").generateResponse(messages, tools, ModelRequestContext.empty()))
                .isSameAs(expected);
        verify(gemini, never()).generateResponse(any(), any(), any());
    }

    @Test
    @DisplayName("When asking for the turn deadline, then the selected provider's value is returned")
    void forwards_the_turn_deadline() {
        when(gemini.turnDeadline()).thenReturn(Duration.ofSeconds(25));
        when(other.turnDeadline()).thenReturn(Duration.ofSeconds(90));

        assertThat(delegatingTo("gemini").turnDeadline()).isEqualTo(Duration.ofSeconds(25));
        assertThat(delegatingTo("other").turnDeadline()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("Given an unknown provider, when constructing, then it fails naming the allowed values")
    void unknown_provider_fails_naming_allowed_values() {
        assertThatThrownBy(() -> delegatingTo("foo")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'foo'").hasMessageContaining("[gemini, other]");
    }
}
