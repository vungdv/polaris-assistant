package vn.danang.polaris.assistant.health;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import vn.danang.polaris.assistant.config.AssistantAiProperties;

class GeminiHealthIndicatorTest {

    private AssistantAiProperties properties;
    private HttpClient httpClient;
    private GeminiHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = new AssistantAiProperties();
        properties.setApiKey("test-gemini-key");
        httpClient = mock(HttpClient.class);
        indicator = new GeminiHealthIndicator(properties, httpClient);
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given Gemini responds 200 to the model metadata GET, when health is checked, then reports up")
        @SuppressWarnings("unchecked")
        void reports_up_on_2xx() throws Exception {
            HttpResponse<Void> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("model", properties.getModel());

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            org.mockito.Mockito.verify(httpClient).send(captor.capture(), any());
            HttpRequest request = captor.getValue();
            assertThat(request.uri().toString())
                    .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/" + properties.getModel());
            assertThat(request.headers().firstValue("x-goog-api-key")).hasValue("test-gemini-key");
            assertThat(request.method()).isEqualTo("GET");
        }
    }

    @Nested
    @DisplayName("2. Failure handling")
    class FailureHandling {

        @Test
        @DisplayName("Given Gemini responds with a 4xx/5xx status, when health is checked, then reports down")
        @SuppressWarnings("unchecked")
        void reports_down_on_error_status() throws Exception {
            HttpResponse<Void> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(503);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("httpStatus", 503);
        }

        @Test
        @DisplayName("Given the request throws, when health is checked, then reports down with the exception")
        void reports_down_on_transport_failure() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("Given no API key is configured, when health is checked, then reports down without a network call")
        void reports_down_when_api_key_missing() {
            properties.setApiKey("");

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("reason", "missing API key");
        }
    }
}
