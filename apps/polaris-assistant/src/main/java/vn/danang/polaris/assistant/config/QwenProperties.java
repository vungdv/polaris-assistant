package vn.danang.polaris.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the Qwen provider ({@code polaris.ai.qwen.*}): any server that speaks the OpenAI Chat Completions
 * API, verified on Ollama. {@code baseUrl} includes the API version path, for example
 * {@code http://localhost:11434/v1}.
 */
@Configuration
@ConfigurationProperties(prefix = "polaris.ai.qwen")
@Getter
@Setter
public class QwenProperties {

    /** Sent as {@code Authorization: Bearer} only when non-blank; Ollama needs none. */
    private String apiKey = "";
    private String model = "qwen3:4b";
    /** Required when {@code polaris.ai.provider=qwen}. */
    private String baseUrl = "";
    // Duplicated from GeminiProperties on purpose so each provider can be tuned alone; a test keeps them equal.
    private String systemPrompt = "You are Polaris Assistant, a friendly and helpful AI assistant for the Polaris store. "
            + "Help users answer questions and navigate the store politely and concisely. "
            + "Only state product details, prices and stock levels that come from tool results in this conversation; "
            + "never guess or invent them, and say so when you don't have the data. "
            + "Never say that an order was placed, changed or cancelled unless a tool result in this conversation confirmed it.";
    /**
     * Per-call request timeout. Larger than Gemini's: measured on qwen3:4b, one call took up to 88 s because the
     * model spends thousands of reasoning tokens even on a trivial prompt.
     */
    private int timeoutSeconds = 120;
    /** Time budget for a whole chat turn; a tool turn is at least two model calls (measured 4 s + 23 s). */
    private Duration turnDeadline = Duration.ofSeconds(180);
}
