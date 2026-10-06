package vn.danang.polaris.order.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.event.OrderCancelled;
import vn.danang.polaris.order.event.OrderPlaced;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.web.support.JwtMockFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E4 through the REST API against real PostgreSQL: placing an order records exactly one {@code order.placed.v1}
 * in the outbox, committed with the order and in the placing request's trace; rejected placements and idempotent
 * replays record nothing (TR-O1). With no transport configured the event stays pending (Plan 1 Definition of Done),
 * so this suite switches the Kafka transport off; delivery to Kafka is covered by {@code OrderLifecycleKafkaIntegrationTest}.
 */
@SpringBootTest(properties = "polaris.outbox.kafka.enabled=false")
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class OrderPlacedEventApiIntegrationTest {

    private static final String SKU = "E4-EVENT-SKU";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationEvents applicationEvents;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    record OutboxRow(long id, String eventType, String eventKey, String status, String payload, String traceparent) {}

    @BeforeEach
    void createProduct() {
        cleanup();
        Product product = new Product();
        product.setSku(SKU);
        product.setName("Outbox Test Item");
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

    private List<OutboxRow> outboxRows(String key) {
        return jdbc.sql("""
                        SELECT id, event_type, event_key, status, payload, traceparent
                        FROM outbox_events WHERE event_key = ? ORDER BY id""")
                .param(key)
                .query(OutboxRow.class)
                .list();
    }

    private long outboxCount() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Long.class).single();
    }

    private MvcResult place(String items, String idempotencyKey) throws Exception {
        var request = post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"customerId\": 1, \"items\": " + items + " }")
                .header("traceparent", TRACEPARENT)
                .with(JwtMockFactory.purchaseManagement());
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andReturn();
    }

    private String orderNumberOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("orderNumber").asText();
    }

    @Test
    @DisplayName("Placed order with no transport → 201 and exactly one pending order.placed with customer, items, total and trace")
    void placeOrder_noTransport_returns201AndOnePendingOrderPlaced() throws Exception {
        assertThat(context.getBeanProvider(EventTransport.class).getIfAvailable()).isNull();

        MvcResult result = place("[{ \"sku\": \"" + SKU + "\", \"quantity\": 2 }]", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String orderNumber = orderNumberOf(result);
        assertThat(applicationEvents.stream(OrderPlaced.class)).singleElement()
                .satisfies(e -> assertThat(e.orderNumber()).isEqualTo(orderNumber));

        List<OutboxRow> rows = outboxRows(orderNumber);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.eventType()).isEqualTo("vn.danang.polaris.order.placed.v1");
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.traceparent()).startsWith("00-" + TRACE_ID + "-");
        });
        JsonNode payload = objectMapper.readTree(rows.getFirst().payload());
        assertThat(payload.path("orderNumber").asText()).isEqualTo(orderNumber);
        assertThat(payload.path("status").asText()).isEqualTo("PLACED");
        assertThat(payload.path("customer").path("id").asLong()).isEqualTo(1L);
        assertThat(payload.path("customer").path("name").asText()).isEqualTo("Alice Tran");
        assertThat(payload.path("customer").path("email").asText()).isEqualTo("alice.tran@example.com");
        assertThat(payload.path("items")).hasSize(1);
        JsonNode item = payload.path("items").get(0);
        assertThat(item.path("sku").asText()).isEqualTo(SKU);
        assertThat(item.path("name").asText()).isEqualTo("Outbox Test Item");
        assertThat(item.path("quantity").asInt()).isEqualTo(2);
        assertThat(item.path("unitPrice").isTextual()).isTrue();
        assertThat(new BigDecimal(item.path("unitPrice").asText())).isEqualByComparingTo("12.50");
        assertThat(payload.path("totalAmount").isTextual()).isTrue();
        assertThat(new BigDecimal(payload.path("totalAmount").asText())).isEqualByComparingTo("25.00");
        assertThat(payload.path("currency").asText()).isEqualTo("USD");
        assertThat(payload.path("assignedPartner").isNull()).isTrue();
    }

    @Test
    @DisplayName("Idempotent replay (same Idempotency-Key) → 200 and no second event")
    void idempotentReplay_recordsNothing() throws Exception {
        String key = "e4-replay-" + java.util.UUID.randomUUID();
        String items = "[{ \"sku\": \"" + SKU + "\", \"quantity\": 1 }]";

        MvcResult first = place(items, key);
        MvcResult replay = place(items, key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        String orderNumber = orderNumberOf(first);
        assertThat(orderNumberOf(replay)).isEqualTo(orderNumber);
        assertThat(applicationEvents.stream(OrderPlaced.class)).hasSize(1);
        assertThat(outboxRows(orderNumber)).hasSize(1);
    }

    @Test
    @DisplayName("Rejected placement (insufficient stock) → 400 and no event")
    void insufficientStock_recordsNothing() throws Exception {
        long before = outboxCount();

        MvcResult result = place("[{ \"sku\": \"" + SKU + "\", \"quantity\": 11 }]", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(applicationEvents.stream(OrderPlaced.class)).isEmpty();
        assertThat(outboxCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("Rejected placement (price changed) → 409 and no event")
    void priceChanged_recordsNothing() throws Exception {
        long before = outboxCount();

        MvcResult result = place("[{ \"sku\": \"" + SKU + "\", \"quantity\": 1, \"expectedUnitPrice\": 9.99 }]", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(applicationEvents.stream(OrderPlaced.class)).isEmpty();
        assertThat(outboxCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("Cancel → exactly one OrderCancelled; a repeated cancel → 409 and no event")
    void cancel_registersExactlyOneOrderCancelled() throws Exception {
        String orderNumber = orderNumberOf(place("[{ \"sku\": \"" + SKU + "\", \"quantity\": 1 }]", null));

        mockMvc.perform(post("/api/v1/orders/{n}/cancel", orderNumber).with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders/{n}/cancel", orderNumber).with(JwtMockFactory.purchaseManagement()))
                .andExpect(status().isConflict());

        assertThat(applicationEvents.stream(OrderCancelled.class)).singleElement()
                .satisfies(e -> assertThat(e.orderNumber()).isEqualTo(orderNumber));
        // No order.cancelled contract exists (EM-002), so only order.placed is published for this order
        assertThat(outboxRows(orderNumber)).extracting(OutboxRow::eventType)
                .containsExactly("vn.danang.polaris.order.placed.v1");
    }
}
