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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import vn.danang.polaris.assistant.tools.PolarisMcpProperties;

class PolarisMcpHealthIndicatorTest {

    private PolarisMcpProperties properties;
    private HttpClient httpClient;
    private PolarisMcpHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = new PolarisMcpProperties();
        httpClient = mock(HttpClient.class);
        indicator = new PolarisMcpHealthIndicator(properties, new ObjectMapper().findAndRegisterModules(), httpClient);
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given the MCP server answers tools/list with no error, when health is checked, then reports up")
        @SuppressWarnings("unchecked")
        void reports_up_on_successful_tools_list() throws Exception {
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":\"health\",\"result\":{\"tools\":[]}}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any());
            assertThat(captor.getValue().uri().toString()).isEqualTo(properties.getCore().getUrl());
        }

        @Test
        @DisplayName("Given MCP is disabled, when health is checked, then reports up without a network call")
        void reports_up_when_disabled() throws Exception {
            properties.getCore().setEnabled(false);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("reason", "disabled");
        }
    }

    @Nested
    @DisplayName("2. Failure handling")
    class FailureHandling {

        @Test
        @DisplayName("Given the MCP server returns a JSON-RPC error, when health is checked, then reports down")
        @SuppressWarnings("unchecked")
        void reports_down_on_rpc_error() throws Exception {
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":\"health\",\"error\":{\"code\":-32601,\"message\":\"boom\"}}");
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("rpcError", "boom");
        }

        @Test
        @DisplayName("Given the request throws, when health is checked, then reports down")
        void reports_down_on_transport_failure() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }
    }
}
