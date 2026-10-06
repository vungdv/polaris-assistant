package vn.danang.polaris.order.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import vn.danang.polaris.events.order.OrderEvents;

/**
 * Order owns and provisions its lifecycle topic {@value OrderEvents#DESTINATION} (TR-B5, ADR-0019); the broker
 * never auto-creates topics (TR-B1). Spring Kafka's {@code KafkaAdmin} creates it at startup, or adds partitions
 * if it has fewer; the outbox's Kafka transport retries that provisioning if the broker was down at startup.
 *
 * <p>Every event is keyed by order number, so an order's events share one partition and keep their order, while
 * several partitions let the consumers of one group process different orders in parallel.
 *
 * <p>On the three-node dev cluster each partition has 3 replicas, and a write (the transport sends with
 * {@code acks=all}) needs 2 of them in sync: any one broker can be down without losing or refusing an event.
 * Replication is fixed at creation; {@code KafkaAdmin} never changes it on an existing topic.
 */
@Configuration(proxyBeanMethods = false)
public class OrderLifecycleTopicConfiguration {

    @Bean
    NewTopic orderLifecycleTopic(
            @Value("${polaris.order.lifecycle-topic.partitions:3}") int partitions,
            @Value("${polaris.order.lifecycle-topic.replicas:3}") short replicas,
            @Value("${polaris.order.lifecycle-topic.min-insync-replicas:2}") int minInsyncReplicas) {
        return TopicBuilder.name(OrderEvents.DESTINATION)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInsyncReplicas))
                .build();
    }
}
