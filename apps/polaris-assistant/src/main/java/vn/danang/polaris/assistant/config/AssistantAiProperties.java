package vn.danang.polaris.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "polaris.ai")
@Getter
@Setter
public class AssistantAiProperties {

    private String provider = "gemini";
    private String apiKey = "";
    private String model = "gemini-3.6-flash";
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private String systemPrompt = "You are Polaris Assistant, a friendly and helpful AI assistant for the Polaris store. "
            + "Help users answer questions and navigate the store politely and concisely. "
            + "Only state product details, prices and stock levels that come from tool results in this conversation; "
            + "never guess or invent them, and say so when you don't have the data. "
            + "Never say that an order was placed, changed or cancelled unless a tool result in this conversation confirmed it.";
    /** Per-call Gemini request timeout. */
    private int timeoutSeconds = 30;
    /** Time budget for a whole chat turn; each Gemini call waits at most what is left of it. */
    private Duration turnDeadline = Duration.ofSeconds(25);
}
