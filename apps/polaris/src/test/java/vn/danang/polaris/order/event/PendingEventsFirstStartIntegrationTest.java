package vn.danang.polaris.order.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import vn.danang.polaris.PolarisApp;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.outbox.transport.EventTransport;

/**
 * Upgrading to the Kafka transport (TR-B7, TR-E2): an {@code order.placed} recorded before this plan, while no
 * transport existed and no topic had been provisioned, is delivered on the first start with the Kafka transport,
 * even when Kafka is unreachable at that start. Order provisions its topic once Kafka returns; the broker never
 * auto-creates it (TR-B1). Two real application starts against the same PostgreSQL and Kafka.
 */
@Testcontainers
class PendingEventsFirstStartIntegrationTest {

    private static final String SKU = "B2-FIRST-START-SKU";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer kafka = new KafkaContainer(TestcontainersConfiguration.KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private static ConfigurableApplicationContext start(String... extraProperties) {
        List<String> properties = new ArrayList<>(List.of(
                "server.port=0",
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.data.redis.host=" + redis.getHost(),
                "spring.data.redis.port=" + redis.getMappedPort(6379),
                "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "spring.kafka.admin.operation-timeout=3s",
                "polaris.outbox.kafka.send-timeout=3s",
                "management.tracing.export.enabled=false",
                "management.otlp.metrics.export.enabled=false"));
        properties.addAll(List.of(extraProperties));
        return new SpringApplicationBuilder(PolarisApp.class).properties(properties.toArray(String[]::new)).run();
    }

    @Test
    void anEventRecordedBeforeTheKafkaTransport_isDeliveredOnItsFirstStart_evenIfKafkaIsDownThen() throws Exception {
        // 1. As before this plan: no transport, no topic. Placing an order leaves one pending order.placed.
        String orderNumber;
        UUID ceId;
        try (ConfigurableApplicationContext before = start(
                "polaris.outbox.kafka.enabled=false", "spring.kafka.admin.auto-create=false")) {
            assertThat(before.getBeanProvider(EventTransport.class).getIfAvailable()).isNull();
            Product product = new Product();
            product.setSku(SKU);
            product.setName("First Start Item");
            product.setPrice(new BigDecimal("5.00"));
            product.setStockQty(10);
            product.setIsActive(true);
            product.setCreatedAt(Instant.now());
            before.getBean(ProductRepository.class).saveAndFlush(product);

            orderNumber = before.getBean(OrderService.class)
                    .placeOrder(1L, List.of(new OrderItemRequest(SKU, 1)), null).getOrderNumber();
            JdbcClient jdbc = before.getBean(JdbcClient.class);
            assertThat(status(jdbc, orderNumber)).isEqualTo("PENDING");
            ceId = jdbc.sql("SELECT event_id FROM outbox_events WHERE event_key = ?").param(orderNumber)
                    .query(UUID.class).single();
        }
        try (Admin admin = admin()) {
            assertThat(admin.listTopics().names().get()).doesNotContain(OrderEvents.DESTINATION);
        }

        // 2. First start with the Kafka transport while Kafka is unreachable: the app starts, the event waits.
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        ConfigurableApplicationContext after;
        try {
            after = start();
            assertThat(after.getBean(EventTransport.class)).isNotNull();
            Thread.sleep(2_000);
            assertThat(status(after.getBean(JdbcClient.class), orderNumber)).isEqualTo("PENDING");
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        // 3. Kafka is back: Order provisions its topic and the pending event is delivered.
        try (after) {
            List<ConsumerRecord<String, byte[]>> records = consume(orderNumber, Duration.ofSeconds(90));
            assertThat(records).isNotEmpty().allSatisfy(record -> assertThat(new String(
                    record.headers().lastHeader("ce_id").value(), StandardCharsets.UTF_8)).isEqualTo(ceId.toString()));
            try (Admin admin = admin()) {
                assertThat(admin.describeTopics(List.of(OrderEvents.DESTINATION)).allTopicNames().get()
                        .get(OrderEvents.DESTINATION).partitions()).hasSize(3);
            }
            JdbcClient jdbc = after.getBean(JdbcClient.class);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!"DELIVERED".equals(status(jdbc, orderNumber)) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(status(jdbc, orderNumber)).isEqualTo("DELIVERED");
        }
    }

    private static String status(JdbcClient jdbc, String orderNumber) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE event_key = ?").param(orderNumber)
                .query(String.class).single();
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    private static List<ConsumerRecord<String, byte[]>> consume(String key, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                // Refresh metadata quickly: the topic does not exist when this consumer subscribes
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 500);
        List<ConsumerRecord<String, byte[]>> matching = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(OrderEvents.DESTINATION));
            Instant deadline = Instant.now().plus(timeout);
            while (matching.isEmpty() && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (key.equals(r.key())) {
                        matching.add(r);
                    }
                });
            }
        }
        return matching;
    }
}
