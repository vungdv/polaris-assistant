package vn.danang.polaris.assistant.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP leg of the OpenAI-compatible client: {@code POST {base-url}/chat/completions}. Every failure (transport
 * error, timeout, non-2xx) becomes a {@link ModelUnavailableException} carrying the failed {@link ModelCall} and
 * any {@code Retry-After}; response bodies of failures are not kept or logged. The API key is only ever sent as
 * a Bearer header, and only when non-blank.
 */
class OpenAiTransport {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTransport.class);

    private final HttpClient httpClient;

    OpenAiTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** @return the 2xx response body */
    String postChatCompletions(String baseUrl, String apiKey, String model, String body, Duration timeout) {
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            request = builder.build();
        } catch (RuntimeException e) {
            log.error("Invalid Qwen client configuration");
            throw new ModelUnavailableException("Invalid Qwen client configuration", e);
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.error("Qwen API error status: {}", response.statusCode());
                ModelProviderException failure = new ModelProviderException(response.statusCode());
                String message = "Qwen returned HTTP " + response.statusCode();
                ModelCall call = ModelCall.failed(model, failure);
                throw GeminiAiModelClient.retryAfter(response)
                        .map(delay -> new ModelUnavailableException(message, failure, call, delay))
                        .orElseGet(() -> new ModelUnavailableException(message, failure, call));
            }
            return response.body();
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Qwen request interrupted");
            throw new ModelUnavailableException("Qwen request interrupted", e, ModelCall.failed(model, e));
        } catch (HttpTimeoutException e) {
            log.error("Qwen request timed out after {}", timeout);
            throw new ModelUnavailableException("Qwen request timed out", e, ModelCall.failed(model, e));
        } catch (IOException e) {
            log.error("Qwen request I/O error: {}", e.getClass().getSimpleName());
            throw new ModelUnavailableException("Qwen request I/O error", e, ModelCall.failed(model, e));
        } catch (RuntimeException e) {
            log.error("Unexpected error in Qwen client", e);
            throw new ModelUnavailableException("Unexpected error in Qwen client", e, ModelCall.failed(model, e));
        }
    }
}
