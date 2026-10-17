package vn.danang.polaris.assistant.ai.routing;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.QwenAiModelClient;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.resilience.ResilientGeminiModelClient;

/**
 * The {@link AssistantModelClient} the application injects: picks the model once, at startup, from
 * {@code polaris.ai.provider} ({@code gemini} or {@code qwen}) and delegates every call to it. An unknown
 * provider fails startup rather than the first chat.
 * <p>
 * Gemini is reached through {@link ResilientGeminiModelClient} (circuit breaker and retry); Qwen is a local
 * server and is called directly.
 */
@Component
@Primary
public class ProviderRoutingModelClient implements AssistantModelClient {

    static final String GEMINI = "gemini";
    static final String QWEN = "qwen";

    private final AssistantModelClient delegate;

    @Autowired
    public ProviderRoutingModelClient(AssistantAiProperties properties, ResilientGeminiModelClient gemini,
            QwenAiModelClient qwen) {
        this.delegate = select(properties.getProvider(), gemini, qwen);
    }

    static AssistantModelClient select(String provider, AssistantModelClient gemini, AssistantModelClient qwen) {
        String name = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case GEMINI -> gemini;
            case QWEN -> qwen;
            default -> throw new IllegalArgumentException(
                    "Unknown polaris.ai.provider '" + provider + "'; expected '" + GEMINI + "' or '" + QWEN + "'");
        };
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return delegate.chat(messages);
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return delegate.generateResponse(messages, tools);
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools,
            ModelRequestContext context) {
        return delegate.generateResponse(messages, tools, context);
    }
}
