package vn.danang.polaris.assistant.health;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;

class TypeSafeHealthIndicatorTest {

    private AssistantTypeSafeProperties properties;
    private HttpClient httpClient;
    private TypeSafeHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = new AssistantTypeSafeProperties();
        httpClient = mock(HttpClient.class);
        indicator = new TypeSafeHealthIndicator(properties, httpClient);
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given TypeSafe responds to a HEAD at the base URL, when health is checked, then reports up regardless of status")
        @SuppressWarnings("unchecked")
        void reports_up_on_any_response() throws Exception {
            HttpResponse<Void> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(404);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("httpStatus", 404);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any());
            HttpRequest request = captor.getValue();
            assertThat(request.uri().toString()).isEqualTo(properties.getBaseUrl());
            assertThat(request.method()).isEqualTo("HEAD");
        }
    }

    @Nested
    @DisplayName("2. Failure handling")
    class FailureHandling {

        @Test
        @DisplayName("Given the request throws, when health is checked, then reports down")
        void reports_down_on_transport_failure() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("timeout"));

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("Given no base URL is configured, when health is checked, then reports down without a network call")
        void reports_down_when_base_url_missing() {
            properties.setBaseUrl("");

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("reason", "not configured");
        }
    }
}
