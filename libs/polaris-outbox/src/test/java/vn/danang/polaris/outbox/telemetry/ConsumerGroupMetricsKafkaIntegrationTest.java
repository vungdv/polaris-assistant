package vn.danang.polaris.outbox.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The consumer-group metric contract against a real broker: client lag metrics carry the {@code group} tag, the oldest
 * in-flight record age rises while a record is blocked and returns to 0, and a skipped poison record is counted.
 */
@Testcontainers
class ConsumerGroupMetricsKafkaIntegrationTest {

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void groupLagAgeAndSkipped_arePublishedPerGroup() throws Exception {
        String topic = "o6a-" + System.nanoTime();
        String group = "o6a.group";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        var registry = new SimpleMeterRegistry();
        var groupMetrics = new ConsumerGroupMetrics(registry, Clock.systemUTC());
        var tracker = groupMetrics.<String, String>recordInterceptor(group);

        var factory = new DefaultKafkaConsumerFactory<String, String>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer());
        factory.addListener(ConsumerGroupMetrics.clientMetrics(registry, group));

        CountDownLatch release = new CountDownLatch(1);
        var props = new ContainerProperties(topic);
        props.setGroupId(group);
        props.setMessageListener((MessageListener<String, String>) record -> {
            if (record.value().equals("blocked")) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (record.value().equals("poison")) {
                throw new IllegalStateException("poison");
            }
        });
        var container = new ConcurrentMessageListenerContainer<>(factory, props);
        container.setRecordInterceptor(tracker);
        container.setCommonErrorHandler(new DefaultErrorHandler((record, e) -> tracker.skipped(record), new FixedBackOff(10, 1)));
        container.start();
        try (var producer = new KafkaProducer<String, String>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, "k", "blocked")).get();
            producer.send(new ProducerRecord<>(topic, "k", "poison")).get();
            producer.send(new ProducerRecord<>(topic, "k", "ok")).get();

            // The first record is stuck in the handler: its age grows, and the two behind it are lag.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(registry.get(ConsumerGroupMetrics.OLDEST_RECORD_AGE).tag("group", group).gauge().value())
                            .isGreaterThan(0.0));
            // Micrometer binds the client's per-partition metrics (records-lag) on a 60 s rebind cycle after the
            // assignment, so this test proves the group tag on the client metrics bound at start; the lag series
            // itself is checked on the live stack.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(registry.getMeters()).anyMatch(m -> m.getId().getName().startsWith("kafka.consumer.")
                            && group.equals(m.getId().getTag("group"))));
            release.countDown();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var skipped = registry.find(ConsumerGroupMetrics.RECORDS_SKIPPED).tag("group", group).tag("topic", topic)
                        .counter();
                assertThat(skipped).isNotNull();
                assertThat(skipped.count()).isEqualTo(1.0);
                assertThat(registry.get(ConsumerGroupMetrics.OLDEST_RECORD_AGE).tag("group", group).gauge().value())
                        .as("drained").isZero();
            });
        } finally {
            container.stop();
        }
    }

    /**
     * A poison record that is still being retried when its container stops (no success, no skip) must not leave a
     * growing age behind. The blocked-record signal also rises while it is held, with the record being the last one
     * of the partition (nothing queued behind it).
     */
    @Test
    void containerStoppedWhileARecordIsBlocked_clearsTheInFlightState() throws Exception {
        String topic = "o6c-" + System.nanoTime();
        String group = "o6c.group";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        var registry = new SimpleMeterRegistry();
        var groupMetrics = new ConsumerGroupMetrics(registry, Clock.systemUTC());
        var tracker = groupMetrics.<String, String>recordInterceptor(group);
        var factory = new DefaultKafkaConsumerFactory<String, String>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer());
        var props = new ContainerProperties(topic);
        props.setGroupId(group);
        props.setConsumerRebalanceListener(tracker.rebalanceListener());
        props.setMessageListener((MessageListener<String, String>) record -> {
            throw new IllegalStateException("poison");
        });
        var container = new ConcurrentMessageListenerContainer<>(factory, props);
        container.setRecordInterceptor(tracker);
        container.setApplicationEventPublisher(event -> {
            if (event instanceof org.springframework.kafka.event.ConsumerStoppedEvent stopped) {
                groupMetrics.onApplicationEvent(stopped);
            }
        });
        container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(50, FixedBackOff.UNLIMITED_ATTEMPTS)));
        container.start();
        try (var producer = new KafkaProducer<String, String>(Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, "k", "poison")).get();
        }
        var blocked = registry.get(ConsumerGroupMetrics.BLOCKED_RECORD_AGE).tag("group", group).gauge();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(blocked.value()).isGreaterThan(0.0));

        container.stop();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(blocked.value()).as("cleared on stop").isZero());
        assertThat(registry.get(ConsumerGroupMetrics.OLDEST_RECORD_AGE).tag("group", group).gauge().value()).isZero();
    }
}
