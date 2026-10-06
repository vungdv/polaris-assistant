package vn.danang.polaris.fulfilment;

import java.util.List;
import java.util.Properties;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import vn.danang.polaris.outbox.telemetry.ConsumerGroupMetrics;

/**
 * One listener container, and so one consumer group {@code fulfilment.<partnerId>}, per partner (D3, TR-F1,
 * ADR-0019 §4.1.6): every partner independently receives every offer.
 *
 * <p><b>Starting offset (TR-F5, TR-B6):</b> {@code auto.offset.reset=earliest}. A partner group that has no committed
 * offset starts at the beginning of the topic, so orders placed before the emulator first ran are still offered
 * (already-claimed ones just answer 409). See F2 design §6.
 */
class OfferListenerRegistrar implements SmartLifecycle {

    static final String GROUP_PREFIX = "fulfilment.";

    private static final Logger log = LoggerFactory.getLogger(OfferListenerRegistrar.class);

    private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers;
    private boolean running;

    OfferListenerRegistrar(ConsumerFactory<String, byte[]> consumerFactory, String topic, List<PartnerAgent> partners,
            OfferHandler handler, ObservationRegistry observationRegistry, ConsumerGroupMetrics groupMetrics,
            MeterRegistry meters) {
        this.containers = partners.stream()
                .map(partner -> container(consumerFactory, topic, partner, handler, observationRegistry, groupMetrics, meters))
                .toList();
    }

    private static ConcurrentMessageListenerContainer<String, byte[]> container(ConsumerFactory<String, byte[]> consumerFactory,
            String topic, PartnerAgent partner, OfferHandler handler,
            ObservationRegistry observationRegistry, ConsumerGroupMetrics groupMetrics, MeterRegistry meters) {
        var properties = new ContainerProperties(topic);
        String group = GROUP_PREFIX + partner.partnerId();
        properties.setGroupId(group);
        // Consumer-group metrics: one factory per group so the client lag metrics carry the group tag; oldest in-flight record age.
        var groupFactory = new DefaultKafkaConsumerFactory<>(consumerFactory.getConfigurationProperties(),
                consumerFactory.getKeyDeserializer(), consumerFactory.getValueDeserializer());
        groupFactory.addListener(ConsumerGroupMetrics.clientMetrics(meters, group));
        var tracker = groupMetrics.<String, byte[]>recordInterceptor(group);
        properties.setConsumerRebalanceListener(tracker.rebalanceListener()); // revoked partitions drop in-flight state
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setObservationEnabled(true);
        properties.setObservationRegistry(observationRegistry);
        var overrides = new Properties();
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.setKafkaConsumerProperties(overrides);
        properties.setMessageListener((MessageListener<String, byte[]>) record -> handler.handle(partner, record));
        var container = new ConcurrentMessageListenerContainer<>(groupFactory, properties);
        container.setRecordInterceptor(tracker);
        // Not a context bean, so no event publisher is injected: hand the stop event to the metrics directly.
        container.setApplicationEventPublisher(event -> {
            if (event instanceof ConsumerStoppedEvent stopped) {
                groupMetrics.onApplicationEvent(stopped);
            }
        });
        container.setBeanName("fulfilment-" + partner.partnerId());
        // A poison offer never blocks the partition: two quick retries, then ERROR with its coordinates, and skip.
        container.setCommonErrorHandler(new DefaultErrorHandler((record, e) -> {
            tracker.skipped(record);
            log.error("Offer skipped after retries partnerId={} topic={} partition={} offset={} error={}",
                    partner.partnerId(), record.topic(), record.partition(), record.offset(), e.toString());
        }, new FixedBackOff(500, 2)));
        return container;
    }

    @Override
    public void start() {
        containers.forEach(ConcurrentMessageListenerContainer::start);
        running = true;
    }

    @Override
    public void stop() {
        containers.forEach(ConcurrentMessageListenerContainer::stop);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
