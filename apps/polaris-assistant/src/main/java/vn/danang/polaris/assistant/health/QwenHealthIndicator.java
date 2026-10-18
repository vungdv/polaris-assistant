package vn.danang.polaris.assistant.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.config.QwenProperties;

/**
 * Reports whether the Qwen server (Ollama or any OpenAI-compatible endpoint) is reachable, on
 * {@code /actuator/health} only. It is deliberately not in the readiness group: a local Ollama outage must not
 * restart the pod; chat answers 503 Problem Details while it is down.
 *
 * Present only when {@code polaris.ai.provider=qwen}. Probes {@code GET {base-url}/models} within the provider's
 * {@code timeout-seconds}; a Bearer header is sent only when the API key is non-blank, and the key is never
 * logged or reported. Can be turned off with {@code management.health.qwen.enabled=false}.
 */
@Component("qwen")
@ConditionalOnProperty(name = "polaris.ai.provider", havingValue = "qwen")
@ConditionalOnEnabledHealthIndicator("qwen")
public class QwenHealthIndicator implements HealthIndicator {

    private final QwenProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public QwenHealthIndicator(QwenProperties properties) {
        this(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build());
    }

    QwenHealthIndicator(QwenProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        String baseUrl = properties.getBaseUrl();
        String model = properties.getModel();
        if (baseUrl == null || baseUrl.isBlank()) {
            return Health.down().withDetail("provider", "qwen").withDetail("reason", "not configured").build();
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/models"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .GET();
            String apiKey = properties.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            Health.Builder health = (status >= 200 && status < 300) ? Health.up() : Health.down();
            return health.withDetail("provider", "qwen").withDetail("model", model)
                    .withDetail("httpStatus", status).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("provider", "qwen").withDetail("model", model)
                    .withDetail("error", "interrupted").build();
        } catch (Exception e) {
            // Exception class only: messages can echo the URL.
            return Health.down().withDetail("provider", "qwen").withDetail("model", model)
                    .withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
