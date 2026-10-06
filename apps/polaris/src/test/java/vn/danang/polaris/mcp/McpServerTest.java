package vn.danang.polaris.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class McpServerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMcpTools productMcpTools;

    @Autowired
    private OrderMcpTools orderMcpTools;

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Autowired
    private HttpServletStreamableServerTransportProvider transport;

    @Autowired
    private McpStatelessSyncServer mcpStatelessSyncServer;

    @Autowired
    private HttpServletStatelessServerTransport statelessTransport;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private vn.danang.polaris.catalog.repository.ProductRepository productRepository;

    /** Keycloak user ID of shopper alice.tran, linked to seeded customer 1 by V12. */
    private static final String ALICE_SUBJECT = "3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b01";

    @BeforeEach
    void setUp() {
        // Test fixture reset (rolled back with the test); production code changes status only via Order methods
        jdbcTemplate.update("UPDATE orders SET status = 'PLACED' WHERE order_number = 'ORD-1001'");
        // Tool calls run as back-office staff unless a test passes a shopper explicitly
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("staff", null, "ROLE_PURCHASE_MANAGEMENT", "PERM_order.write"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication shopper(String subject, String email) {
        Jwt jwt = Jwt.withTokenValue("shopper-token")
                .header("alg", "none")
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", true)
                .build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_shopper", "PERM_order.write"));
    }

    @Nested
    @DisplayName("ProductMcpTools Unit & Integration Tests")
    class ProductToolsTests {

        @Test
        @DisplayName("Verify search_available_products tool schema contract")
        void searchAvailableProducts_schemaContract() {
            McpSchema.Tool tool = productMcpTools.getSearchProductsTool();
            assertThat(tool.name()).isEqualTo("search_available_products");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties).containsKey("query");
            assertThat(properties).containsKey("category");
            assertThat(properties).containsKey("min_price");
            assertThat(properties).containsKey("max_price");
            assertThat(properties).containsKey("available_only");
            assertThat(properties).containsKey("page");
            assertThat(properties).containsKey("size");
            assertThat(properties).containsKey("sort");
        }

        @Test
        @DisplayName("Verify get_product_by_sku tool schema contract")
        void getProductBySku_schemaContract() {
            McpSchema.Tool tool = productMcpTools.getProductBySkuTool();
            assertThat(tool.name()).isEqualTo("get_product_by_sku");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("sku");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("sku");
        }

        @Test
        @DisplayName("search_available_products with default arguments returns seeded products")
        void searchAvailableProducts_defaultArgs() {
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(Map.of());
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).isNotEmpty();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Found ");
            assertThat(text).contains("product(s):");
            assertThat(text).contains("NG-EARBUD-01");
        }

        @Test
        @DisplayName("search_available_products with keyword query filters correctly")
        void searchAvailableProducts_keywordQuery() {
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(Map.of("query", "Earbuds"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Nova Wireless Earbuds");
            assertThat(text).contains("NG-EARBUD-01");
        }

        @Test
        @DisplayName("search_available_products with price range filters correctly")
        void searchAvailableProducts_priceRange() {
            Map<String, Object> args = new HashMap<>();
            args.put("min_price", 30.00);
            args.put("max_price", 50.00);
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("NG-EARBUD-01"); // 49.90
            assertThat(text).contains("NG-SPEAKER-01"); // 39.90
            assertThat(text).doesNotContain("NG-CASE-01"); // 14.90
        }

        @Test
        @DisplayName("search_available_products with sort parameter applies sort order")
        void searchAvailableProducts_withSort() {
            Map<String, Object> args = Map.of("sort", "price,desc");
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Found ");
        }

        @Test
        @DisplayName("search_available_products when no products match returns clean empty message")
        void searchAvailableProducts_emptyResult() {
            Map<String, Object> args = Map.of("query", "DEFINITELY_NON_EXISTENT_PRODUCT_12345");
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).isEqualTo("No products found matching the specified criteria.");
        }

        @Test
        @DisplayName("search_available_products with invalid page number returns error result")
        void searchAvailableProducts_invalidPage() {
            Map<String, Object> args = Map.of("page", -5);
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Error searching products");
        }

        @Test
        @DisplayName("search_available_products with invalid page size returns error result")
        void searchAvailableProducts_invalidSize() {
            Map<String, Object> args = Map.of("size", 500);
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Error searching products");
        }

        @Test
        @DisplayName("search_available_products with unsupported sort property returns error result")
        void searchAvailableProducts_invalidSort() {
            Map<String, Object> args = Map.of("sort", "unsupported_field,asc");
            McpSchema.CallToolResult result = productMcpTools.searchAvailableProducts(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Error searching products");
        }

        @Test
        @DisplayName("get_product_by_sku with valid SKU returns product details and stock status")
        void getProductBySku_validSku() {
            McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of("sku", "NG-EARBUD-01"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Product Details for Nova Wireless Earbuds");
            assertThat(text).contains("- SKU: NG-EARBUD-01");
            assertThat(text).contains("- Price: $49.90");
            assertThat(text).contains("In Stock (120 available)");
        }

        @Test
        @DisplayName("get_product_by_sku with non-existent SKU returns clean error message")
        void getProductBySku_nonExistentSku() {
            McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of("sku", "NONEXISTENT-999"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Product not found with SKU: NONEXISTENT-999");
        }

        @Test
        @DisplayName("get_product_by_sku with missing SKU returns error message")
        void getProductBySku_missingSku() {
            McpSchema.CallToolResult result = productMcpTools.getProductBySku(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'sku' is required.");
        }
    }

    @Nested
    @DisplayName("OrderMcpTools Unit & Integration Tests")
    class OrderToolsTests {

        @Test
        @DisplayName("Verify get_order_status tool schema contract")
        void getOrderStatus_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getOrderStatusTool();
            assertThat(tool.name()).isEqualTo("get_order_status");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("order_number");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("order_number");
        }

        @Test
        @DisplayName("Verify cancel_order tool schema contract")
        void cancelOrder_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getCancelOrderTool();
            assertThat(tool.name()).isEqualTo("cancel_order");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("order_number");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("order_number");
        }

        @Test
        @DisplayName("get_order_status for seeded order returns status, customer, and items")
        void getOrderStatus_seededOrder() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderStatus(Map.of("order_number", "ORD-1002"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order Status for ORD-1002:");
            assertThat(text).contains("- Status: CONFIRMED");
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).contains("- Total Amount: $89.90");
            assertThat(text).contains("- Items");
        }

        @Test
        @DisplayName("get_order_status with non-existent order returns clean error message")
        void getOrderStatus_nonExistentOrder() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderStatus(Map.of("order_number", "ORD-9999"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order not found with order number: ORD-9999");
        }

        @Test
        @DisplayName("get_order_status with missing order_number returns required message")
        void getOrderStatus_missingOrderNumber() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderStatus(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'order_number' is required.");
        }

        @Test
        @DisplayName("cancel_order for PLACED order transitions order to CANCELLED")
        void cancelOrder_placedOrder_success() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of("order_number", "ORD-1001"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order ORD-1001 has been successfully cancelled.");
            assertThat(text).contains("- Status: CANCELLED");
        }

        @Test
        @DisplayName("cancel_order for DELIVERED order returns state conflict error")
        void cancelOrder_deliveredOrder_conflict() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of("order_number", "ORD-1005"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Cannot cancel order: Order ORD-1005 cannot be cancelled — current status is DELIVERED");
        }

        @Test
        @DisplayName("cancel_order for non-existent order returns not found error")
        void cancelOrder_nonExistentOrder() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of("order_number", "ORD-9999"));
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order not found with order number: ORD-9999");
        }

        @Test
        @DisplayName("Verify get_order_details tool schema contract")
        void getOrderDetails_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getOrderDetailsTool();
            assertThat(tool.name()).isEqualTo("get_order_details");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("order_number");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("order_number");
        }

        @Test
        @DisplayName("Verify place_order tool schema contract")
        void placeOrder_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getPlaceOrderTool();
            assertThat(tool.name()).isEqualTo("place_order");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties).containsKey("customer_id");
            assertThat(properties).containsKey("customer_name");
            assertThat(properties).containsKey("items");
            assertThat(properties).containsKey("idempotency_key");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("items");
        }

        @Test
        @DisplayName("Verify search_customers_by_name tool schema contract")
        void searchCustomersByName_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getSearchCustomersByNameTool();
            assertThat(tool.name()).isEqualTo("search_customers_by_name");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull().containsKey("name");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("name");
        }

        @Test
        @DisplayName("Verify list_customer_orders tool schema contract")
        void listCustomerOrders_schemaContract() {
            McpSchema.Tool tool = orderMcpTools.getListCustomerOrdersTool();
            assertThat(tool.name()).isEqualTo("list_customer_orders");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            assertThat(properties).isNotNull();
            assertThat(properties).containsKey("customer_id");
            assertThat(properties).containsKey("status");
            assertThat(properties).containsKey("page");
            assertThat(properties).containsKey("size");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) tool.inputSchema().get("required");
            assertThat(required).isNotNull().contains("customer_id");
        }

        @Test
        @DisplayName("getOrderDetails for seeded order returns status, customer, and items")
        void getOrderDetails_seededOrder() {
            McpSchema.CallToolResult result = orderMcpTools.getOrderDetails(Map.of("order_number", "ORD-1002"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order Status for ORD-1002:");
            assertThat(text).contains("- Status: CONFIRMED");
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).contains("- Assigned Partner: legacy");
            assertThat(text).contains("- Total Amount: $89.90");
        }

        @Test
        @DisplayName("place_order with valid items creates order successfully")
        void placeOrder_success() {
            Map<String, Object> item1 = Map.of("sku", "NG-EARBUD-01", "quantity", 1);
            Map<String, Object> item2 = Map.of("sku", "NG-CHARGER-01", "quantity", 2);
            Map<String, Object> args = Map.of(
                    "customer_id", 1,
                    "items", List.of(item1, item2),
                    "idempotency_key", "mcp-place-test-1"
            );

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order successfully placed!");
            assertThat(text).contains("- Status: PLACED");
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).contains("- Total Amount: $99.70");
            assertThat(text).contains("NG-EARBUD-01");
            assertThat(text).contains("NG-CHARGER-01");
        }

        @Test
        @DisplayName("place_order with customer_name fuzzy match resolves customer and places order")
        void placeOrder_withCustomerName_success() {
            Map<String, Object> item = Map.of("sku", "NG-EARBUD-01", "quantity", 1);
            Map<String, Object> args = Map.of(
                    "customer_name", "Alice Tran",
                    "items", List.of(item),
                    "idempotency_key", "mcp-name-place-test-1"
            );

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order successfully placed!");
            assertThat(text).contains("Alice Tran");
        }

        @Test
        @DisplayName("place_order with non-matching customer_name returns error")
        void placeOrder_withCustomerName_notFound() {
            Map<String, Object> item = Map.of("sku", "NG-EARBUD-01", "quantity", 1);
            Map<String, Object> args = Map.of(
                    "customer_name", "Unknown Person 999",
                    "items", List.of(item)
            );

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("No customer found matching");
        }

        @Test
        @DisplayName("search_customers_by_name with partial name returns candidate list")
        void searchCustomersByName_partial_success() {
            McpSchema.CallToolResult result = orderMcpTools.searchCustomersByName(Map.of("name", "Alice"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Alice Tran");
        }

        @Test
        @DisplayName("search_customers_by_name with non-matching name returns clean message")
        void searchCustomersByName_noMatch() {
            McpSchema.CallToolResult result = orderMcpTools.searchCustomersByName(Map.of("name", "xyz_nonexistent_zzz"));
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("No customers found");
        }

        @Test
        @DisplayName("place_order with insufficient stock returns actionable error remedy")
        void placeOrder_insufficientStock_returnsRemedy() {
            Map<String, Object> item = Map.of("sku", "NG-WATCH-01", "quantity", 9999);
            Map<String, Object> args = Map.of("customer_id", 1, "items", List.of(item));

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Insufficient stock for product 'NG-WATCH-01'");
            assertThat(text).contains("Remedy: Reduce order quantity for 'NG-WATCH-01'");
        }

        @Test
        @DisplayName("place_order with missing customer_id and customer_name returns error")
        void placeOrder_missingCustomerId() {
            Map<String, Object> item = Map.of("sku", "NG-EARBUD-01", "quantity", 1);
            Map<String, Object> args = Map.of("items", List.of(item));

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("customer_id");
        }

        @Test
        @DisplayName("place_order with empty items returns error")
        void placeOrder_emptyItems() {
            Map<String, Object> args = Map.of("customer_id", 1, "items", List.of());

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(args);
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'items' is required and must not be empty.");
        }

        @Test
        @DisplayName("list_customer_orders for customer with orders returns formatted list")
        void listCustomerOrders_seededCustomer() {
            Map<String, Object> args = Map.of("customer_id", 1);
            McpSchema.CallToolResult result = orderMcpTools.listCustomerOrders(args);
            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Found ");
            assertThat(text).contains("order(s) for customer ID 1");
            assertThat(text).contains("ORD-1001");
            assertThat(text).contains("ORD-1002");
        }

        @Test
        @DisplayName("list_customer_orders with missing customer_id returns error")
        void listCustomerOrders_missingCustomerId() {
            McpSchema.CallToolResult result = orderMcpTools.listCustomerOrders(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'customer_id' is required.");
        }

        @Test
        @DisplayName("cancel_order with missing order_number returns required message")
        void cancelOrder_missingOrderNumber() {
            McpSchema.CallToolResult result = orderMcpTools.cancelOrder(Map.of());
            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Parameter 'order_number' is required.");
        }
    }

    @Nested
    @DisplayName("place_order price guard and idempotency (plan S4)")
    class PriceGuardAndIdempotencyTests {

        /** Keycloak user ID of shopper ben.nguyen, linked to seeded customer 2 by V12. */
        private static final String BEN_SUBJECT = "3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b02";

        @SuppressWarnings("unchecked")
        private Map<String, Object> structured(McpSchema.CallToolResult result) {
            assertThat(result.structuredContent()).isInstanceOf(Map.class);
            return (Map<String, Object>) result.structuredContent();
        }

        @Test
        @DisplayName("place_order schema offers an optional expected_unit_price per item")
        void placeOrder_schemaHasExpectedUnitPrice() {
            @SuppressWarnings("unchecked")
            Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) orderMcpTools.getPlaceOrderTool()
                    .inputSchema().get("properties")).get("items");
            @SuppressWarnings("unchecked")
            Map<String, Object> itemSchema = (Map<String, Object>) items.get("items");
            @SuppressWarnings("unchecked")
            Map<String, Object> itemProperties = (Map<String, Object>) itemSchema.get("properties");
            assertThat(itemProperties).containsKey("expected_unit_price");
            assertThat((List<String>) itemSchema.get("required")).containsExactly("sku", "quantity");
        }

        @Test
        @DisplayName("matching expected_unit_price (camelCase alias accepted) places the order")
        void placeOrder_expectedPriceMatches_success() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1,
                    "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1, "expectedUnitPrice", 49.90))));

            assertThat(result.isError()).isFalse();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("Order successfully placed!");
        }

        @Test
        @DisplayName("changed price is a tool error whose structuredContent carries the price-changed problem and changed lines")
        void placeOrder_priceChanged_structuredProblem() {
            long ordersBefore = orderRepository.count();

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1,
                    "items", List.of(
                            Map.of("sku", "NG-EARBUD-01", "quantity", 1, "expected_unit_price", "39.90"),
                            Map.of("sku", "NG-CHARGER-01", "quantity", 1, "expected_unit_price", 24.90))));

            assertThat(result.isError()).isTrue();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("No order was placed");
            Map<String, Object> problem = structured(result);
            assertThat(problem).containsEntry("type", "https://polaris.local/errors/price-changed");
            assertThat(problem).containsEntry("status", 409);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changed = (List<Map<String, Object>>) problem.get("changed_lines");
            assertThat(changed).hasSize(1);
            assertThat(changed.get(0)).containsEntry("sku", "NG-EARBUD-01");
            assertThat((java.math.BigDecimal) changed.get(0).get("expected_unit_price")).isEqualByComparingTo("39.90");
            assertThat((java.math.BigDecimal) changed.get(0).get("current_unit_price")).isEqualByComparingTo("49.90");
            assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        }

        @Test
        @DisplayName("insufficient stock is a tool error whose structuredContent carries the out-of-stock problem")
        void placeOrder_insufficientStock_structuredProblem() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1, "items", List.of(Map.of("sku", "NG-WATCH-01", "quantity", 9999))));

            assertThat(result.isError()).isTrue();
            Map<String, Object> problem = structured(result);
            assertThat(problem).containsEntry("type", "https://polaris.local/errors/out-of-stock");
            assertThat(problem).containsEntry("sku", "NG-WATCH-01");
            assertThat(problem).containsEntry("requested_quantity", 9999);
            assertThat(problem).containsKey("available_quantity");
        }

        @Test
        @DisplayName("invalid expected_unit_price is rejected before anything is ordered")
        void placeOrder_invalidExpectedPrice_error() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1, "expected_unit_price", "cheap"))));

            assertThat(result.isError()).isTrue();
            assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("expected_unit_price");
        }

        @Test
        @DisplayName("inactive product is a tool error with the product-inactive problem listing the SKUs")
        void placeOrder_inactiveProduct_structuredProblem() {
            var speaker = productRepository.findBySku("NG-SPEAKER-01").orElseThrow();
            speaker.setIsActive(false);
            productRepository.saveAndFlush(speaker);

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1, "items", List.of(Map.of("sku", "NG-SPEAKER-01", "quantity", 1))));

            assertThat(result.isError()).isTrue();
            Map<String, Object> problem = structured(result);
            assertThat(problem).containsEntry("type", "https://polaris.local/errors/product-inactive");
            assertThat(problem).containsEntry("status", 409);
            assertThat(problem).containsEntry("inactive_skus", List.of("NG-SPEAKER-01"));
        }

        @Test
        @DisplayName("unknown SKU (e.g. deleted since staging) is a structured not-found problem")
        void placeOrder_unknownSku_structuredNotFound() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1, "items", List.of(Map.of("sku", "NG-DOES-NOT-EXIST", "quantity", 1))));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("type", "https://polaris.local/errors/not-found")
                    .containsEntry("status", 404);
        }

        @Test
        @DisplayName("unknown customer_id is a structured not-found problem")
        void placeOrder_unknownCustomer_structuredNotFound() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 999999, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1))));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("type", "https://polaris.local/errors/not-found");
        }

        @Test
        @DisplayName("invalid arguments are structured validation problems naming the parameter")
        void placeOrder_invalidArguments_structuredValidation() {
            McpSchema.CallToolResult noItems = orderMcpTools.placeOrder(Map.of("customer_id", 1, "items", List.of()));
            assertThat(structured(noItems)).containsEntry("type", "https://polaris.local/errors/validation-error")
                    .containsEntry("status", 400)
                    .containsEntry("invalid_param", "items");

            McpSchema.CallToolResult badQty = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1, "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 0))));
            assertThat(structured(badQty)).containsEntry("invalid_param", "items.quantity");
        }

        @Test
        @DisplayName("idempotency_key longer than 100 characters is a structured validation problem and places nothing")
        void placeOrder_idempotencyKeyTooLong_structuredValidation() {
            long ordersBefore = orderRepository.count();

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(Map.of(
                    "customer_id", 1,
                    "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1)),
                    "idempotency_key", "k".repeat(101)));

            assertThat(result.isError()).isTrue();
            assertThat(structured(result)).containsEntry("type", "https://polaris.local/errors/validation-error")
                    .containsEntry("invalid_param", "idempotency_key");
            assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        }

        @Test
        @DisplayName("same idempotency_key replays the order; another shopper reusing it gets idempotency-key-reused")
        void placeOrder_idempotencyKey_replayAndReuseByOtherShopper() {
            Map<String, Object> args = Map.of(
                    "items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1)),
                    "idempotency_key", "mcp-s4-key-alice");

            McpSchema.CallToolResult first = orderMcpTools.placeOrder(args, shopper(ALICE_SUBJECT, "alice.tran@example.com"));
            assertThat(first.isError()).isFalse();
            long ordersAfterFirst = orderRepository.count();

            McpSchema.CallToolResult replay = orderMcpTools.placeOrder(args, shopper(ALICE_SUBJECT, "alice.tran@example.com"));
            assertThat(replay.isError()).isFalse();
            assertThat(((McpSchema.TextContent) replay.content().get(0)).text()).contains("no new order was created");

            McpSchema.CallToolResult reused = orderMcpTools.placeOrder(args, shopper(BEN_SUBJECT, "ben.nguyen@example.com"));
            assertThat(reused.isError()).isTrue();
            assertThat(((McpSchema.TextContent) reused.content().get(0)).text()).doesNotContain("Alice");
            assertThat(structured(reused)).containsEntry("type", "https://polaris.local/errors/idempotency-key-reused");
            assertThat(orderRepository.count()).isEqualTo(ordersAfterFirst);
        }
    }

    @Nested
    @DisplayName("place_order shopper identity binding (PRD-003 FR-10)")
    class ShopperIdentityTests {

        private final Map<String, Object> item = Map.of("sku", "NG-EARBUD-01", "quantity", 1);

        @Test
        @DisplayName("shopper without customer_id orders for their own linked customer")
        void placeOrder_shopperWithoutCustomerId_ordersForOwnCustomer() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(
                    Map.of("items", List.of(item)), shopper(ALICE_SUBJECT, "alice.tran@example.com"));

            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Order successfully placed!");
            assertThat(text).contains("- Customer: Alice Tran");
        }

        @Test
        @DisplayName("shopper naming another customer_id is forbidden and no order is created")
        void placeOrder_shopperForAnotherCustomer_forbidden() {
            long ordersBefore = orderRepository.count();

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(
                    Map.of("customer_id", 2, "items", List.of(item)), shopper(ALICE_SUBJECT, "alice.tran@example.com"));

            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).startsWith("Forbidden:");
            assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        }

        @Test
        @DisplayName("shopper-supplied customer_name is ignored: the order goes to the shopper's own customer")
        void placeOrder_shopperWithCustomerName_ignoresName() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(
                    Map.of("customer_name", "Ben Nguyen", "items", List.of(item)), shopper(ALICE_SUBJECT, "alice.tran@example.com"));

            assertThat(result.isError()).isFalse();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("- Customer: Alice Tran");
            assertThat(text).doesNotContain("Ben Nguyen");
        }

        @Test
        @DisplayName("shopper with no linked customer gets an error and no order")
        void placeOrder_unlinkedShopper_returnsError() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(
                    Map.of("customer_id", 1, "items", List.of(item)), shopper("unknown-subject", "nobody@example.com"));

            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("No customer is linked to the authenticated user.");
        }

        @Test
        @DisplayName("unauthenticated tool call is rejected")
        void placeOrder_unauthenticated_returnsError() {
            McpSchema.CallToolResult result = orderMcpTools.placeOrder(
                    Map.of("customer_id", 1, "items", List.of(item)), null);

            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Authentication is required");
        }

        @Test
        @DisplayName("caller without order.write is forbidden, staff included")
        void placeOrder_withoutOrderWritePermission_forbidden() {
            Authentication readOnlyStaff = new TestingAuthenticationToken("staff", null, "ROLE_ADMIN", "PERM_order.read");

            McpSchema.CallToolResult result = orderMcpTools.placeOrder(
                    Map.of("customer_id", 1, "items", List.of(item)), readOnlyStaff);

            assertThat(result.isError()).isTrue();
            String text = ((McpSchema.TextContent) result.content().get(0)).text();
            assertThat(text).contains("Forbidden: the 'order.write' permission is required");
        }

        @Test
        @DisplayName("Streamable transport: the request's caller reaches the place_order handler")
        void streamableTransport_propagatesCallerToPlaceOrder() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(shopper(ALICE_SUBJECT, "alice.tran@example.com"));

            org.springframework.mock.web.MockHttpServletResponse init = streamablePost(null, """
                    {"jsonrpc": "2.0", "id": "init-1", "method": "initialize",
                     "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                                "clientInfo": {"name": "test-client", "version": "1.0.0"}}}
                    """);
            String sessionId = init.getHeader("mcp-session-id");
            assertThat(sessionId).isNotBlank();
            streamablePost(sessionId, """
                    {"jsonrpc": "2.0", "method": "notifications/initialized"}
                    """);

            // A forbidden call writes nothing, so it is safe to run on the transport's own threads
            org.springframework.mock.web.MockHttpServletResponse call = streamablePost(sessionId, """
                    {"jsonrpc": "2.0", "id": "call-1", "method": "tools/call",
                     "params": {"name": "place_order",
                                "arguments": {"customer_id": 2, "items": [{"sku": "NG-EARBUD-01", "quantity": 1}]}}}
                    """);

            long deadline = System.currentTimeMillis() + 5_000;
            while (!call.getContentAsString().contains("call-1") && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(call.getContentAsString()).contains("Forbidden:");
        }

        private org.springframework.mock.web.MockHttpServletResponse streamablePost(String sessionId, String body) throws Exception {
            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", "/mcp/sse");
            request.setAsyncSupported(true);
            request.addHeader("Accept", "application/json, text/event-stream");
            if (sessionId != null) {
                request.addHeader("mcp-session-id", sessionId);
            }
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.springframework.mock.web.MockHttpServletResponse response =
                    new org.springframework.mock.web.MockHttpServletResponse();
            transport.service(request, response);
            return response;
        }

        @Test
        @DisplayName("Stateless transport: the request's caller reaches the place_order handler")
        void statelessTransport_propagatesCallerToPlaceOrder() throws Exception {
            // A forbidden call writes nothing, so it is safe to run on the transport's own threads
            SecurityContextHolder.getContext().setAuthentication(shopper(ALICE_SUBJECT, "alice.tran@example.com"));

            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", "/mcp");
            request.addHeader("Accept", "application/json, text/event-stream");
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent("""
                    {
                        "jsonrpc": "2.0",
                        "id": "call-1",
                        "method": "tools/call",
                        "params": {
                            "name": "place_order",
                            "arguments": {
                                "customer_id": 2,
                                "items": [{ "sku": "NG-EARBUD-01", "quantity": 1 }]
                            }
                        }
                    }
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.springframework.mock.web.MockHttpServletResponse response =
                    new org.springframework.mock.web.MockHttpServletResponse();

            statelessTransport.service(request, response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains("Forbidden:");
        }
    }

    @Nested
    @DisplayName("McpServerConfig & Wiring Tests")
    class ServerConfigWiringTests {

        @Test
        @DisplayName("Verify McpSyncServer bean is instantiated")
        void mcpSyncServer_isNotNull() {
            assertThat(mcpSyncServer).isNotNull();
        }

        @Test
        @DisplayName("Verify HttpServletStreamableServerTransportProvider bean is instantiated")
        void transport_isNotNull() {
            assertThat(transport).isNotNull();
        }

        @Test
        @DisplayName("Security: unauthenticated GET /mcp/sse returns 401 Unauthorized")
        void mcpSse_unauthenticated_returnsUnauthorized() throws Exception {
            mockMvc.perform(get("/mcp/sse"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Security: authenticated GET /mcp/sse is authorized")
        void mcpSse_authenticated_isAuthorized() throws Exception {
            mockMvc.perform(get("/mcp/sse").with(JwtMockFactory.user()))
                    .andExpect(result -> {
                        int statusCode = result.getResponse().getStatus();
                        assertThat(statusCode).isNotEqualTo(401);
                        assertThat(statusCode).isNotEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Security: unauthenticated POST /mcp/sse returns 401 Unauthorized")
        void mcpSsePost_unauthenticated_returnsUnauthorized() throws Exception {
            mockMvc.perform(post("/mcp/sse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Security: authenticated POST /mcp/sse is authorized")
        void mcpSsePost_authenticated_isAuthorized() throws Exception {
            mockMvc.perform(post("/mcp/sse")
                            .with(JwtMockFactory.user())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"))
                    .andExpect(result -> {
                        int statusCode = result.getResponse().getStatus();
                        assertThat(statusCode).isNotEqualTo(401);
                        assertThat(statusCode).isNotEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Streamable HTTP: initialize handshake over POST /mcp/sse succeeds with session ID")
        void mcpStreamable_initializeHandshake_succeeds() throws Exception {
            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", "/mcp/sse");
            request.addHeader("Accept", "application/json, text/event-stream");
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent("""
                    {
                        "jsonrpc": "2.0",
                        "id": "init-1",
                        "method": "initialize",
                        "params": {
                            "protocolVersion": "2024-11-05",
                            "capabilities": {},
                            "clientInfo": {
                                "name": "test-client",
                                "version": "1.0.0"
                            }
                        }
                    }
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            org.springframework.mock.web.MockHttpServletResponse response =
                    new org.springframework.mock.web.MockHttpServletResponse();
            transport.service(request, response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("mcp-session-id")).isNotBlank();
            assertThat(response.getContentAsString()).contains("polaris-mcp");
        }

        @Test
        @DisplayName("Security: unauthenticated POST /mcp/message returns 401 Unauthorized")
        void mcpMessage_unauthenticated_returnsUnauthorized() throws Exception {
            mockMvc.perform(post("/mcp/message")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Security: authenticated POST /mcp/message is authorized")
        void mcpMessage_authenticated_isAuthorized() throws Exception {
            mockMvc.perform(post("/mcp/message")
                            .with(JwtMockFactory.user())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}"))
                    .andExpect(result -> {
                        int statusCode = result.getResponse().getStatus();
                        assertThat(statusCode).isNotEqualTo(401);
                        assertThat(statusCode).isNotEqualTo(403);
                    });
        }

        @Test
        @DisplayName("Verify McpStatelessSyncServer bean is instantiated")
        void mcpStatelessSyncServer_isNotNull() {
            assertThat(mcpStatelessSyncServer).isNotNull();
        }

        @Test
        @DisplayName("Verify HttpServletStatelessServerTransport bean is instantiated")
        void statelessTransport_isNotNull() {
            assertThat(statelessTransport).isNotNull();
        }

        @Test
        @DisplayName("Security: unauthenticated POST /mcp returns 401 Unauthorized")
        void mcpStateless_unauthenticated_returnsUnauthorized() throws Exception {
            mockMvc.perform(post("/mcp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Security: authenticated POST /mcp is authorized")
        void mcpStateless_authenticated_isAuthorized() throws Exception {
            mockMvc.perform(post("/mcp")
                            .with(JwtMockFactory.user())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}"))
                    .andExpect(result -> {
                        int statusCode = result.getResponse().getStatus();
                        assertThat(statusCode).isNotEqualTo(401);
                        assertThat(statusCode).isNotEqualTo(403);
                    });
        }
    }
}
