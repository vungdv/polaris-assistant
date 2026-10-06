package vn.danang.polaris.assistant.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;

/**
 * Reports whether the TypeSafe API is reachable, on {@code /actuator/health} only (not readiness).
 * TypeSafe is optional; while it is down, intent classification falls back to {@code general.conversation}.
 *
 * TypeSafe exposes no free introspection/ping endpoint, so unlike {@link GeminiHealthIndicator}
 * this only proves network reachability: it sends a HEAD to the configured base URL and treats
 * any HTTP response (even 404/405) as evidence that DNS, TCP and TLS to the host all work. It
 * does not call {@code /v1/systemone}, since that endpoint performs a billed model judgment.
 * Only a transport-level failure (timeout, connection refused, unknown host) counts as down.
 * A short, fixed timeout is used regardless of {@code polaris.typesafe.timeout-seconds}, so a
 * slow/unreachable TypeSafe never stalls a {@code /actuator/health} call.
 *
 * Can be turned off with {@code management.health.typeSafe.enabled=false}, e.g. in test suites
 * that boot the full application context without real network egress.
 */
@Component("typeSafe")
@ConditionalOnEnabledHealthIndicator("typeSafe")
public class TypeSafeHealthIndicator implements HealthIndicator {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final AssistantTypeSafeProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public TypeSafeHealthIndicator(AssistantTypeSafeProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build());
    }

    TypeSafeHealthIndicator(AssistantTypeSafeProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return Health.down().withDetail("reason", "not configured").build();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(PROBE_TIMEOUT)
                    .method("HEAD", BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return Health.up()
                    .withDetail("httpStatus", response.statusCode())
                    .withDetail("check", "reachability-only")
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
