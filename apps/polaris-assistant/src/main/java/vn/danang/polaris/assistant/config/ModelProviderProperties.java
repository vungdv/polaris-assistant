package vn.danang.polaris.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Selects which model provider serves the assistant ({@code polaris.ai.provider}). Provider-specific settings
 * live in their own blocks (for example {@link GeminiProperties}); this class reads only the selector.
 */
@Configuration
@ConfigurationProperties(prefix = "polaris.ai")
@Getter
@Setter
public class ModelProviderProperties {

    /** {@code gemini} (default) or, once registered, {@code qwen}. */
    private String provider = "gemini";
}
