package vn.danang.polaris.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.catalog.service.ProductService;
import vn.danang.polaris.config.PolarisPermissions;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class OrderQuoteApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    @DisplayName("POST /orders/quote returns live prices, stock, totals and changes nothing")
    void quote_allOk_returns200AndWritesNothing() throws Exception {
        int chargerStock = productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty();
        long orderCount = orderRepository.count();

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "items": [
                                { "sku": "NG-CHARGER-01", "quantity": 2 },
                                { "sku": "ng-earbud-01", "quantity": 1 }
                            ] }
                            """)
                        .with(JwtMockFactory.withPermissions(PolarisPermissions.ORDER_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderable").value(true))
                .andExpect(jsonPath("$.totalAmount").value(99.70))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].sku").value("NG-CHARGER-01"))
                .andExpect(jsonPath("$.lines[0].name").value("Nova 65W Fast Charger"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(24.90))
                .andExpect(jsonPath("$.lines[0].requestedQuantity").value(2))
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(chargerStock))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(49.80))
                .andExpect(jsonPath("$.lines[0].problem").value(nullValue()))
                .andExpect(jsonPath("$.lines[1].sku").value("NG-EARBUD-01"));

        assertThat(productRepository.findBySku("NG-CHARGER-01").orElseThrow().getStockQty()).isEqualTo(chargerStock);
        assertThat(orderRepository.count()).isEqualTo(orderCount);
    }

    @Test
    @DisplayName("POST /orders/quote reports per-line problems with 200, not an error")
    void quote_problems_reportedPerLine() throws Exception {
        int watchStock = productRepository.findBySku("NG-WATCH-01").orElseThrow().getStockQty();

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                            { "items": [
                                { "sku": "NG-WATCH-01", "quantity": %d },
                                { "sku": "NOPE-404", "quantity": 1 },
                                { "sku": "NG-STAND-01", "quantity": 1 }
                            ] }
                            """, watchStock + 1))
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderable").value(false))
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andExpect(jsonPath("$.lines[0].problem").value("insufficient_stock"))
                .andExpect(jsonPath("$.lines[0].requestedQuantity").value(watchStock + 1))
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(watchStock))
                .andExpect(jsonPath("$.lines[1].problem").value("not_found"))
                .andExpect(jsonPath("$.lines[1].unitPrice").value(nullValue()))
                // NG-STAND-01 is seeded active with zero stock
                .andExpect(jsonPath("$.lines[2].problem").value("insufficient_stock"))
                .andExpect(jsonPath("$.lines[2].availableQuantity").value(0));
    }

    @Test
    @DisplayName("POST /orders/quote flags inactive products")
    void quote_inactiveProduct() throws Exception {
        Product speaker = productRepository.findBySku("NG-SPEAKER-01").orElseThrow();
        speaker.setIsActive(false);
        productRepository.saveAndFlush(speaker);

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "items": [ { "sku": "NG-SPEAKER-01", "quantity": 1 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderable").value(false))
                .andExpect(jsonPath("$.lines[0].problem").value("inactive"));
    }

    @Test
    @DisplayName("POST /orders/quote reads the database, not the products cache (FR-1)")
    void quote_bypassesProductCache() throws Exception {
        // Warm the cache, then change stock underneath it the way OrderService does (repository, no eviction)
        int cachedStock = productService.getProductBySku("NG-CASE-01").stockQuantity();
        Product caseProduct = productRepository.findBySku("NG-CASE-01").orElseThrow();
        caseProduct.setStockQty(cachedStock - 7);
        productRepository.saveAndFlush(caseProduct);
        assertThat(productService.getProductBySku("NG-CASE-01").stockQuantity()).isEqualTo(cachedStock);

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "items": [ { "sku": "NG-CASE-01", "quantity": 1 } ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(cachedStock - 7));
    }

    @Test
    @DisplayName("POST /orders/quote validation failures return RFC 7807 400")
    void quote_invalidPayload_returns400Problem() throws Exception {
        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"items\": [] }")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"));

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"items\": [ { \"sku\": \"NG-CASE-01\", \"quantity\": 0 } ] }")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /orders/quote with more than 50 items returns RFC 7807 400")
    void quote_tooManyItems_returns400Problem() throws Exception {
        String items = java.util.stream.IntStream.rangeClosed(0, QuoteRequest.MAX_ITEMS)
                .mapToObj(i -> "{ \"sku\": \"NG-CASE-01\", \"quantity\": 1 }")
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"items\": [" + items + "] }")
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"));
    }

    @Test
    @DisplayName("POST /orders/quote merges lines repeating a SKU")
    void quote_repeatedSku_merged() throws Exception {
        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            { "items": [
                                { "sku": "NG-CHARGER-01", "quantity": 1 },
                                { "sku": "ng-charger-01", "quantity": 2 }
                            ] }
                            """)
                        .with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].requestedQuantity").value(3))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(74.70))
                .andExpect(jsonPath("$.totalAmount").value(74.70));
    }

    @Test
    @DisplayName("POST /orders/quote requires authentication and order.read")
    void quote_security() throws Exception {
        String body = "{ \"items\": [ { \"sku\": \"NG-CASE-01\", \"quantity\": 1 } ] }";

        mockMvc.perform(post("/api/v1/orders/quote").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/orders/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isForbidden());
    }
}
