package vn.danang.polaris.assistant.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;

class ProviderRoutingModelClientTest {

    private final AssistantModelClient gemini = mock(AssistantModelClient.class);
    private final AssistantModelClient qwen = mock(AssistantModelClient.class);

    @Test
    @DisplayName("provider 'qwen' (any case) routes every call to Qwen only")
    void routes_to_qwen() {
        ModelRequestContext context = ModelRequestContext.empty();
        ModelResponse reply = new ModelResponse("from qwen");
        when(qwen.generateResponse(List.of(), List.of(), context)).thenReturn(reply);

        AssistantModelClient routed = ProviderRoutingModelClient.select(" Qwen ", gemini, qwen);

        assertThat(routed.generateResponse(List.of(), List.of(), context)).isSameAs(reply);
        verify(qwen).generateResponse(List.of(), List.of(), context);
        verifyNoInteractions(gemini);
    }

    @Test
    @DisplayName("provider 'gemini' routes to Gemini only")
    void routes_to_gemini() {
        assertThat(ProviderRoutingModelClient.select("gemini", gemini, qwen)).isSameAs(gemini);
    }

    @Test
    @DisplayName("an unknown or missing provider fails fast")
    void unknown_provider() {
        assertThatThrownBy(() -> ProviderRoutingModelClient.select("gpt", gemini, qwen))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gpt");
        assertThatThrownBy(() -> ProviderRoutingModelClient.select(null, gemini, qwen))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
