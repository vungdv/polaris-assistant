package vn.danang.polaris.assistant.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.security.UserContext;

/**
 * High-performance, stateless HTTP client for invoking Model Context Protocol (MCP) tools
 * on the Polaris Core backend via standard java.net.http.HttpClient.
 *
 * Authentication and Bearer token resolution is decoupled into {@link UserContext}.
 * Direct synchronous HTTP request/response execution completes in milliseconds without
 * long-lived SSE connections or reactive streaming overhead.
 */
@Component
public class HttpPolarisMcpClient implements PolarisMcpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPolarisMcpClient.class);

    private final PolarisMcpProperties properties;
    private final ObjectMapper objectMapper;
    private final JacksonMcpJsonMapper jsonMapper;
    private final UserContext userContext;
    private final HttpClient httpClient;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper, UserContext userContext, ObjectProvider<Tracer> tracerProvider) {
        this(properties, objectMapper, userContext, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getCore().getTimeoutSeconds()))
                .build(), tracerProvider != null ? tracerProvider.getIfAvailable() : null);
    }

    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper, UserContext userContext, HttpClient httpClient, @Nullable Tracer tracer) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // Parse JSON-RPC results with BigDecimal floats so money in structuredContent (e.g. quote_order)
        // never goes through double.
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        this.userContext = userContext;
        this.httpClient = httpClient;
        this.tracer = tracer;
    }

    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper, UserContext userContext, HttpClient httpClient) {
        this(properties, objectMapper, userContext, httpClient, null);
    }

    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper, UserContext userContext) {
        this(properties, objectMapper, userContext, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getCore().getTimeoutSeconds()))
                .build(), (Tracer) null);
    }

    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this(properties, objectMapper, new UserContext(properties), httpClient, null);
    }

    public HttpPolarisMcpClient(PolarisMcpProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new UserContext(properties));
    }

    /**
     * Resolves the Bearer token for authenticating MCP requests by delegating to {@link UserContext}.
     */
    public String resolveBearerToken() {
        return userContext != null ? userContext.resolveBearerToken() : null;
    }

    /**
     * Resolves the target HTTP endpoint for MCP calls.
     * Converts legacy SSE endpoint paths (e.g. /mcp/sse) to direct stateless HTTP endpoints (/mcp).
     */
    String resolveEndpoint() {
        String url = properties.getCore().getUrl();
        if (url == null || url.isBlank()) {
            return "http://localhost:8080/mcp";
        }
        url = url.trim();
        if (url.endsWith("/mcp/sse")) {
            return url.substring(0, url.length() - 4);
        }
        return url;
    }

    /**
     * Compatibility reset hook (stateless HTTP client does not hold long-lived sessions).
     */
    public void resetClient() {
        // No persistent connection state to reset for standard HTTP client
    }

    private void injectTraceParent(HttpRequest.Builder builder) {
        if (this.tracer == null) {
            return;
        }
        Span currentSpan = this.tracer.currentSpan();
        TraceContext context = (currentSpan != null) ? currentSpan.context()
                : (this.tracer.currentTraceContext() != null ? this.tracer.currentTraceContext().context() : null);
        if (context != null && context.traceId() != null && context.spanId() != null) {
            String sampled = (context.sampled() != null && !context.sampled()) ? "00" : "01";
            builder.header("traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-" + sampled);
        }
    }

    private HttpRequest buildJsonRpcRequest(String jsonBody) {
        String endpoint = resolveEndpoint();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(properties.getCore().getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        String token = resolveBearerToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        injectTraceParent(builder);

        return builder.build();
    }

    @Override
    public List<Tool> listAvailableTools() {
        try {
            Map<String, Object> rpcRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", "1",
                    "method", "tools/list",
                    "params", Map.of()
            );
            String jsonBody = objectMapper.writeValueAsString(rpcRequest);
            HttpRequest request = buildJsonRpcRequest(jsonBody);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Failed to list tools from Polaris Core MCP server. HTTP Status: {}, Body: {}",
                        response.statusCode(), response.body());
                return Collections.emptyList();
            }

            JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, response.body());
            if (message instanceof JSONRPCResponse rpcResponse) {
                if (rpcResponse.error() != null) {
                    log.error("MCP server returned error for tools/list: code={}, message={}",
                            rpcResponse.error().code(), rpcResponse.error().message());
                    return Collections.emptyList();
                }
                if (rpcResponse.result() != null) {
                    ListToolsResult listResult = jsonMapper.convertValue(rpcResponse.result(), ListToolsResult.class);
                    return listResult != null && listResult.tools() != null ? listResult.tools() : Collections.emptyList();
                }
            }
            log.warn("Unexpected JSON-RPC response format for tools/list: {}", response.body());
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while listing tools from Polaris Core MCP server: {}", e.getMessage());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to list tools from Polaris Core MCP server: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Map.of());

            Map<String, Object> rpcRequest = Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", "tools/call",
                    "params", params
            );
            String jsonBody = objectMapper.writeValueAsString(rpcRequest);
            HttpRequest request = buildJsonRpcRequest(jsonBody);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("Polaris Core refused MCP tool '{}'. HTTP Status: {}", toolName, response.statusCode());
                return refused(toolName, response.statusCode());
            }
            if (response.statusCode() != 200) {
                log.error("Error executing MCP tool '{}'. HTTP Status: {}, Body: {}",
                        toolName, response.statusCode(), response.body());
                return new CallToolResult(
                        List.of(TextContent.builder("Error executing tool " + toolName + ": HTTP " + response.statusCode()).build()),
                        true,
                        null,
                        Map.of()
                );
            }

            JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, response.body());
            if (message instanceof JSONRPCResponse rpcResponse) {
                if (rpcResponse.error() != null) {
                    String errorMsg = rpcResponse.error().message() != null ? rpcResponse.error().message() : "Unknown MCP error";
                    log.error("MCP server returned error executing tool '{}': code={}, message={}",
                            toolName, rpcResponse.error().code(), errorMsg);
                    return new CallToolResult(
                            List.of(TextContent.builder("Error executing tool " + toolName + ": " + errorMsg).build()),
                            true,
                            null,
                            Map.of()
                    );
                }
                if (rpcResponse.result() != null) {
                    return jsonMapper.convertValue(rpcResponse.result(), CallToolResult.class);
                }
            }
            return new CallToolResult(
                    List.of(TextContent.builder("Error executing tool " + toolName + ": Invalid JSON-RPC response").build()),
                    true,
                    null,
                    Map.of()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while executing MCP tool '{}': {}", toolName, e.getMessage());
            return new CallToolResult(
                    List.of(TextContent.builder("Execution of tool " + toolName + " was interrupted").build()),
                    true,
                    null,
                    Map.of()
            );
        } catch (Exception e) {
            log.error("Error executing MCP tool '{}': {}", toolName, e.getMessage());
            return new CallToolResult(
                    List.of(TextContent.builder("Error executing tool " + toolName + ": " + e.getMessage()).build()),
                    true,
                    null,
                    Map.of()
            );
        }
    }

    /**
     * A tool call the MCP endpoint refused with 401/403, as an error result carrying an RFC 7807 problem
     * (the same shape Polaris Core uses for its own tool-level refusals).
     */
    private static CallToolResult refused(String toolName, int status) {
        String title = status == 401 ? "Unauthorized" : "Forbidden";
        String detail = "Polaris Core refused tool " + toolName + ": HTTP " + status + " " + title;
        Map<String, Object> problem = Map.of("type", "about:blank", "title", title, "status", status, "detail", detail);
        return new CallToolResult(List.of(TextContent.builder(detail).build()), true, problem, Map.of());
    }
}
