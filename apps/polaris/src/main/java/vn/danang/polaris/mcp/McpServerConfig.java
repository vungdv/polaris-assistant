package vn.danang.polaris.mcp;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Spring configuration wiring the native Model Context Protocol (MCP) server,
 * HTTP Streamable and Stateless transport servlets, and tool facades into the Polaris runtime.
 */
@Configuration
public class McpServerConfig {

    public static final String MESSAGE_ENDPOINT = "/mcp/message";
    public static final String SSE_ENDPOINT = "/mcp/sse";
    public static final String STREAMABLE_ENDPOINT = "/mcp/sse";
    public static final String STATELESS_ENDPOINT = "/mcp";

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public JacksonMcpJsonMapper jacksonMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider httpServletStreamableServerTransportProvider(JacksonMcpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(STREAMABLE_ENDPOINT)
                .contextExtractor(McpSecurityContext.extractor())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistrationBean(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, STREAMABLE_ENDPOINT);
        registration.setAsyncSupported(true);
        registration.setName("mcpStreamableServlet");
        return registration;
    }

    @Bean
    public McpSyncServer mcpSyncServer(
            HttpServletStreamableServerTransportProvider transport,
            ProductMcpTools productMcpTools,
            OrderMcpTools orderMcpTools,
            OrderQuoteMcpTools orderQuoteMcpTools,
            JacksonMcpJsonMapper jsonMapper) {
        return McpServer.sync(transport)
                .serverInfo("polaris-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(productMcpTools.getSearchProductsTool(jsonMapper), (exchange, request) -> productMcpTools.searchAvailableProducts(request.arguments()))
                .toolCall(productMcpTools.getProductBySkuTool(jsonMapper), (exchange, request) -> productMcpTools.getProductBySku(request.arguments()))
                .toolCall(orderMcpTools.getOrderStatusTool(jsonMapper), (exchange, request) -> orderMcpTools.getOrderStatus(request.arguments()))
                .toolCall(orderMcpTools.getOrderDetailsTool(jsonMapper), (exchange, request) -> orderMcpTools.getOrderDetails(request.arguments()))
                .toolCall(orderMcpTools.getPlaceOrderTool(jsonMapper), (exchange, request) -> orderMcpTools.placeOrder(request.arguments(), McpSecurityContext.authentication(exchange.transportContext())))
                .toolCall(orderMcpTools.getListCustomerOrdersTool(jsonMapper), (exchange, request) -> orderMcpTools.listCustomerOrders(request.arguments()))
                .toolCall(orderMcpTools.getCancelOrderTool(jsonMapper), (exchange, request) -> orderMcpTools.cancelOrder(request.arguments()))
                .toolCall(orderMcpTools.getSearchCustomersByNameTool(jsonMapper), (exchange, request) -> orderMcpTools.searchCustomersByName(request.arguments()))
                .toolCall(orderQuoteMcpTools.getQuoteOrderTool(jsonMapper), (exchange, request) -> orderQuoteMcpTools.quoteOrder(request.arguments()))
                .build();
    }

    @Bean
    public HttpServletStatelessServerTransport httpServletStatelessServerTransport(JacksonMcpJsonMapper jsonMapper) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(jsonMapper)
                .messageEndpoint(STATELESS_ENDPOINT)
                .contextExtractor(McpSecurityContext.extractor())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStatelessServerTransport> statelessMcpServletRegistrationBean(
            HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
                new ServletRegistrationBean<>(transport, STATELESS_ENDPOINT);
        registration.setAsyncSupported(true);
        registration.setName("statelessMcpServlet");
        return registration;
    }

    @Bean
    public McpStatelessSyncServer mcpStatelessSyncServer(
            HttpServletStatelessServerTransport transport,
            ProductMcpTools productMcpTools,
            OrderMcpTools orderMcpTools,
            OrderQuoteMcpTools orderQuoteMcpTools,
            JacksonMcpJsonMapper jsonMapper) {
        return McpServer.sync(transport)
                .serverInfo("polaris-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(productMcpTools.getSearchProductsTool(jsonMapper), (exchange, request) -> productMcpTools.searchAvailableProducts(request.arguments()))
                .toolCall(productMcpTools.getProductBySkuTool(jsonMapper), (exchange, request) -> productMcpTools.getProductBySku(request.arguments()))
                .toolCall(orderMcpTools.getOrderStatusTool(jsonMapper), (exchange, request) -> orderMcpTools.getOrderStatus(request.arguments()))
                .toolCall(orderMcpTools.getOrderDetailsTool(jsonMapper), (exchange, request) -> orderMcpTools.getOrderDetails(request.arguments()))
                .toolCall(orderMcpTools.getPlaceOrderTool(jsonMapper), (context, request) -> orderMcpTools.placeOrder(request.arguments(), McpSecurityContext.authentication(context)))
                .toolCall(orderMcpTools.getListCustomerOrdersTool(jsonMapper), (exchange, request) -> orderMcpTools.listCustomerOrders(request.arguments()))
                .toolCall(orderMcpTools.getCancelOrderTool(jsonMapper), (exchange, request) -> orderMcpTools.cancelOrder(request.arguments()))
                .toolCall(orderMcpTools.getSearchCustomersByNameTool(jsonMapper), (exchange, request) -> orderMcpTools.searchCustomersByName(request.arguments()))
                .toolCall(orderQuoteMcpTools.getQuoteOrderTool(jsonMapper), (exchange, request) -> orderQuoteMcpTools.quoteOrder(request.arguments()))
                .build();
    }
}
