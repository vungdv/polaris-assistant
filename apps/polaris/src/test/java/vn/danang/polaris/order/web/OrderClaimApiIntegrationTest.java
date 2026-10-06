package vn.danang.polaris.order.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.support.JwtMockFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F1 through the REST API against real PostgreSQL: first-wins claim (TR-O2), claim vs cancel (TR-O3), the
 * {@code order.confirmed.v1} outbox event (TR-O5) and the visible partner (TR-O6). Kafka is off so events stay pending.
 */
@SpringBootTest(properties = "polaris.outbox.kafka.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderClaimApiIntegrationTest {

    private static final String SKU = "F1-CLAIM-SKU";
    private static final String CONFIRMED_TYPE = "vn.danang.polaris.order.confirmed.v1";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderService orderService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void createProduct() {
        cleanup();
        Product product = new Product();
        product.setSku(SKU);
        product.setName("Claim Test Item");
        product.setPrice(new BigDecimal("12.50"));
        product.setStockQty(10);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);
    }

    @AfterEach
    void cleanup() {
        transactionTemplate.executeWithoutResult(status -> productRepository.findBySku(SKU).ifPresent(p -> {
            orderRepository.findAll().stream()
                    .filter(o -> o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId())))
                    .forEach(o -> {
                        jdbc.sql("DELETE FROM outbox_events WHERE event_key = ?").param(o.getOrderNumber()).update();
                        orderRepository.delete(o);
                    });
            productRepository.delete(p);
        }));
    }

    private String placeOrder() {
        return orderService.place(1L, List.of(new OrderItemRequest(SKU, 1)), null).order().getOrderNumber();
    }

    private MockHttpServletResponse claim(String orderNumber, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + orderNumber + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(JwtMockFactory.withPermissions("order.fulfil")))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse claimBy(String orderNumber, String partner) throws Exception {
        return claim(orderNumber, "{ \"partnerId\": \"" + partner + "\" }");
    }

    private long confirmedEvents(String orderNumber) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_key = ? AND event_type = ?")
                .params(orderNumber, CONFIRMED_TYPE).query(Long.class).single();
    }

    private String statusOf(String orderNumber) {
        return jdbc.sql("SELECT status FROM orders WHERE order_number = ?").param(orderNumber).query(String.class).single();
    }

    private int stock() {
        return productRepository.findBySku(SKU).orElseThrow().getStockQty();
    }

    @Test
    @DisplayName("Claim of a PLACED order → 200 CONFIRMED with assignedPartner, one order.confirmed.v1 carrying the partner")
    void claim_placedOrder_returns200AndOneConfirmedEvent() throws Exception {
        String orderNumber = placeOrder();

        MockHttpServletResponse response = claimBy(orderNumber, "partner-a");

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("assignedPartner").asText()).isEqualTo("partner-a");
        assertThat(confirmedEvents(orderNumber)).isEqualTo(1);
        JsonNode payload = objectMapper.readTree(jdbc.sql(
                "SELECT payload FROM outbox_events WHERE event_key = ? AND event_type = ?")
                .params(orderNumber, CONFIRMED_TYPE).query(String.class).single());
        assertThat(payload.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(payload.path("assignedPartner").asText()).isEqualTo("partner-a");
        assertThat(payload.path("customer").path("id").asLong()).isEqualTo(1L);
        assertThat(payload.path("items")).hasSize(1);
        assertThat(new BigDecimal(payload.path("totalAmount").asText())).isEqualByComparingTo("12.50");

        // TR-O6: visible on the read path
        mockMvc.perform(get("/api/v1/orders/" + orderNumber).with(JwtMockFactory.admin()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.assignedPartner").value("partner-a"));
    }

    @Test
    @DisplayName("Repeat claim (by the winner or another partner) → 409 order-not-claimable with orderStatus, no winner, no second event")
    void claim_twice_returns409WithoutWinner() throws Exception {
        String orderNumber = placeOrder();
        assertThat(claimBy(orderNumber, "partner-a").getStatus()).isEqualTo(200);

        for (String partner : List.of("partner-a", "partner-b")) {
            MockHttpServletResponse response = claimBy(orderNumber, partner);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentType()).contains("application/problem+json");
            JsonNode problem = objectMapper.readTree(response.getContentAsString());
            assertThat(problem.path("type").asText()).isEqualTo("https://polaris.local/errors/order-not-claimable");
            assertThat(problem.path("status").asInt()).isEqualTo(409);
            assertThat(problem.path("orderStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(response.getContentAsString()).doesNotContain("partner-a").doesNotContain("partner-b");
        }
        assertThat(confirmedEvents(orderNumber)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT assigned_partner FROM orders WHERE order_number = ?").param(orderNumber)
                .query(String.class).single()).isEqualTo("partner-a");
    }

    @Test
    @DisplayName("Claim of a CANCELLED order → 409, no partner, no event")
    void claim_cancelledOrder_returns409() throws Exception {
        String orderNumber = placeOrder();
        orderService.cancelOrder(orderNumber);

        MockHttpServletResponse response = claimBy(orderNumber, "partner-a");

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(response.getContentAsString()).path("orderStatus").asText()).isEqualTo("CANCELLED");
        assertThat(statusOf(orderNumber)).isEqualTo("CANCELLED");
        assertThat(confirmedEvents(orderNumber)).isZero();
    }

    @Test
    @DisplayName("Unknown order → 404; invalid body → 400; token without order.fulfil → 403")
    void claim_notFound_badRequest_forbidden() throws Exception {
        assertThat(claimBy("ORD-NOPE", "partner-a").getStatus()).isEqualTo(404);

        String orderNumber = placeOrder();
        assertThat(claim(orderNumber, "{}").getStatus()).isEqualTo(400);
        assertThat(claim(orderNumber, "{ \"partnerId\": \"  \" }").getStatus()).isEqualTo(400);
        assertThat(claim(orderNumber, "{ \"partnerId\": \"" + "x".repeat(65) + "\" }").getStatus()).isEqualTo(400);

        MockHttpServletResponse forbidden = mockMvc.perform(post("/api/v1/orders/" + orderNumber + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"partnerId\": \"partner-a\" }")
                        .with(JwtMockFactory.admin()))
                .andReturn().getResponse();
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(statusOf(orderNumber)).isEqualTo("PLACED");
        assertThat(confirmedEvents(orderNumber)).isZero();
    }

    @Test
    @DisplayName("Concurrent claims (10) → exactly one 200, the rest 409, exactly one order.confirmed.v1")
    void concurrentClaims_exactlyOneWins() throws Exception {
        String orderNumber = placeOrder();
        int total = 10;
        ExecutorService executor = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < total; i++) {
            String partner = "partner-" + i;
            executor.submit(() -> {
                try {
                    start.await();
                    statuses.add(claimBy(orderNumber, partner).getStatus());
                } catch (Throwable e) {
                    statuses.add(-1);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(total - 1);
        assertThat(statusOf(orderNumber)).isEqualTo("CONFIRMED");
        assertThat(confirmedEvents(orderNumber)).isEqualTo(1);
    }

    @Test
    @DisplayName("Claim racing cancel on a PLACED order → a claim that loses is 409 with no event; never a confirmed order with restored stock")
    void claimRacingCancel_isSerialised() throws Exception {
        for (int round = 0; round < 8; round++) {
            String orderNumber = placeOrder();
            int stockAfterPlace = stock();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            var claimResult = executor.submit(() -> {
                start.await();
                return claimBy(orderNumber, "partner-a").getStatus();
            });
            var cancelResult = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post("/api/v1/orders/" + orderNumber + "/cancel").with(JwtMockFactory.admin()))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int claimStatus = claimResult.get(30, TimeUnit.SECONDS);
            int cancelStatus = cancelResult.get(30, TimeUnit.SECONDS);
            executor.shutdown();

            String finalStatus = statusOf(orderNumber);
            if (claimStatus == 200) {
                // Cancel of a claimed order is unchanged by F1 (plan Change Log): it may follow the claim.
                assertThat(confirmedEvents(orderNumber)).isEqualTo(1);
                assertThat(cancelStatus).isIn(200, 409);
                assertThat(finalStatus).isEqualTo(cancelStatus == 200 ? "CANCELLED" : "CONFIRMED");
                assertThat(stock()).isEqualTo(cancelStatus == 200 ? stockAfterPlace + 1 : stockAfterPlace);
            } else {
                assertThat(claimStatus).isEqualTo(409);
                assertThat(cancelStatus).isEqualTo(200);
                assertThat(finalStatus).isEqualTo("CANCELLED");
                assertThat(confirmedEvents(orderNumber)).isZero();
                assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE order_number = ? AND assigned_partner IS NOT NULL")
                        .param(orderNumber).query(Long.class).single()).isZero();
                assertThat(stock()).isEqualTo(stockAfterPlace + 1);
            }
            assertThat(finalStatus.equals("CONFIRMED") && stock() > stockAfterPlace).isFalse();
        }
    }

    @Test
    @DisplayName("V16 applied cleanly to the seeded DB: pre-existing fulfilled orders got a partner, PLACED/CANCELLED none; the DB refuses a fulfilled order without a partner")
    void migration_seededData_andCheckConstraint() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE order_number IN ('ORD-1002','ORD-1003','ORD-1004','ORD-1005') "
                + "AND assigned_partner IS NOT NULL AND claimed_at IS NOT NULL").query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE order_number IN ('ORD-1001','ORD-1006') "
                + "AND assigned_partner IS NULL").query(Long.class).single()).isEqualTo(2);

        String orderNumber = placeOrder();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                "UPDATE orders SET status = 'CONFIRMED' WHERE order_number = ?").param(orderNumber).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // a partner on a CANCELLED order is allowed
        assertThat(jdbc.sql("UPDATE orders SET status = 'CANCELLED', assigned_partner = 'p' WHERE order_number = ?")
                .param(orderNumber).update()).isEqualTo(1);
    }
}
