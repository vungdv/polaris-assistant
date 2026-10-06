package vn.danang.polaris.assistant.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link HttpCurrentCustomerClient}: calls Order Management's {@code GET /api/v1/customers/me}
 * with the caller's own token and maps 200 / 404 / other statuses.
 */
class HttpCurrentCustomerClientTest {

    private HttpClient httpClient;
    private HttpResponse<String> response;
    private HttpCurrentCustomerClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        PolarisCoreApiProperties properties = new PolarisCoreApiProperties();
        properties.setBaseUrl("http://polaris:8080/");
        httpClient = mock(HttpClient.class);
        response = (HttpResponse<String>) mock(HttpResponse.class);
        client = new HttpCurrentCustomerClient(properties, new ObjectMapper(), httpClient, null);
    }

    @Test
    @DisplayName("Given a linked customer, when resolved, then GETs /api/v1/customers/me with the caller's bearer token and returns its id")
    void resolves_linked_customer() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"id\":7,\"fullName\":\"Alice Tran\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(client.findCurrentCustomer("user-token")).contains(new CustomerRef(7L, "Alice Tran"));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), any());
        assertThat(request.getValue().uri().toString()).isEqualTo("http://polaris:8080/api/v1/customers/me");
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer user-token");
    }

    @Test
    @DisplayName("Given Order Management's not-linked problem (404), when resolved, then empty")
    void not_linked_is_empty() throws Exception {
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn("{\"type\":\"https://polaris.local/errors/not-found\",\"title\":\"Resource Not Found\","
                + "\"status\":404,\"detail\":\"No customer is linked to the authenticated user.\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(client.findCurrentCustomer("user-token")).isEmpty();
    }

    @Test
    @DisplayName("Given a 404 that is not Order Management's problem (e.g. gateway page), when resolved, then CustomerLookupException")
    void foreign_404_is_a_failure() throws Exception {
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn("<html><body>404 Not Found</body></html>");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomer("user-token")).isInstanceOf(CustomerLookupException.class);
    }

    @Test
    @DisplayName("Given a 404 problem of another type, when resolved, then CustomerLookupException")
    void other_problem_404_is_a_failure() throws Exception {
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn("{\"type\":\"about:blank\",\"status\":404}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomer("user-token")).isInstanceOf(CustomerLookupException.class);
    }

    @Test
    @DisplayName("Given staff names a customer, when its name is looked up, then GET /customers/{id} returns fullName; failures yield empty")
    void customer_name_is_best_effort() throws Exception {
        when(response.statusCode()).thenReturn(200, 403);
        when(response.body()).thenReturn("{\"id\":42,\"fullName\":\"Bob Le\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThat(client.findCustomerName(42L, "staff-token")).contains("Bob Le");
        assertThat(client.findCustomerName(42L, "staff-token")).isEmpty();

        doThrow(new IOException("down")).when(httpClient).send(any(HttpRequest.class), any());
        assertThat(client.findCustomerName(42L, "staff-token")).isEmpty();
    }

    @Test
    @DisplayName("Given a rejected token (401), when resolved, then CustomerLookupException")
    void rejected_token_fails() throws Exception {
        when(response.statusCode()).thenReturn(401);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomer("user-token"))
                .isInstanceOf(CustomerLookupException.class).message().doesNotContain("401");
    }

    @Test
    @DisplayName("Given Order Management is unreachable, when resolved, then CustomerLookupException")
    void unreachable_fails() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomer("user-token"))
                .isInstanceOf(CustomerLookupException.class).message().doesNotContain("connection refused");
    }

    @Test
    @DisplayName("Given a payload without id, when resolved, then CustomerLookupException")
    void payload_without_id_fails() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"fullName\":\"Alice\"}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any());

        assertThatThrownBy(() -> client.findCurrentCustomer("user-token")).isInstanceOf(CustomerLookupException.class);
    }

    @Test
    @DisplayName("Given no caller token, when resolved, then refused without any request")
    void blank_token_is_refused() {
        assertThatThrownBy(() -> client.findCurrentCustomer(" ")).isInstanceOf(CustomerLookupException.class);
        verifyNoInteractions(httpClient);
    }
}
