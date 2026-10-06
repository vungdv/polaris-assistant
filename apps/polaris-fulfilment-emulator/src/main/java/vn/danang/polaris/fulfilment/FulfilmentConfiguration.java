package vn.danang.polaris.fulfilment;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.random.RandomGenerator;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.telemetry.ConsumerGroupMetrics;

/** Wires the partners, their listeners and the shipment topic and producer (F2 design §3). */
@Configuration(proxyBeanMethods = false)
class FulfilmentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * {@link Random} lives in {@code java.base}. {@code RandomGenerator.getDefault()} is L32X64MixRandom, which is in
     * {@code jdk.random} and absent from the plain JRE the image runs on.
     */
    @Bean
    RandomGenerator claimPauseRandom(FulfilmentProperties properties) {
        return properties.randomSeed() == null ? new Random() : new Random(properties.randomSeed());
    }

    /** Timer for pauses and step delays; the listener threads never sleep. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService partnerScheduler(FulfilmentProperties properties) {
        var executor = new ScheduledThreadPoolExecutor(Math.max(2, properties.partners().size() * 2),
                Thread.ofPlatform().name("partner-scheduler-", 0).daemon().factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Fulfilment owns its topic (ADR-0019 §4.4); sized like Order's lifecycle topic. */
    @Bean
    NewTopic shipmentsTopic(FulfilmentProperties properties) {
        FulfilmentProperties.Topics topics = properties.topics();
        return TopicBuilder.name(topics.shipments())
                .partitions(topics.shipmentPartitions())
                .replicas(topics.shipmentReplicas())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(topics.shipmentMinInsyncReplicas()))
                .build();
    }

    @Bean
    ShipmentPublisher shipmentPublisher(FulfilmentProperties properties, ProducerFactory<?, ?> applicationProducerFactory,
            ObservationRegistry observationRegistry, JsonMapper mapper, FulfilmentMetrics metrics) {
        @SuppressWarnings("unchecked")
        ProducerFactory<String, byte[]> producerFactory = (ProducerFactory<String, byte[]>) applicationProducerFactory
                .copyWithConfigurationOverride(Map.of(
                        ProducerConfig.ACKS_CONFIG, "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        var template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        return new ShipmentPublisher(template, mapper, properties.topics().shipments(), metrics);
    }

    @Bean
    List<PartnerAgent> partnerAgents(FulfilmentProperties properties, RandomGenerator random,
            ScheduledExecutorService scheduler, ClaimClient claimClient, ShipmentPublisher shipments,
            FulfilmentMetrics metrics, Clock clock) {
        return properties.partners().stream()
                .map(id -> new PartnerAgent(id, properties, random, scheduler, claimClient, shipments, metrics, clock))
                .toList();
    }

    @Bean
    ConsumerGroupMetrics consumerGroupMetrics(MeterRegistry meters, Clock clock) {
        return new ConsumerGroupMetrics(meters, clock);
    }

    @Bean
    OfferListenerRegistrar offerListeners(ConsumerFactory<?, ?> applicationConsumerFactory, FulfilmentProperties properties,
            List<PartnerAgent> partnerAgents, JsonMapper mapper, FulfilmentMetrics metrics,
            ObservationRegistry observationRegistry, ConsumerGroupMetrics groupMetrics, MeterRegistry meters) {
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, byte[]> consumerFactory = (ConsumerFactory<String, byte[]>) applicationConsumerFactory;
        return new OfferListenerRegistrar(consumerFactory, properties.topics().offers(), partnerAgents,
                new OfferHandler(mapper, metrics), observationRegistry, groupMetrics, meters);
    }
}
