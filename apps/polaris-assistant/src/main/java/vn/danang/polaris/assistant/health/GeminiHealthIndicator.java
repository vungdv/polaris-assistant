package vn.danang.polaris.assistant.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.config.AssistantAiProperties;

/**
 * Reports whether the Gemini API is reachable, on {@code /actuator/health} only (not readiness).
 * Gemini is required for chat; while it is down, chat answers 503 Problem Details.
 *
 * Probes with a GET on the model metadata endpoint ({@code /v1beta/models/{model}}) rather
 * than a {@code generateContent} call: it exercises the same host, TLS and API key as a real
 * request but returns model info instead of generated content, so it costs no tokens and stays
 * cheap enough to run on every probe. A short, fixed timeout is used here regardless of
 * {@code polaris.ai.timeout-seconds}, so a slow/unreachable Gemini never stalls a
 * {@code /actuator/health} call.
 *
 * Can be turned off with {@code management.health.gemini.enabled=false}, e.g. in test suites
 * that boot the full application context without real AI credentials or network egress.
 */
@Component("gemini")
@ConditionalOnEnabledHealthIndicator("gemini")
public class GeminiHealthIndicator implements HealthIndicator {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final AssistantAiProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public GeminiHealthIndicator(AssistantAiProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build());
    }

    GeminiHealthIndicator(AssistantAiProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        String baseUrl = properties.getBaseUrl();
        String model = properties.getModel();
        String apiKey = properties.getApiKey();

        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            return Health.down().withDetail("reason", "not configured").build();
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Health.down().withDetail("reason", "missing API key").build();
        }

        String url = baseUrl.replaceAll("/+$", "") + "/v1beta/models/" + model;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", apiKey)
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            Health.Builder builder = (status >= 200 && status < 300) ? Health.up() : Health.down();
            return builder.withDetail("model", model).withDetail("httpStatus", status).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("model", model).build();
        }
    }
}
