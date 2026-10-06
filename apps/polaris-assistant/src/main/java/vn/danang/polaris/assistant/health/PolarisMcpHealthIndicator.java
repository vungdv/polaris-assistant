package vn.danang.polaris.assistant.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.tools.PolarisMcpProperties;

/**
 * Reports whether the Polaris Core MCP server is reachable, for the readiness probe.
 *
 * Probes with a {@code tools/list} JSON-RPC call: it's the cheapest real MCP operation (just
 * returns tool definitions, invokes nothing) and confirms the whole path — network, HTTP layer
 * and JSON-RPC handling — the same way {@link vn.danang.polaris.assistant.tools.HttpPolarisMcpClient}
 * does for real traffic. It uses its own short, fixed timeout, independent of
 * {@code polaris.mcp.core.timeout-seconds}, so a slow/unreachable MCP server never stalls the
 * container healthcheck past its own timeout.
 *
 * If {@code polaris.mcp.core.enabled} is {@code false}, the integration was intentionally turned
 * off, so this reports up rather than failing readiness for a dependency the app isn't using.
 * Can also be turned off entirely with {@code management.health.polarisMcp.enabled=false}, e.g.
 * in test suites that boot the full application context without a running Polaris Core.
 */
@Component("polarisMcp")
@ConditionalOnEnabledHealthIndicator("polarisMcp")
public class PolarisMcpHealthIndicator implements HealthIndicator {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final PolarisMcpProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public PolarisMcpHealthIndicator(PolarisMcpProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build());
    }

    PolarisMcpHealthIndicator(PolarisMcpProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        if (!properties.getCore().isEnabled()) {
            return Health.up().withDetail("reason", "disabled").build();
        }

        String url = resolveEndpoint();
        if (url == null || url.isBlank()) {
            return Health.down().withDetail("reason", "not configured").build();
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", "health",
                    "method", "tools/list",
                    "params", Map.of()
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .timeout(PROBE_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // Both 200 or 401 (unauthorized) means the connection is worked.
            if (response.statusCode() != 200 && response.statusCode() != 401) {
                return Health.down().withDetail("httpStatus", response.statusCode()).build();
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("error")) {
                return Health.down().withDetail("rpcError", root.path("error").path("message").asText()).build();
            }
            return Health.up().build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }

    /**
     * Mirrors {@code HttpPolarisMcpClient.resolveEndpoint()}: converts legacy SSE endpoint paths
     * (e.g. {@code /mcp/sse}) to the direct stateless HTTP endpoint ({@code /mcp}).
     */
    private String resolveEndpoint() {
        String url = properties.getCore().getUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        url = url.trim();
        if (url.endsWith("/mcp/sse")) {
            return url.substring(0, url.length() - 4);
        }
        return url;
    }
}
