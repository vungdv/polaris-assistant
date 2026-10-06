package vn.danang.polaris.assistant.tools;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.security.UserContext;

class HttpPolarisMcpClientTest {

    private PolarisMcpProperties properties;
    private ObjectMapper objectMapper;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockHttpResponse;
    private HttpPolarisMcpClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new PolarisMcpProperties();
        properties.getCore().setUrl("http://localhost:8080/mcp");
        properties.getCore().setTimeoutSeconds(5);

        objectMapper = new ObjectMapper();
        mockHttpClient = mock(HttpClient.class);
        mockHttpResponse = (HttpResponse<String>) mock(HttpResponse.class);

        client = new HttpPolarisMcpClient(properties, objectMapper, mockHttpClient);
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // 1. Happy path — standard MCP list and call operations
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given successful tools/list response, when queried, then parses and returns registered tools")
        void lists_available_tools_from_mcp_endpoint() throws Exception {
            String jsonResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "result": {
                    "tools": [
                      {
                        "name": "search_available_products",
                        "description": "Search products in catalog",
                        "inputSchema": { "type": "object" }
                      }
                    ]
                  }
                }
                """;
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(jsonResponse);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            List<Tool> tools = client.listAvailableTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("search_available_products");
            assertThat(tools.get(0).description()).isEqualTo("Search products in catalog");
        }

        @Test
        @DisplayName("Given successful tools/call response, when executed, then returns parsed CallToolResult")
        void calls_tool_and_returns_content() throws Exception {
            String jsonResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": "call-1",
                  "result": {
                    "content": [
                      {
                        "type": "text",
                        "text": "Found 2 products matching query"
                      }
                    ],
                    "isError": false
                  }
                }
                """;
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(jsonResponse);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            CallToolResult result = client.callTool("search_available_products", Map.of("query", "phone"));

            assertThat(result.isError()).isFalse();
            assertThat(result.content()).hasSize(1);
            assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("Found 2 products matching query");
        }

        @Test
        @DisplayName("Given authenticated caller in SecurityContext, when sending request, then injects Bearer token in Authorization header")
        void injects_bearer_token_from_security_context() throws Exception {
            Jwt jwt = Jwt.withTokenValue("ey-user-token-abc")
                    .header("alg", "none")
                    .claim("sub", "user-42")
                    .build();
            JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}");
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            try {
                client.listAvailableTools();

                ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
                verify(mockHttpClient).send(captor.capture(), any());

                HttpRequest sentRequest = captor.getValue();
                assertThat(sentRequest.headers().firstValue("Authorization"))
                        .hasValue("Bearer ey-user-token-abc");
                assertThat(sentRequest.headers().firstValue("Content-Type"))
                        .hasValue("application/json");
                assertThat(sentRequest.headers().firstValue("Accept").orElse(""))
                        .contains("application/json");
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Test
        @DisplayName("Given active span in tracer, when calling tool, then injects W3C traceparent header")
        void injects_w3c_traceparent_header_when_tracer_is_present() throws Exception {
            Tracer mockTracer = mock(Tracer.class);
            Span mockSpan = mock(Span.class);
            TraceContext mockContext = mock(TraceContext.class);

            when(mockTracer.currentSpan()).thenReturn(mockSpan);
            when(mockSpan.context()).thenReturn(mockContext);
            when(mockContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
            when(mockContext.spanId()).thenReturn("00f067aa0ba902b7");
            when(mockContext.sampled()).thenReturn(true);

            HttpPolarisMcpClient tracedClient = new HttpPolarisMcpClient(
                    properties, objectMapper, new UserContext(properties), mockHttpClient, mockTracer);

            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("""
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "result": {
                    "content": [{"type": "text", "text": "Success"}],
                    "isError": false
                  }
                }
                """);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            tracedClient.callTool("search_available_products", Map.of("query", "test"));

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttpClient).send(captor.capture(), any());

            HttpRequest sentRequest = captor.getValue();
            assertThat(sentRequest.headers().firstValue("traceparent"))
                    .hasValue("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        }
    }

    // =========================================================================
    // 2. Invalid input & protocol error handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & protocol error handling")
    class InvalidInput {

        @Test
        @DisplayName("Given HTTP 500 error status, when listing tools, then returns empty list safely")
        void returns_empty_list_when_tools_list_encounters_http_error() throws Exception {
            when(mockHttpResponse.statusCode()).thenReturn(500);
            when(mockHttpResponse.body()).thenReturn("Internal Server Error");
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            List<Tool> tools = client.listAvailableTools();

            assertThat(tools).isEmpty();
        }

        @Test
        @DisplayName("Given JSON-RPC error response, when listing tools, then returns empty list safely")
        void returns_empty_list_when_tools_list_encounters_json_rpc_error() throws Exception {
            String errorResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "error": {
                    "code": -32601,
                    "message": "Method not found"
                  }
                }
                """;
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(errorResponse);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            List<Tool> tools = client.listAvailableTools();

            assertThat(tools).isEmpty();
        }

        @Test
        @DisplayName("Given HTTP 503 status, when calling tool, then returns CallToolResult with isError=true")
        void returns_error_result_when_tool_call_encounters_http_error() throws Exception {
            when(mockHttpResponse.statusCode()).thenReturn(503);
            when(mockHttpResponse.body()).thenReturn("Service Unavailable");
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            CallToolResult result = client.callTool("search_available_products", Map.of("query", "phone"));

            assertThat(result.isError()).isTrue();
            assertThat(((TextContent) result.content().get(0)).text()).contains("HTTP 503");
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403})
        @DisplayName("Given HTTP 401/403, when calling tool, then returns an error result carrying the status as an RFC 7807 problem")
        void returns_problem_result_when_tool_call_is_refused(int status) throws Exception {
            when(mockHttpResponse.statusCode()).thenReturn(status);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            CallToolResult result = client.callTool("get_order_status", Map.of("order_id", "ORD-1"));

            assertThat(result.isError()).isTrue();
            assertThat(((TextContent) result.content().get(0)).text()).contains("HTTP " + status);
            assertThat(result.structuredContent()).isInstanceOfSatisfying(Map.class,
                    problem -> assertThat(problem).containsEntry("status", status).containsEntry("type", "about:blank"));
        }

        @Test
        @DisplayName("Given JSON-RPC error payload, when calling tool, then returns error result with error message")
        void returns_error_result_when_tool_call_encounters_json_rpc_error() throws Exception {
            String errorResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": "call-1",
                  "error": {
                    "code": -32602,
                    "message": "Invalid params"
                  }
                }
                """;
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(errorResponse);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            CallToolResult result = client.callTool("search_available_products", Map.of("bad", 123));

            assertThat(result.isError()).isTrue();
            assertThat(((TextContent) result.content().get(0)).text()).contains("Invalid params");
        }
    }

    // =========================================================================
    // 3. Edge cases — network timeouts, URL normalization, fallbacks
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given unreachable destination, when executing, then handles connection exception gracefully")
        void handles_offline_connection_failure_gracefully() {
            PolarisMcpProperties offlineProps = new PolarisMcpProperties();
            offlineProps.getCore().setUrl("http://127.0.0.1:59999/mcp");
            offlineProps.getCore().setTimeoutSeconds(1);

            HttpPolarisMcpClient realClient = new HttpPolarisMcpClient(offlineProps, objectMapper);

            assertThat(realClient.listAvailableTools()).isEmpty();
            CallToolResult result = realClient.callTool("search_available_products", Map.of("query", "test"));
            assertThat(result.isError()).isTrue();
        }

        @Test
        @DisplayName("Given URL ending with legacy /mcp/sse, when resolving endpoint, then normalizes to /mcp")
        void normalizes_legacy_sse_endpoint_to_standard_http() {
            properties.getCore().setUrl("http://localhost:8080/mcp/sse");

            assertThat(client.resolveEndpoint()).isEqualTo("http://localhost:8080/mcp");
        }

        @Test
        @DisplayName("Given client without tracer, when calling tool, then does not inject traceparent header")
        void omits_traceparent_header_when_tracer_is_absent() throws Exception {
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("""
                {
                  "jsonrpc": "2.0",
                  "id": "1",
                  "result": {
                    "content": [{"type": "text", "text": "Success"}],
                    "isError": false
                  }
                }
                """);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            client.callTool("search_available_products", Map.of("query", "test"));

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockHttpClient).send(captor.capture(), any());
            assertThat(captor.getValue().headers().firstValue("traceparent")).isEmpty();
        }

        @Test
        @DisplayName("Given fallback authToken in properties, when unauthenticated, then resolves configured static token")
        void resolves_fallback_auth_token_from_properties() {
            properties.getCore().setAuthToken("fallback-token-xyz");
            HttpPolarisMcpClient clientWithFallback = new HttpPolarisMcpClient(properties, objectMapper, mockHttpClient);

            assertThat(clientWithFallback.resolveBearerToken()).isEqualTo("fallback-token-xyz");
        }

        @Test
        @DisplayName("Given unauthenticated context and no fallback, when resolving token, then returns null")
        void returns_null_when_unauthenticated_and_no_fallback_configured() {
            properties.getCore().setAuthToken(null);
            HttpPolarisMcpClient clientNoAuth = new HttpPolarisMcpClient(properties, objectMapper, mockHttpClient);

            assertThat(clientNoAuth.resolveBearerToken()).isNull();
        }

        @Test
        @DisplayName("Given resetClient invoked, then completes safely without exceptions")
        void resets_client_without_exceptions() {
            assertThatCode(() -> client.resetClient()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Given ObjectProvider constructor, when instantiated, then extracts Tracer bean")
        @SuppressWarnings("unchecked")
        void extracts_tracer_from_object_provider() {
            Tracer mockTracer = mock(Tracer.class);
            ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(mockTracer);

            HttpPolarisMcpClient clientFromProvider = new HttpPolarisMcpClient(
                    properties, objectMapper, new UserContext(properties), provider);

            assertThat(clientFromProvider).isNotNull();
            verify(provider).getIfAvailable();
        }
    }

    @Nested
    @DisplayName("Structured content money precision")
    class StructuredContentPrecision {

        @Test
        @DisplayName("Given structuredContent with decimal prices, when a tool is called, then numbers arrive as exact BigDecimal")
        @SuppressWarnings("unchecked")
        void decimals_in_structured_content_are_big_decimal() throws Exception {
            String jsonResponse = """
                {
                  "jsonrpc": "2.0",
                  "id": "q-1",
                  "result": {
                    "content": [{"type": "text", "text": "Quote"}],
                    "structuredContent": {"orderable": true, "totalAmount": 0.30,
                      "lines": [{"sku": "A", "unitPrice": 0.10, "requestedQuantity": 3, "lineTotal": 0.30}]},
                    "isError": false
                  }
                }
                """;
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn(jsonResponse);
            doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

            CallToolResult result = client.callTool("quote_order", Map.of());

            Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured.get("totalAmount")).isEqualTo(new java.math.BigDecimal("0.30"));
            Map<String, Object> line = ((List<Map<String, Object>>) structured.get("lines")).get(0);
            assertThat(line.get("unitPrice")).isEqualTo(new java.math.BigDecimal("0.10"));
            assertThat(line.get("requestedQuantity")).isEqualTo(3);
        }
    }
}
