package vn.danang.polaris.assistant.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.config.ModelProviderProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;

/**
 * The {@link AssistantModelClient} the assistant talks to. Picks the provider named by
 * {@code polaris.ai.provider} once, at construction, and forwards every call to that provider's (already
 * guarded) client. It holds no provider configuration and no failover: one provider per deployment.
 */
public class DelegatingModelClient implements AssistantModelClient {

    private final String provider;
    private final AssistantModelClient selected;

    /**
     * @param properties the provider selector
     * @param providers provider clients by provider name, each already wrapped by its circuit breaker
     * @throws IllegalStateException when the selected provider is not registered
     */
    public DelegatingModelClient(ModelProviderProperties properties, Map<String, AssistantModelClient> providers) {
        this.provider = properties.getProvider();
        this.selected = providers.get(provider);
        if (selected == null) {
            throw new IllegalStateException("Unknown polaris.ai.provider '" + provider + "'; allowed values: "
                    + new TreeMap<>(providers).keySet());
        }
    }

    @Override
    public Duration turnDeadline() {
        return selected.turnDeadline();
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return selected.chat(messages);
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return selected.generateResponse(messages, tools);
    }

    @Override
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools,
            ModelRequestContext context) {
        return selected.generateResponse(messages, tools, context);
    }

    @Override
    public String toString() {
        return "DelegatingModelClient[" + provider + "]";
    }
}
