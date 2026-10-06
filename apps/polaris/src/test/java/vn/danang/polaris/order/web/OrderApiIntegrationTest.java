package vn.danang.polaris.order.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
public class OrderApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // Test fixture reset (rolled back with the test); production code changes status only via Order methods
        jdbcTemplate.update("UPDATE orders SET status = 'PLACED' WHERE order_number = 'ORD-1001'");
    }

    @Test
    void getStatus_seededOrder_shouldReturnConfirmedOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Autowired
    private vn.danang.polaris.catalog.repository.ProductRepository productRepository;

    @Test
    void placeOrder_multiItem_shouldDeductStockAndReturn201() throws Exception {
        int initialEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        int initialChargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();

        String payload = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-EARBUD-01", "quantity": 1 },
                { "sku": "NG-CHARGER-01", "quantity": 2 }
              ]
            }
            """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.orderNumber").exists())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.totalAmount").value(99.70))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.items").isArray());

        int finalEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        int finalChargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(finalEarbudStock).isEqualTo(initialEarbudStock - 1);
        org.assertj.core.api.Assertions.assertThat(finalChargerStock).isEqualTo(initialChargerStock - 2);
    }

    @Test
    void placeOrder_insufficientStock_shouldReturn400OutOfStockAndNotDeductStock() throws Exception {
        int initialWatchStock = productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty();
        long initialOrderCount = orderRepository.count();

        String payload = String.format("""
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-WATCH-01", "quantity": %d }
              ]
            }
            """, initialWatchStock + 10);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/out-of-stock"))
                .andExpect(jsonPath("$.sku").value("NG-WATCH-01"))
                .andExpect(jsonPath("$.available_quantity").value(initialWatchStock))
                .andExpect(jsonPath("$.remedy").exists());

        int finalWatchStock = productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty();
        long finalOrderCount = orderRepository.count();
        org.assertj.core.api.Assertions.assertThat(finalWatchStock).isEqualTo(initialWatchStock);
        org.assertj.core.api.Assertions.assertThat(finalOrderCount).isEqualTo(initialOrderCount);
    }

    @Test
    void placeOrder_idempotentRetry_shouldReturnSameOrderWithoutDuplicateDeduction() throws Exception {
        int initialEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();

        String payload = """
            {
              "customerId": 1,
              "items": [
                { "sku": "NG-EARBUD-01", "quantity": 1 }
              ],
              "idempotencyKey": "idem-key-scenario-3"
            }
            """;

        // First attempt
        String response1 = mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        int afterFirstStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(afterFirstStock).isEqualTo(initialEarbudStock - 1);

        com.fasterxml.jackson.databind.JsonNode rootNode1 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response1);
        String orderNumber1 = rootNode1.get("orderNumber").asText();

        // Second attempt with same idempotency key: a replay, so 200 rather than 201
        String response2 = mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode rootNode2 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response2);
        String orderNumber2 = rootNode2.get("orderNumber").asText();

        org.assertj.core.api.Assertions.assertThat(orderNumber2).isEqualTo(orderNumber1);

        int afterSecondStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(afterSecondStock).isEqualTo(afterFirstStock);
    }

    @Test
    void getOrder_byOrderNumber_shouldReturnFullDetails() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.totalAmount").value(89.90))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void searchOrders_byCustomerId_shouldReturnPaginatedOrders() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .param("customerId", "1")
                        .param("page", "0")
                        .param("size", "10")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void cancel_placedOrder_shouldTransitionToCancelledAndRestoreStock() throws Exception {
        int earbudStockBefore = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();

        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        int earbudStockAfter = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        org.assertj.core.api.Assertions.assertThat(earbudStockAfter).isEqualTo(earbudStockBefore + 1);
    }

    @Test
    void cancel_deliveredOrder_shouldReturn409ConflictProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1005/cancel")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order State Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"))
                .andExpect(jsonPath("$.allowed_states_for_action").isArray())
                .andExpect(jsonPath("$.remedy").exists());
    }

    @Test
    void getStatus_missingOrder_shouldReturn404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-9999/status")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void unauthenticatedRequest_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void distributedTracingPropagation_shouldReturnTraceIdInHeader() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    // --- Shopper identity binding (PRD-003 FR-10, anti-IDOR) ---

    /** Keycloak user ID of shopper alice.tran, linked to seeded customer 1 (Alice Tran) by V12. */
    private static final String ALICE_SUBJECT = "3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b01";

    private static final String SINGLE_ITEM = "\"items\": [{ \"sku\": \"NG-CHARGER-01\", \"quantity\": 1 }]";

    @Test
    void placeOrder_shopperWithoutCustomerId_shouldOrderForOwnCustomer() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Alice Tran"));
    }

    @Test
    void placeOrder_shopperWithOwnCustomerId_shouldReturn201() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Alice Tran"));
    }

    @Test
    void placeOrder_shopperForAnotherCustomer_shouldReturn403AndNotDeductStock() throws Exception {
        int initialStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();
        long initialOrderCount = orderRepository.count();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 2, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"));

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isEqualTo(initialOrderCount);
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty())
                .isEqualTo(initialStock);
    }

    @Test
    void placeOrder_unlinkedShopper_shouldReturn404ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.shopper("unknown-subject", "nobody@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void placeOrder_staffForAnyCustomer_shouldReturn201() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 2, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Ben Nguyen"));
    }

    @Test
    void placeOrder_staffWithoutCustomerId_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"));
    }

    // --- Shopper order ownership on read / list / cancel ---

    /** Keycloak user ID of shopper ben.nguyen, linked to seeded customer 2 (Ben Nguyen) by V12. */
    private static final String BEN_SUBJECT = "3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b02";

    @Test
    void getOrder_shopperOwnOrder_shouldReturn200() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-1002"));
    }

    @Test
    void getOrderAndStatus_shopperOtherCustomersOrder_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/orders/ORD-1002")
                        .with(JwtMockFactory.shopper(BEN_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"));
        mockMvc.perform(get("/api/v1/orders/ORD-1002/status")
                        .with(JwtMockFactory.shopper(BEN_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"));
    }

    @Test
    void searchOrders_shopperWithoutCustomerId_shouldOnlyReturnOwnOrders() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .with(JwtMockFactory.shopper(BEN_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orderNumber", org.hamcrest.Matchers.hasItems("ORD-1003", "ORD-1004")))
                .andExpect(jsonPath("$.content[*].customerName", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Ben Nguyen"))));
    }

    @Test
    void searchOrders_shopperForAnotherCustomer_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("customerId", "1")
                        .with(JwtMockFactory.shopper(BEN_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"));
    }

    @Test
    void cancel_shopperOtherCustomersOrder_shouldReturn403AndKeepStatus() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel")
                        .with(JwtMockFactory.shopper(BEN_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"));

        org.assertj.core.api.Assertions.assertThat(orderRepository.findByOrderNumber("ORD-1001").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PLACED);
    }

    @Test
    void cancel_shopperOwnOrder_shouldReturn200() throws Exception {
        mockMvc.perform(post("/api/v1/orders/ORD-1001/cancel")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // --- Price guard, idempotency hardening, order numbers (plan S4: G6, G7, G10) ---

    private static String orderWithExpectedPrice(String sku, int quantity, String expectedUnitPrice) {
        return String.format("""
            { "customerId": 1, "items": [ { "sku": "%s", "quantity": %d, "expectedUnitPrice": %s } ] }
            """, sku, quantity, expectedUnitPrice);
    }

    @Test
    void placeOrder_expectedUnitPriceMatchesLivePrice_shouldReturn201() throws Exception {
        // Seeded NG-EARBUD-01 costs 49.90; a different scale (49.9) is still the same price
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(orderWithExpectedPrice("NG-EARBUD-01", 2, "49.9"))
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(99.80));
    }

    @Test
    void placeOrder_priceChangedSinceConfirmation_shouldReturn409AndChangeNothing() throws Exception {
        int initialEarbudStock = productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty();
        int initialChargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();
        long initialOrderCount = orderRepository.count();

        // NG-CHARGER-01 still matches (24.90); NG-EARBUD-01 was confirmed at 39.90 but now costs 49.90
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "customerId": 1,
                              "items": [
                                { "sku": "NG-CHARGER-01", "quantity": 1, "expectedUnitPrice": 24.90 },
                                { "sku": "NG-EARBUD-01", "quantity": 1, "expectedUnitPrice": 39.90 }
                              ]
                            }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/price-changed"))
                .andExpect(jsonPath("$.title").value("Price Changed"))
                .andExpect(jsonPath("$.changed_lines.length()").value(1))
                .andExpect(jsonPath("$.changed_lines[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.changed_lines[0].expected_unit_price").value(39.90))
                .andExpect(jsonPath("$.changed_lines[0].current_unit_price").value(49.90));

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isEqualTo(initialOrderCount);
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-EARBUD-01").orElseThrow().getStockQty())
                .isEqualTo(initialEarbudStock);
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty())
                .isEqualTo(initialChargerStock);
    }

    @Test
    void placeOrder_idempotencyKeyReusedByAnotherShopper_shouldReturn422AndNotLeakOrder() throws Exception {
        String key = "idem-key-owned-by-alice";
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isCreated());
        long orderCount = orderRepository.count();

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{" + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.shopper(BEN_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/idempotency-key-reused"))
                .andExpect(jsonPath("$.orderNumber").doesNotExist())
                .andExpect(jsonPath("$.customerName").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isEqualTo(orderCount);
    }

    @Test
    void placeOrder_idempotencyKeyReusedByStaffForAnotherCustomer_shouldReturn422() throws Exception {
        String key = "idem-key-staff-customer-1";
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 2, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/idempotency-key-reused"));
    }

    @Test
    void placeOrder_repeatedSkuLines_areMergedAndCheckedAgainstStockAsOne() throws Exception {
        int stock = productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty();
        int half = stock / 2 + 1;

        // Each line alone fits the stock, together they exceed it: nothing may be ordered
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            { "customerId": 1, "items": [
                                { "sku": "NG-WATCH-01", "quantity": %d },
                                { "sku": "ng-watch-01", "quantity": %d } ] }
                            """, half, half))
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/out-of-stock"))
                .andExpect(jsonPath("$.requested_quantity").value(half * 2))
                .andExpect(jsonPath("$.available_quantity").value(stock));
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty())
                .isEqualTo(stock);

        // Within stock: one merged line with the summed quantity
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [
                                { "sku": "NG-WATCH-01", "quantity": 1 },
                                { "sku": "NG-WATCH-01", "quantity": 2 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty())
                .isEqualTo(stock - 3);
    }

    @Test
    void placeOrder_orderNumbersComeFromTheDatabaseSequence() throws Exception {
        String first = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("orderNumber").asText();
        String second = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("orderNumber").asText();

        org.assertj.core.api.Assertions.assertThat(first).matches("ORD-\\d{6,}");
        org.assertj.core.api.Assertions.assertThat(second).matches("ORD-\\d{6,}");
        long firstValue = Long.parseLong(first.substring(4));
        long secondValue = Long.parseLong(second.substring(4));
        org.assertj.core.api.Assertions.assertThat(secondValue).isGreaterThan(firstValue);
        // The sequence starts above every seeded number (ORD-1001..ORD-1006), so it can never re-issue one
        org.assertj.core.api.Assertions.assertThat(firstValue).isGreaterThan(1006L);
    }

    @Test
    void placeOrder_inactiveProduct_shouldReturn409ProductInactiveAndChangeNothing() throws Exception {
        var speaker = productRepository.findBySku("NG-SPEAKER-01").orElseThrow();
        speaker.setIsActive(false);
        productRepository.saveAndFlush(speaker);
        int speakerStock = speaker.getStockQty();
        int chargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();
        long initialOrderCount = orderRepository.count();

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                            { "customerId": 1, "items": [
                                { "sku": "NG-CHARGER-01", "quantity": 1 },
                                { "sku": "NG-SPEAKER-01", "quantity": 1 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/product-inactive"))
                .andExpect(jsonPath("$.title").value("Product Inactive"))
                .andExpect(jsonPath("$.inactive_skus.length()").value(1))
                .andExpect(jsonPath("$.inactive_skus[0]").value("NG-SPEAKER-01"));

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isEqualTo(initialOrderCount);
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-SPEAKER-01").orElseThrow().getStockQty())
                .isEqualTo(speakerStock);
        org.assertj.core.api.Assertions.assertThat(productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty())
                .isEqualTo(chargerStock);
    }

    @Test
    void placeOrder_idempotencyKeyTooLong_shouldReturn400AndCreateNothing() throws Exception {
        long initialOrderCount = orderRepository.count();

        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "k".repeat(101))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key must be at most 100 characters"));

        // Exactly 100 characters fits orders.idempotency_key
        mockMvc.perform(post("/api/v1/orders")
                        .header("Idempotency-Key", "k".repeat(100))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, " + SINGLE_ITEM + "}")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isCreated());

        org.assertj.core.api.Assertions.assertThat(orderRepository.count()).isEqualTo(initialOrderCount + 1);
    }
}
