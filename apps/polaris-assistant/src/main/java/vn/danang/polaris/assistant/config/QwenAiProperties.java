package vn.danang.polaris.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/** Settings of the local Qwen model served by Ollama, used when {@code polaris.ai.provider} is {@code qwen}. */
@Configuration
@ConfigurationProperties(prefix = "polaris.ai.qwen")
@Getter
@Setter
public class QwenAiProperties {

    private String baseUrl = "http://localhost:11434";
    private String model = "qwen3:4b";
    /** Per-call request timeout; a small local model is slower than a hosted one. */
    private int timeoutSeconds = 60;
}
