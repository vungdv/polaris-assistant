package vn.danang.polaris.mcp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.service.ProductService;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.entity.Order;
import org.springframework.test.util.ReflectionTestUtils;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class McpTracingTest {

    @Mock
    private ProductService productService;

    @Mock
    private OrderService orderService;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private Tracer.SpanInScope spanInScope;

    @Mock
    private ObjectProvider<Tracer> tracerProvider;

    private ProductMcpTools productToolsWithTracer;
    private ProductMcpTools productToolsNoTracer;
    private OrderMcpTools orderToolsWithTracer;
    private OrderMcpTools orderToolsNoTracer;

    @BeforeEach
    void setUp() {
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);

        productToolsWithTracer = new ProductMcpTools(productService, tracerProvider);
        productToolsNoTracer = new ProductMcpTools(productService);

        orderToolsWithTracer = new OrderMcpTools(orderService, tracerProvider);
        orderToolsNoTracer = new OrderMcpTools(orderService);
    }

    private void mockTracerForSpan(String spanName) {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(spanName)).thenReturn(span);
        when(span.tag(anyString(), anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(spanInScope);
    }

    @Test
    @DisplayName("ProductMcpTools.searchAvailableProducts creates span with catalog metadata")
    void productTools_searchAvailableProducts_createsSpan() {
        mockTracerForSpan("mcp.server.tool_call search_available_products");

        ProductResponse p = new ProductResponse(
                1L, "SKU-01", "Test Product", "Desc", "Electronics",
                BigDecimal.valueOf(29.99), 10, true, true, Instant.now(), null, null
        );
        when(productService.searchProducts(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p)));

        McpSchema.CallToolResult result = productToolsWithTracer.searchAvailableProducts(Map.of("query", "Test"));

        assertThat(result.isError()).isFalse();
        verify(tracer).nextSpan();
        verify(span).name("mcp.server.tool_call search_available_products");
        verify(span).tag("gen_ai.tool.name", "search_available_products");
        verify(span, never()).tag(eq("mcp.tool.name"), anyString());
        verify(span).tag("mcp.server", "polaris-mcp");
        verify(span).tag("mcp.category", "catalog");
        verify(span).start();
        verify(spanInScope).close();
        verify(span).end();
        verify(span, never()).tag(eq("error"), anyString());
    }

    @Test
    @DisplayName("ProductMcpTools.searchAvailableProducts tags error when validation fails")
    void productTools_searchAvailableProducts_tagsErrorOnFailure() {
        mockTracerForSpan("mcp.server.tool_call search_available_products");

        McpSchema.CallToolResult result = productToolsWithTracer.searchAvailableProducts(Map.of("page", -1));

        assertThat(result.isError()).isTrue();
        verify(span).tag("error", "true");
        verify(span).end();
    }

    @Test
    @DisplayName("ProductMcpTools.getProductBySku creates span with catalog metadata")
    void productTools_getProductBySku_createsSpan() {
        mockTracerForSpan("mcp.server.tool_call get_product_by_sku");

        ProductResponse p = new ProductResponse(
                1L, "SKU-01", "Test Product", "Desc", "Electronics",
                BigDecimal.valueOf(29.99), 10, true, true, Instant.now(), null, null
        );
        when(productService.getProductBySku("SKU-01")).thenReturn(p);

        McpSchema.CallToolResult result = productToolsWithTracer.getProductBySku(Map.of("sku", "SKU-01"));

        assertThat(result.isError()).isFalse();
        verify(span).name("mcp.server.tool_call get_product_by_sku");
        verify(span).tag("gen_ai.tool.name", "get_product_by_sku");
        verify(span, never()).tag(eq("mcp.tool.name"), anyString());
        verify(span).tag("mcp.server", "polaris-mcp");
        verify(span).tag("mcp.category", "catalog");
        verify(span).start();
        verify(span).end();
    }

    @Test
    @DisplayName("ProductMcpTools.getProductBySku tags error when SKU is not found")
    void productTools_getProductBySku_notFoundTagsError() {
        mockTracerForSpan("mcp.server.tool_call get_product_by_sku");
        when(productService.getProductBySku("NONEXISTENT")).thenThrow(new ResourceNotFoundException("Not found"));

        McpSchema.CallToolResult result = productToolsWithTracer.getProductBySku(Map.of("sku", "NONEXISTENT"));

        assertThat(result.isError()).isTrue();
        verify(span).tag("error", "true");
        verify(span).end();
    }

    @Test
    @DisplayName("ProductMcpTools executes cleanly when Tracer is null")
    void productTools_withoutTracer_executesCleanly() {
        ProductResponse p = new ProductResponse(
                1L, "SKU-01", "Test Product", "Desc", "Electronics",
                BigDecimal.valueOf(29.99), 10, true, true, Instant.now(), null, null
        );
        when(productService.getProductBySku("SKU-01")).thenReturn(p);

        McpSchema.CallToolResult result = productToolsNoTracer.getProductBySku(Map.of("sku", "SKU-01"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("OrderMcpTools.placeOrder creates span with order metadata")
    void orderTools_placeOrder_createsSpan() {
        mockTracerForSpan("mcp.server.tool_call place_order");

        Order order = new Order();
        order.setId(100L);
        order.setOrderNumber("ORD-2001");
        ReflectionTestUtils.setField(order, "status", OrderStatus.PLACED); // fixture for a mocked service; production uses Order methods
        order.setTotalAmount(BigDecimal.valueOf(50.00));
        order.setPlacedAt(Instant.now());
        Customer customer = new Customer();
        customer.setFullName("Alice Tran");
        order.setCustomer(customer);

        when(orderService.place(anyLong(), anyList(), any())).thenReturn(new OrderService.Placement(order, false));

        Map<String, Object> item = Map.of("sku", "SKU-01", "quantity", 2);
        Map<String, Object> args = Map.of("customer_id", 1, "items", List.of(item));

        McpSchema.CallToolResult result = orderToolsWithTracer.placeOrder(args,
                new org.springframework.security.authentication.TestingAuthenticationToken("staff", null, "ROLE_ADMIN", "PERM_order.write"));

        assertThat(result.isError()).isFalse();
        verify(tracer).nextSpan();
        verify(span).name("mcp.server.tool_call place_order");
        verify(span).tag("gen_ai.tool.name", "place_order");
        verify(span, never()).tag(eq("mcp.tool.name"), anyString());
        verify(span).tag("mcp.server", "polaris-mcp");
        verify(span).tag("mcp.category", "order");
        verify(span).start();
        verify(spanInScope).close();
        verify(span).end();
    }

    @Test
    @DisplayName("OrderMcpTools.placeOrder tags error on invalid arguments")
    void orderTools_placeOrder_tagsErrorOnMissingCustomer() {
        mockTracerForSpan("mcp.server.tool_call place_order");

        McpSchema.CallToolResult result = orderToolsWithTracer.placeOrder(Map.of("items", List.of()));

        assertThat(result.isError()).isTrue();
        verify(span).tag("error", "true");
        verify(span).end();
    }

    @Test
    @DisplayName("OrderMcpTools.getOrderStatus creates span with order metadata")
    void orderTools_getOrderStatus_createsSpan() {
        mockTracerForSpan("mcp.server.tool_call get_order_status");

        Order order = new Order();
        order.setId(100L);
        order.setOrderNumber("ORD-1001");
        ReflectionTestUtils.setField(order, "status", OrderStatus.PLACED); // fixture for a mocked service; production uses Order methods
        order.setTotalAmount(BigDecimal.valueOf(50.00));
        order.setPlacedAt(Instant.now());
        Customer customer = new Customer();
        customer.setFullName("Alice Tran");
        order.setCustomer(customer);

        when(orderService.getOrderStatus("ORD-1001")).thenReturn(order);

        McpSchema.CallToolResult result = orderToolsWithTracer.getOrderStatus(Map.of("order_number", "ORD-1001"));

        assertThat(result.isError()).isFalse();
        verify(span).name("mcp.server.tool_call get_order_status");
        verify(span).tag("gen_ai.tool.name", "get_order_status");
        verify(span, never()).tag(eq("mcp.tool.name"), anyString());
        verify(span).tag("mcp.server", "polaris-mcp");
        verify(span).tag("mcp.category", "order");
        verify(span).start();
        verify(span).end();
    }

    @Test
    @DisplayName("OrderMcpTools.cancelOrder tags error when cancellation fails")
    void orderTools_cancelOrder_tagsErrorOnConflict() {
        mockTracerForSpan("mcp.server.tool_call cancel_order");
        when(orderService.cancelOrder("ORD-1005")).thenThrow(new IllegalStateException("Order ORD-1005 cannot be cancelled"));

        McpSchema.CallToolResult result = orderToolsWithTracer.cancelOrder(Map.of("order_number", "ORD-1005"));

        assertThat(result.isError()).isTrue();
        verify(span).tag("error", "true");
        verify(span).end();
    }

    @Test
    @DisplayName("OrderMcpTools executes cleanly when Tracer is null")
    void orderTools_withoutTracer_executesCleanly() {
        Order order = new Order();
        order.setId(100L);
        order.setOrderNumber("ORD-1001");
        ReflectionTestUtils.setField(order, "status", OrderStatus.PLACED); // fixture for a mocked service; production uses Order methods
        order.setTotalAmount(BigDecimal.valueOf(50.00));
        order.setPlacedAt(Instant.now());
        Customer customer = new Customer();
        customer.setFullName("Alice Tran");
        order.setCustomer(customer);

        when(orderService.getOrderStatus("ORD-1001")).thenReturn(order);

        McpSchema.CallToolResult result = orderToolsNoTracer.getOrderStatus(Map.of("order_number", "ORD-1001"));
        assertThat(result.isError()).isFalse();
    }
}
