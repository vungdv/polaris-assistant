package vn.danang.polaris.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ProducerState;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.KafkaMessageFactory;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.autoconfigure.OutboxAutoConfiguration;
import vn.danang.polaris.outbox.autoconfigure.OutboxKafkaAutoConfiguration;
import vn.danang.polaris.outbox.transport.EventTransport;

/**
 * The auto-configured outbox relay with the Kafka transport against real PostgreSQL and a real Kafka broker of the
 * compose image, with topic auto-creation disabled as in compose (TR-B1). No stubs.
 */
// @Testcontainers first so its afterAll runs last: the context (and its polling worker) closes before the brokers stop.
@Testcontainers
@SpringBootTest(classes = KafkaEventTransportIntegrationTest.TestApp.class,
        properties = "polaris.outbox.kafka.send-timeout=3s", webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class KafkaEventTransportIntegrationTest {

    static final String KAFKA_IMAGE = "apache/kafka:3.9.1";
    static final String TOPIC = "polaris.test.lifecycle";
    static final String TYPE = "vn.danang.polaris.test.thing.happened.v1";
    static final String SOURCE = "/polaris/test";
    static final int PARTITIONS = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private IntegrationEventPublisher publisher;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EventTransport transport;

    @Test
    void recordedEvent_arrivesOnce_asABinaryModeCloudEvent_keyedByAggregate_inTheRecordedTrace() throws Exception {
        String key = "K-" + UUID.randomUUID();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        UUID recorded;
        try (Scope ignored = Span.wrap(SpanContext.create(traceId, "00f067aa0ba902b7", TraceFlags.getSampled(),
                TraceState.builder().put("polaris", "t1").build())).makeCurrent()) {
            recorded = tx.execute(status -> publisher.publish(
                    new IntegrationEvent(TYPE, SOURCE, TOPIC, key, Map.of("key", key, "amount", "12.50"))));
        }

        List<ConsumerRecord<String, byte[]>> records = consumeKeys(Set.of(key), 1);

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(key);
            Map<String, String> headers = headers(record);
            assertThat(headers).containsEntry("ce_specversion", "1.0")
                    .containsEntry("ce_id", recorded.toString())
                    .containsEntry("ce_type", TYPE)
                    .containsEntry("ce_source", SOURCE)
                    .containsEntry("content-type", "application/json")
                    .containsKey("ce_time")
                    .containsEntry("tracestate", "polaris=t1");
            assertThat(headers.get("traceparent")).startsWith("00-" + traceId + "-");
            assertThat(headers.keySet()).noneMatch(name -> name.startsWith("__"));
            assertThat(new String(record.value(), StandardCharsets.UTF_8))
                    .contains("\"key\":\"" + key + "\"").contains("\"amount\":\"12.50\"");

            // Any CloudEvents SDK consumer reads it back (the binding Plans 3 and 4 consume with)
            CloudEvent event = KafkaMessageFactory.createReader(record).toEvent();
            assertThat(event.getId()).isEqualTo(recorded.toString());
            assertThat(event.getType()).isEqualTo(TYPE);
            assertThat(event.getSource().toString()).isEqualTo(SOURCE);
            assertThat(event.getDataContentType()).isEqualTo("application/json");
            assertThat(event.getTime()).isNotNull();
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> "DELIVERED".equals(status(recorded)));
    }

    @Test
    void interleavedEventsForManyKeys_arriveInRecordOrderPerKey_eachKeyOnOnePartition() throws Exception {
        List<String> keys = new ArrayList<>();
        for (int k = 0; k < 12; k++) {
            keys.add("ORD-" + k + "-" + UUID.randomUUID());
        }
        int perKey = 5;
        // One transaction per event, interleaving keys, as quick successive business changes would
        for (int seq = 0; seq < perKey; seq++) {
            for (String key : keys) {
                int s = seq;
                tx.executeWithoutResult(status -> publisher.publish(
                        new IntegrationEvent(TYPE, SOURCE, TOPIC, key, Map.of("key", key, "seq", s))));
            }
        }

        List<ConsumerRecord<String, byte[]>> records = consumeKeys(Set.copyOf(keys), keys.size() * perKey);

        Map<String, List<ConsumerRecord<String, byte[]>>> byKey = records.stream()
                .collect(Collectors.groupingBy(ConsumerRecord::key, LinkedHashMap::new, Collectors.toList()));
        assertThat(byKey).hasSize(keys.size());
        byKey.forEach((key, perKeyRecords) -> {
            assertThat(perKeyRecords).extracting(ConsumerRecord::partition).as("partitions of %s", key).containsOnly(
                    perKeyRecords.getFirst().partition());
            assertThat(perKeyRecords).extracting(r -> seqOf(r)).as("sequence of %s", key)
                    .containsExactly(0, 1, 2, 3, 4);
        });
        assertThat(records.stream().map(ConsumerRecord::partition).distinct().count()).isGreaterThan(1);
    }

    @Test
    void producerIsIdempotentWithAcksAll_andTheBrokerTracksItsSequence() throws Exception {
        Map<String, Object> config = ((KafkaEventTransport) transport).template().getProducerFactory()
                .getConfigurationProperties();
        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // The application's own Kafka connection settings (here: the service connection) still apply
        assertThat(config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG).toString()).contains(kafka.getBootstrapServers());

        String key = "IDEMPOTENT-" + UUID.randomUUID();
        tx.executeWithoutResult(status -> publisher.publish(new IntegrationEvent(TYPE, SOURCE, TOPIC, key, Map.of())));
        ConsumerRecord<String, byte[]> record = consumeKeys(Set.of(key), 1).getFirst();

        // Only idempotent (or transactional) producers have broker-side producer state: id, epoch and sequence.
        try (Admin admin = admin()) {
            TopicPartition partition = new TopicPartition(TOPIC, record.partition());
            List<ProducerState> producers = admin.describeProducers(List.of(partition)).partitionResult(partition)
                    .get().activeProducers();
            assertThat(producers).isNotEmpty().allSatisfy(producer -> {
                assertThat(producer.producerId()).isNotNegative();
                assertThat(producer.lastSequence()).isNotNegative();
            });
        }
    }

    @Test
    void anUndeclaredTopic_isNeverAutoCreated_andItsEventStaysPendingWithTheError() throws Exception {
        try (Admin admin = admin()) {
            String autoCreate = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.BROKER, "1")))
                    .all().get().values().iterator().next().get("auto.create.topics.enable").value();
            assertThat(autoCreate).isEqualTo("false");
            assertThat(admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC).partitions())
                    .as("declared topic provisioned by its owner").hasSize(PARTITIONS);
        }

        UUID recorded = tx.execute(status -> publisher.publish(
                new IntegrationEvent(TYPE, SOURCE, "polaris.undeclared", "K-1", Map.of())));

        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc
                .sql("SELECT attempts FROM outbox_events WHERE event_id = :id").param("id", recorded)
                .query(Integer.class).single() > 0);
        assertThat(status(recorded)).isEqualTo("PENDING");
        try (Admin admin = admin()) {
            assertThat(admin.listTopics().names().get()).doesNotContain("polaris.undeclared");
        }
        jdbc.sql("DELETE FROM outbox_events WHERE event_id = :id").param("id", recorded).update();
    }

    private String status(UUID eventId) {
        return jdbc.sql("SELECT status FROM outbox_events WHERE event_id = :id").param("id", eventId)
                .query(String.class).single();
    }

    static int seqOf(ConsumerRecord<String, byte[]> record) {
        String json = new String(record.value(), StandardCharsets.UTF_8);
        return Integer.parseInt(json.replaceAll(".*\"seq\":(\\d+).*", "$1"));
    }

    static Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    private static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    /** Reads the topic from the start until {@code expected} records of {@code keys} arrived, plus a quiet period. */
    static List<ConsumerRecord<String, byte[]>> consumeKeys(Set<String> keys, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> matching = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config, new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plusSeconds(30);
            Instant quietUntil = null;
            while (Instant.now().isBefore(deadline)) {
                StreamSupport.stream(consumer.poll(Duration.ofMillis(200)).spliterator(), false)
                        .filter(r -> keys.contains(r.key()))
                        .forEach(matching::add);
                if (matching.size() >= expected) {
                    // Keep reading briefly so a duplicate would be seen too
                    quietUntil = quietUntil == null ? Instant.now().plusSeconds(2) : quietUntil;
                    if (Instant.now().isAfter(quietUntil)) {
                        break;
                    }
                }
            }
        }
        return matching;
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            JdbcClientAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            KafkaAutoConfiguration.class,
            OutboxAutoConfiguration.class,
            OutboxKafkaAutoConfiguration.class })
    static class TestApp {

        /** The owner declares its topic; it is never auto-created (TR-B1). */
        @Bean
        NewTopic testLifecycleTopic() {
            return TopicBuilder.name(TOPIC).partitions(PARTITIONS).replicas(1).build();
        }
    }
}
