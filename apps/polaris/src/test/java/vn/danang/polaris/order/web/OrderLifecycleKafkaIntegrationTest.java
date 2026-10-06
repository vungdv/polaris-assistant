package vn.danang.polaris.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Plan 2 B2 end to end against real PostgreSQL and a real Kafka broker of the compose image with topic auto-creation
 * disabled: a placed order reaches {@value OrderEvents#DESTINATION} as a binary-mode CloudEvent in the placing
 * request's trace (TR-B2, TR-B4), Order provisions its topic (TR-B1, TR-B5), commit order is kept per partition
 * (TR-B3, TR-E3), and a Kafka outage never fails placement and loses nothing (TR-B7, TR-E2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, OrderLifecycleKafkaIntegrationTest.SpanCapture.class })
class OrderLifecycleKafkaIntegrationTest {

    private static final String SKU = "B2-KAFKA-SKU";
    private static final String TOPIC = OrderEvents.DESTINATION;
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String CALLER_SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + CALLER_SPAN_ID + "-01";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private KafkaContainer kafka;
    @Autowired
    private InMemorySpanExporter spans;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCapture {

        /** Receives every finished span alongside the OTLP exporter. */
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @BeforeEach
    void createProduct() {
        cleanup();
        Product product = new Product();
        product.setSku(SKU);
        product.setName("Kafka Test Item");
        product.setPrice(new BigDecimal("9.90"));
        product.setStockQty(1000);
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

    @Test
    @DisplayName("Order provisions polaris.order.lifecycle with several partitions and its own min ISR; the broker never auto-creates topics")
    void lifecycleTopic_isProvisionedByOrder() throws Exception {
        try (Admin admin = admin()) {
            String autoCreate = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.BROKER, "1")))
                    .all().get().values().iterator().next().get("auto.create.topics.enable").value();
            assertThat(autoCreate).isEqualTo("false");
            assertThat(admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC).partitions())
                    .hasSize(3);
            // Declared on the topic by its owner (single broker here: 1; the dev cluster: 2).
            var minIsr = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.TOPIC, TOPIC)))
                    .all().get().values().iterator().next().get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG);
            assertThat(minIsr.value()).isEqualTo("1");
            assertThat(minIsr.source()).isEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
        }
    }

    @Test
    @DisplayName("Placed order → exactly one order.placed.v1 keyed by order number, all ce_* headers, in the request's trace")
    void placedOrder_arrivesExactlyOnce_asCloudEvent_inThePlacingRequestsTrace() throws Exception {
        spans.reset();
        MvcResult result = place();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String orderNumber = orderNumberOf(result);

        List<ConsumerRecord<String, byte[]>> records = consume(Set.of(orderNumber), 1);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(orderNumber);
            Map<String, String> headers = headers(record);
            String ceId = jdbc.sql("SELECT event_id FROM outbox_events WHERE event_key = ?").param(orderNumber)
                    .query(String.class).single();
            assertThat(headers).containsEntry("ce_specversion", "1.0")
                    .containsEntry("ce_id", ceId)
                    .containsEntry("ce_type", "vn.danang.polaris.order.placed.v1")
                    .containsEntry("ce_source", "/polaris/order")
                    .containsEntry("content-type", "application/json")
                    .containsKey("ce_time");
            assertThat(headers.keySet()).as("no serializer type headers").noneMatch(name -> name.startsWith("__"));
            assertThat(headers.get("traceparent")).startsWith("00-" + TRACE_ID + "-");

            CloudEvent event = KafkaMessageFactory.createReader(record).toEvent();
            JsonNode data = objectMapper.readTree(event.getData().toBytes());
            assertThat(data.path("orderNumber").asText()).isEqualTo(orderNumber);
            assertThat(data.path("status").asText()).isEqualTo("PLACED");
            assertThat(data.path("totalAmount").asText()).isEqualTo("9.90");

            // One trace: HTTP request → outbox hand-off → Kafka produce, whose context is in the record (TR-B4)
            String producedSpanId = headers.get("traceparent").split("-")[2];
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                Map<String, SpanData> byId = spans.getFinishedSpanItems().stream()
                        .filter(s -> s.getTraceId().equals(TRACE_ID))
                        .collect(Collectors.toMap(SpanData::getSpanId, s -> s, (a, b) -> a));
                SpanData produce = byId.get(producedSpanId);
                assertThat(produce).as("Kafka produce span").isNotNull();
                assertThat(produce.getKind()).isEqualTo(SpanKind.PRODUCER);
                assertThat(produce.getName()).contains(TOPIC);
                SpanData handOff = byId.get(produce.getParentSpanId());
                assertThat(handOff).as("outbox hand-off span").isNotNull();
                assertThat(handOff.getName()).isEqualTo("outbox publish " + TOPIC);
                SpanData server = byId.values().stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst()
                        .orElseThrow();
                assertThat(server.getParentSpanId()).isEqualTo(CALLER_SPAN_ID);
                assertThat(ancestors(handOff, byId)).as("hand-off descends from the HTTP request span")
                        .contains(server.getSpanId());
            });
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(jdbc
                .sql("SELECT status FROM outbox_events WHERE event_key = ?").param(orderNumber)
                .query(String.class).single()));
    }

    @Test
    @DisplayName("Several orders placed quickly → each arrives once, and every partition holds them in commit order")
    void severalOrdersPlacedQuickly_keepCommitOrderPerPartition() throws Exception {
        List<String> orderNumbers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MvcResult result = place();
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            orderNumbers.add(orderNumberOf(result));
        }

        List<ConsumerRecord<String, byte[]>> records = consume(Set.copyOf(orderNumbers), orderNumbers.size());

        assertThat(records).extracting(ConsumerRecord::key).containsExactlyInAnyOrderElementsOf(orderNumbers);
        assertCommitOrderPerPartition(records);
    }

    @Test
    @DisplayName("Kafka down while placing → 201; Kafka back → every event arrives, in commit order per partition")
    void kafkaDown_placementStillSucceeds_andEventsArriveWhenKafkaReturns() throws Exception {
        List<String> orderNumbers = new ArrayList<>();
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                MvcResult result = place();
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).as("placement never waits for Kafka")
                        .isLessThan(Duration.ofSeconds(5));
                orderNumbers.add(orderNumberOf(result));
            }
            // The relay tries, fails and keeps the events pending while Kafka is unreachable
            await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                    "SELECT COUNT(*) FROM outbox_events WHERE event_key IN (:keys) AND status = 'PENDING' AND attempts > 0")
                    .param("keys", orderNumbers).query(Long.class).single() > 0);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_key IN (:keys) AND status = 'PENDING'")
                    .param("keys", orderNumbers).query(Long.class).single()).isEqualTo(3L);
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        List<ConsumerRecord<String, byte[]>> records = consume(Set.copyOf(orderNumbers), orderNumbers.size());

        // At-least-once: a send that timed out during the outage may have been written, so dedupe by ce_id
        Map<String, ConsumerRecord<String, byte[]>> firstByCeId = new LinkedHashMap<>();
        records.forEach(r -> firstByCeId.putIfAbsent(headers(r).get("ce_id"), r));
        assertThat(firstByCeId.values()).extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrderElementsOf(orderNumbers);
        assertCommitOrderPerPartition(new ArrayList<>(firstByCeId.values()));
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql(
                "SELECT COUNT(*) FROM outbox_events WHERE event_key IN (:keys) AND status = 'DELIVERED'")
                .param("keys", orderNumbers).query(Long.class).single() == 3L);
    }

    /** Within each partition the records appear in outbox id (commit) order. */
    private void assertCommitOrderPerPartition(List<ConsumerRecord<String, byte[]>> records) {
        Map<Integer, List<ConsumerRecord<String, byte[]>>> byPartition = records.stream()
                .collect(Collectors.groupingBy(ConsumerRecord::partition));
        byPartition.forEach((partition, inPartition) -> {
            List<Long> outboxIds = inPartition.stream()
                    .sorted(Comparator.comparingLong(ConsumerRecord::offset))
                    .map(r -> jdbc.sql("SELECT id FROM outbox_events WHERE event_id = CAST(? AS UUID)")
                            .param(headers(r).get("ce_id")).query(Long.class).single())
                    .toList();
            assertThat(outboxIds).as("partition %d", partition).isSorted();
        });
    }

    private static List<String> ancestors(SpanData span, Map<String, SpanData> byId) {
        List<String> chain = new ArrayList<>();
        SpanData current = span;
        while (current != null && current.getParentSpanContext().isValid()) {
            chain.add(current.getParentSpanId());
            current = byId.get(current.getParentSpanId());
        }
        return chain;
    }

    private MvcResult place() throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"customerId\": 1, \"items\": [{ \"sku\": \"" + SKU + "\", \"quantity\": 1 }] }")
                        .header("traceparent", TRACEPARENT)
                        .with(JwtMockFactory.purchaseManagement()))
                .andReturn();
    }

    private String orderNumberOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("orderNumber").asText();
    }

    private static Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    /**
     * Reads the topic from the beginning with a fresh group until {@code expected} records for {@code keys} arrived,
     * then keeps reading for a quiet period so a duplicate would be seen too.
     */
    private List<ConsumerRecord<String, byte[]>> consume(Set<String> keys, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> matching = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plusSeconds(90);
            Instant quietUntil = null;
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (keys.contains(r.key())) {
                        matching.add(r);
                    }
                });
                if (matching.size() >= expected) {
                    quietUntil = quietUntil == null ? Instant.now().plusSeconds(2) : quietUntil;
                    if (Instant.now().isAfter(quietUntil)) {
                        break;
                    }
                }
            }
        }
        return matching;
    }
}
