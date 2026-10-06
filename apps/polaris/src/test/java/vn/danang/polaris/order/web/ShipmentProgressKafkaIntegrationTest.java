package vn.danang.polaris.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.micrometer.core.instrument.MeterRegistry;
import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.OrderService;

/**
 * F3 at the Kafka level against real PostgreSQL and Kafka: shipment reports on {@value FulfilmentEvents#DESTINATION}
 * drive the order and announce each milestone once through the outbox (TR-O4, TR-O5); anything else is a counted no-op.
 * The test topic has one partition, so a trailing "sentinel" report proves every earlier record was consumed.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ShipmentProgressKafkaIntegrationTest.Topics.class })
class ShipmentProgressKafkaIntegrationTest {

    private static final String SKU = "F3-SHIP-SKU";
    private static final String PARTNER = "partner-a";
    private static final String DLT = FulfilmentEvents.DESTINATION + ".DLT";

    @Autowired private JdbcClient jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderService orderService;
    @Autowired private KafkaContainer kafka;
    @Autowired private MeterRegistry meters;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        /** Fulfilment owns this topic in production; here it must exist before the listener starts. */
        @Bean
        NewTopic shipmentsTopic() {
            return TopicBuilder.name(FulfilmentEvents.DESTINATION).partitions(1).replicas(1).build();
        }
    }

    @BeforeEach
    void createProduct() {
        cleanup();
        Product product = new Product();
        product.setSku(SKU);
        product.setName("Shipment Test Item");
        product.setPrice(new BigDecimal("12.50"));
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

    private String claimedOrder(String partner) {
        String orderNumber = orderService.place(1L, List.of(new OrderItemRequest(SKU, 1)), null).order().getOrderNumber();
        orderService.claimOrder(orderNumber, partner);
        return orderNumber;
    }

    private void report(String orderNumber, String partner, ShipmentStep step) {
        String json = "{\"orderNumber\":\"" + orderNumber + "\",\"shipmentId\":\"S-" + orderNumber + "\",\"partnerId\":\""
                + partner + "\",\"step\":\"" + step + "\",\"occurredAt\":\"" + Instant.now() + "\"}";
        send(orderNumber, step, json, null);
    }

    private void send(String orderNumber, ShipmentStep step, String data, String traceparent) {
        var event = CloudEventBuilder.v1().withId(UUID.randomUUID().toString()).withType(step.type())
                .withSource(URI.create(FulfilmentEvents.SOURCE)).withDataContentType("application/json")
                .withData(data.getBytes()).build();
        var config = Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer())) {
            var message = KafkaMessageFactory.createWriter(FulfilmentEvents.DESTINATION).writeBinary(event);
            var record = new ProducerRecord<>(FulfilmentEvents.DESTINATION, null, orderNumber, message.value(),
                    message.headers());
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every record currently on the dead-letter topic. */
    private List<ConsumerRecord<String, byte[]>> deadLetters() {
        var config = Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(DLT));
            var records = new ArrayList<ConsumerRecord<String, byte[]>>();
            consumer.poll(Duration.ofSeconds(5)).forEach(records::add);
            return records;
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Sends a valid PACKED for a fresh order and waits until it is applied: everything sent before it was consumed. */
    private void drain() {
        String sentinel = claimedOrder(PARTNER);
        report(sentinel, PARTNER, ShipmentStep.PACKED);
        await().atMost(Duration.ofSeconds(60)).until(() -> "PARCELED".equals(statusOf(sentinel)));
    }

    private String statusOf(String orderNumber) {
        return jdbc.sql("SELECT status FROM orders WHERE order_number = ?").param(orderNumber).query(String.class).single();
    }

    private List<String> milestones(String orderNumber) {
        return jdbc.sql("SELECT event_type FROM outbox_events WHERE event_key = ? ORDER BY id")
                .param(orderNumber).query(String.class).list();
    }

    private double ignored(String step, String reason) {
        var counter = meters.find("polaris.order.shipment.reports")
                .tags("step", step, "outcome", "ignored", "reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("PACKED → DISPATCHED → DELIVERED reports → order DELIVERED, three milestone events in order with the partner")
    void reports_driveOrderToDelivered() {
        String orderNumber = claimedOrder(PARTNER);

        report(orderNumber, PARTNER, ShipmentStep.PACKED);
        report(orderNumber, PARTNER, ShipmentStep.DISPATCHED);
        report(orderNumber, PARTNER, ShipmentStep.DELIVERED);

        await().atMost(Duration.ofSeconds(60)).until(() -> "DELIVERED".equals(statusOf(orderNumber)));
        assertThat(milestones(orderNumber)).containsExactly(
                "vn.danang.polaris.order.placed.v1", "vn.danang.polaris.order.confirmed.v1",
                "vn.danang.polaris.order.parceled.v1", "vn.danang.polaris.order.delivering.v1",
                "vn.danang.polaris.order.delivered.v1");
        String payload = jdbc.sql("SELECT payload FROM outbox_events WHERE event_key = ? AND event_type = ?")
                .params(orderNumber, "vn.danang.polaris.order.delivered.v1").query(String.class).single();
        assertThat(payload).contains("\"assignedPartner\":\"" + PARTNER + "\"").contains("\"status\":\"DELIVERED\"")
                .contains("\"totalAmount\":\"12.50\"");
    }

    @Test
    @DisplayName("Wrong partner, duplicate, late and cancelled-order reports → no change, no event, counted by reason")
    void unexpectedReports_areCountedNoOps() {
        String wrongPartner = claimedOrder(PARTNER);
        String duplicate = claimedOrder(PARTNER);
        String late = claimedOrder(PARTNER);
        String cancelled = claimedOrder(PARTNER);
        orderService.cancelOrder(cancelled);
        double wrong0 = ignored("packed", "wrong_partner");
        double dup0 = ignored("packed", "duplicate");
        double late0 = ignored("packed", "late");
        double cancelled0 = ignored("packed", "cancelled");
        double unknown0 = ignored("packed", "unknown_order");
        double outOfOrder0 = ignored("delivered", "out_of_order");

        report(wrongPartner, "partner-b", ShipmentStep.PACKED);
        report(duplicate, PARTNER, ShipmentStep.PACKED);
        report(duplicate, PARTNER, ShipmentStep.PACKED);
        report(late, PARTNER, ShipmentStep.PACKED);
        report(late, PARTNER, ShipmentStep.DISPATCHED);
        report(late, PARTNER, ShipmentStep.DELIVERED);
        report(late, PARTNER, ShipmentStep.PACKED);
        report(cancelled, PARTNER, ShipmentStep.PACKED);
        report("ORD-DOES-NOT-EXIST", PARTNER, ShipmentStep.PACKED);
        report(wrongPartner, PARTNER, ShipmentStep.DELIVERED);
        drain();

        assertThat(statusOf(wrongPartner)).isEqualTo("CONFIRMED");
        assertThat(milestones(wrongPartner)).hasSize(2);
        assertThat(statusOf(duplicate)).isEqualTo("PARCELED");
        assertThat(milestones(duplicate)).as("same report twice → one milestone").hasSize(3)
                .endsWith("vn.danang.polaris.order.parceled.v1");
        assertThat(statusOf(late)).isEqualTo("DELIVERED");
        assertThat(milestones(late)).hasSize(5);
        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(milestones(cancelled)).doesNotContain("vn.danang.polaris.order.parceled.v1");

        assertThat(ignored("packed", "wrong_partner") - wrong0).isEqualTo(1);
        assertThat(ignored("packed", "duplicate") - dup0).isEqualTo(1);
        assertThat(ignored("packed", "late") - late0).isEqualTo(1);
        assertThat(ignored("packed", "cancelled") - cancelled0).isEqualTo(1);
        assertThat(ignored("packed", "unknown_order") - unknown0).isEqualTo(1);
        assertThat(ignored("delivered", "out_of_order") - outOfOrder0).isEqualTo(1);
    }

    @Test
    @DisplayName("Unparseable report → moved to <topic>.DLT with its key, payload, traceparent and the failure; later reports still applied")
    void poisonReport_isDeadLettered() {
        String orderNumber = claimedOrder(PARTNER);
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        send(orderNumber, ShipmentStep.PACKED, "not json", traceparent);
        drain();

        assertThat(statusOf(orderNumber)).isEqualTo("CONFIRMED");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(deadLetters())
                .filteredOn(r -> orderNumber.equals(r.key())).singleElement().satisfies(r -> {
                    assertThat(new String(r.value(), StandardCharsets.UTF_8)).isEqualTo("not json");
                    assertThat(header(r, "traceparent")).isEqualTo(traceparent);
                    assertThat(header(r, "ce_type")).isEqualTo(ShipmentStep.PACKED.type());
                    assertThat(header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(FulfilmentEvents.DESTINATION);
                    assertThat(header(r, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("jackson");
                }));
    }
}
