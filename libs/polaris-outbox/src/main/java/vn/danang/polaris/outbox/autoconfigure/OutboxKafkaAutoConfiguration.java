package vn.danang.polaris.outbox.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import io.cloudevents.kafka.KafkaMessageFactory;
import io.micrometer.observation.ObservationRegistry;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.transport.EventTransport;

/**
 * Contributes the Kafka {@link EventTransport} to the outbox relay (Plan 2, ADR-0019) in apps that have the outbox,
 * Spring Kafka and the CloudEvents Kafka binding. It reuses the application's Kafka connection settings (Spring
 * Boot's producer factory, so {@code spring.kafka.*} and service connections apply) and overrides only what the
 * transport's guarantees depend on ({@link KafkaEventTransport#producerOverrides}). Opt out with
 * {@code polaris.outbox.kafka.enabled=false}.
 */
@AutoConfiguration(after = OutboxAutoConfiguration.class,
        afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({ KafkaTemplate.class, KafkaMessageFactory.class })
@ConditionalOnBean({ OutboxRelayStore.class, ProducerFactory.class })
@ConditionalOnBooleanProperty(name = "polaris.outbox.kafka.enabled", matchIfMissing = true)
public class OutboxKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventTransport.class)
    KafkaEventTransport kafkaEventTransport(OutboxProperties properties, ProducerFactory<?, ?> applicationProducerFactory,
            ObjectProvider<KafkaAdmin> admin, ObjectProvider<ObservationRegistry> observationRegistry) {
        var sendTimeout = properties.kafka().sendTimeout();
        @SuppressWarnings("unchecked")
        ProducerFactory<String, byte[]> producerFactory = (ProducerFactory<String, byte[]>) applicationProducerFactory
                .copyWithConfigurationOverride(KafkaEventTransport.producerOverrides(sendTimeout));
        if (producerFactory instanceof DefaultKafkaProducerFactory<String, byte[]> factory) {
            factory.setBeanName("polarisOutboxKafkaProducerFactory");
        }
        KafkaTemplate<String, byte[]> template = new KafkaTemplate<>(producerFactory);
        // Spring Kafka's producer span, a child of the relay's hand-off span, which propagates its own traceparent.
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
        return new KafkaEventTransport(template, admin.getIfUnique(), sendTimeout);
    }
}
