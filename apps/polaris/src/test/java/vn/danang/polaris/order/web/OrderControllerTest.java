package vn.danang.polaris.order.web;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.entity.Order;
import org.springframework.test.util.ReflectionTestUtils;
import vn.danang.polaris.order.entity.OrderItem;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.order.service.CustomerService;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.order.web.controller.OrderController;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        // Staff callers order for the customer they name; identity rules are covered by CustomerServiceTest
        when(customerService.resolveOrderingCustomerId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(customerService.resolveCustomerScope(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private Order createSampleOrder(String orderNumber, OrderStatus status) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber(orderNumber);
        ReflectionTestUtils.setField(order, "status", status); // fixture for a mocked service; production uses Order methods
        order.setTotalAmount(new BigDecimal("99.90"));
        order.setPlacedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Alice Tran");
        order.setCustomer(customer);

        Product product = new Product();
        product.setId(10L);
        product.setSku("NG-EARBUD-01");
        product.setName("Nova Wireless Earbuds");
        product.setPrice(new BigDecimal("49.95"));

        OrderItem item = new OrderItem();
        item.setId(100L);
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("49.95"));

        order.getItems().add(item);
        return order;
    }

    @Test
    void getStatus_whenFound_shouldReturnOrderResponse() throws Exception {
        Order sample = createSampleOrder("ORD-1002", OrderStatus.CONFIRMED);
        when(orderService.getOrderStatus("ORD-1002")).thenReturn(sample);

        mockMvc.perform(get("/api/v1/orders/ORD-1002/status").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(99.90))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.items[0].productName").value("Nova Wireless Earbuds"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.95))
                .andExpect(jsonPath("$.items[0].subtotal").value(99.90));
    }

    @Test
    void getStatus_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(orderService.getOrderStatus("ORD-9999"))
                .thenThrow(new ResourceNotFoundException("Order not found with order number: ORD-9999"));

        mockMvc.perform(get("/api/v1/orders/ORD-9999/status").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order not found with order number: ORD-9999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getStatus_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancel_whenCancellable_shouldReturnCancelledOrder() throws Exception {
        Order sample = createSampleOrder("ORD-1001", OrderStatus.CANCELLED);
        when(orderService.cancelOrder("ORD-1001")).thenReturn(sample);

        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_whenNotCancellable_shouldReturn409Conflict() throws Exception {
        when(orderService.cancelOrder("ORD-1005"))
                .thenThrow(new IllegalStateException("Order ORD-1005 cannot be cancelled — current status is DELIVERED"));

        mockMvc.perform(post("/api/v1/orders/ORD-1005/cancel").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order State Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Order ORD-1005 cannot be cancelled — current status is DELIVERED"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"));
    }

    @Test
    void cancel_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(orderService.cancelOrder("ORD-9999"))
                .thenThrow(new ResourceNotFoundException("Order not found with order number: ORD-9999"));

        mockMvc.perform(post("/api/v1/orders/ORD-9999/cancel").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order not found with order number: ORD-9999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getOrder_whenFound_shouldReturnOrderResponse() throws Exception {
        Order sample = createSampleOrder("ORD-1002", OrderStatus.CONFIRMED);
        when(orderService.getOrderStatus("ORD-1002")).thenReturn(sample);

        mockMvc.perform(get("/api/v1/orders/ORD-1002").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(99.90))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void getOrder_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(orderService.getOrderStatus("ORD-9999"))
                .thenThrow(new ResourceNotFoundException("Order not found with order number: ORD-9999"));

        mockMvc.perform(get("/api/v1/orders/ORD-9999").with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order not found with order number: ORD-9999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void placeOrder_validRequest_shouldReturn201CreatedAndLocationHeader() throws Exception {
        Order sample = createSampleOrder("ORD-1001", OrderStatus.PLACED);
        when(orderService.place(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new OrderService.Placement(sample, false));

        String json = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-EARBUD-01", "quantity": 2 }
              ]
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/api/v1/orders/ORD-1001"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(99.90));
    }

    @Test
    void placeOrder_withHeaderIdempotencyKey_shouldPassToService() throws Exception {
        Order sample = createSampleOrder("ORD-1001", OrderStatus.PLACED);
        when(orderService.place(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq("idem-key-123")))
                .thenReturn(new OrderService.Placement(sample, false));

        String json = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-EARBUD-01", "quantity": 2 }
              ]
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "idem-key-123")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/api/v1/orders/ORD-1001"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"));
    }

    @Test
    void placeOrder_whenInsufficientStock_shouldReturn400OutOfStockProblemDetail() throws Exception {
        when(orderService.place(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new vn.danang.polaris.web.exception.InsufficientStockException("NG-WATCH-01", 10, 5));

        String json = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-WATCH-01", "quantity": 10 }
              ]
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/out-of-stock"))
                .andExpect(jsonPath("$.detail").value("Insufficient stock for product 'NG-WATCH-01'. Requested: 10, available: 5."))
                .andExpect(jsonPath("$.sku").value("NG-WATCH-01"))
                .andExpect(jsonPath("$.requested_quantity").value(10))
                .andExpect(jsonPath("$.available_quantity").value(5))
                .andExpect(jsonPath("$.remedy").value("Reduce order quantity for 'NG-WATCH-01' to 5 or fewer units."));
    }

    @Test
    void placeOrder_idempotentReplay_shouldReturn200WithOriginalOrderAndNoLocation() throws Exception {
        Order sample = createSampleOrder("ORD-1001", OrderStatus.PLACED);
        when(orderService.place(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq("idem-replay")))
                .thenReturn(new OrderService.Placement(sample, true));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "idem-replay")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [ { "sku": "NG-EARBUD-01", "quantity": 2 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Location"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Location", "/api/v1/orders/ORD-1001"))
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"));
    }

    @Test
    void placeOrder_expectedUnitPrice_isPassedToService() throws Exception {
        Order sample = createSampleOrder("ORD-1001", OrderStatus.PLACED);
        when(orderService.place(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(java.util.List.of(new vn.danang.polaris.order.dto.OrderItemRequest("NG-EARBUD-01", 2, new BigDecimal("49.95")))),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new OrderService.Placement(sample, false));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [ { "sku": "NG-EARBUD-01", "quantity": 2, "expectedUnitPrice": 49.95 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated());
    }

    @Test
    void placeOrder_negativeExpectedUnitPrice_shouldReturn400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [ { "sku": "NG-EARBUD-01", "quantity": 2, "expectedUnitPrice": -1 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"))
                .andExpect(jsonPath("$.invalid_param").value("items[0].expectedUnitPrice"));
    }

    @Test
    void placeOrder_whenPriceChanged_shouldReturn409PriceChangedProblemDetail() throws Exception {
        when(orderService.place(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new vn.danang.polaris.web.exception.PriceChangedException(java.util.List.of(
                        new vn.danang.polaris.web.exception.PriceChangedException.ChangedLine(
                                "NG-EARBUD-01", new BigDecimal("39.90"), new BigDecimal("49.95")))));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [ { "sku": "NG-EARBUD-01", "quantity": 1, "expectedUnitPrice": 39.90 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/price-changed"))
                .andExpect(jsonPath("$.title").value("Price Changed"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.changed_lines", hasSize(1)))
                .andExpect(jsonPath("$.changed_lines[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.changed_lines[0].expected_unit_price").value(39.90))
                .andExpect(jsonPath("$.changed_lines[0].current_unit_price").value(49.95))
                .andExpect(jsonPath("$.remedy").exists());
    }

    @Test
    void placeOrder_whenIdempotencyKeyReused_shouldReturn422ProblemDetail() throws Exception {
        when(orderService.place(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new vn.danang.polaris.web.exception.IdempotencyKeyReusedException());

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "someone-elses-key")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [ { "sku": "NG-EARBUD-01", "quantity": 1 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/idempotency-key-reused"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.invalid_param").value("Idempotency-Key"));
    }

    @Test
    void placeOrder_whenEmptyItems_shouldReturn400ValidationError() throws Exception {
        String json = """
            {
              "customerId": 1,
              "items": []
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"))
                .andExpect(jsonPath("$.invalid_param").value("items"));
    }

    @Test
    void placeOrder_unauthenticated_shouldReturn401() throws Exception {
        String json = """
            {
              "customerId": 1,
              "items": [{ "sku": "NG-EARBUD-01", "quantity": 1 }]
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchOrders_validParams_shouldReturnPagedOrders() throws Exception {
        Order sample = createSampleOrder("ORD-1002", OrderStatus.CONFIRMED);
        org.springframework.data.domain.Page<OrderResponse> page =
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(OrderResponse.from(sample)),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1);

        when(orderService.searchOrders(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(OrderStatus.CONFIRMED), org.mockito.ArgumentMatchers.any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", "1")
                        .param("status", "CONFIRMED")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchOrders_invalidCustomerId_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", "-1")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void searchOrders_invalidSortProperty_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("sort", "unsupportedField,asc")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Sort Property"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-sort"))
                .andExpect(jsonPath("$.invalid_property").value("unsupportedField"));
    }

    @Test
    void searchOrders_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchOrders_missingOrderReadPermission_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }

    @Test
    void placeOrder_missingOrderWritePermission_shouldReturn403() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": 1, \"items\": [{\"sku\": \"NG-EARBUD-01\", \"quantity\": 1}]}")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }

    @Test
    void placeOrder_shopperForAnotherCustomer_shouldReturn403ProblemDetail() throws Exception {
        when(customerService.resolveOrderingCustomerId(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(2L)))
                .thenThrow(new org.springframework.security.access.AccessDeniedException(
                        "Orders can only be placed for the customer account linked to the authenticated user."));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": 2, \"items\": [{\"sku\": \"NG-EARBUD-01\", \"quantity\": 1}]}")
                        .with(JwtMockFactory.shopper("shopper-sub", "alice.tran@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"));

        org.mockito.Mockito.verifyNoInteractions(orderService);
    }

    @Test
    void cancelOrder_missingOrderWritePermission_shouldReturn403() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }
}
