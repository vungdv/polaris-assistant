package vn.danang.polaris.outbox.telemetry;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.ApplicationListener;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RecordInterceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Per consumer-group Kafka metrics. Two parts, both labelled {@code group}:
 * <ul>
 * <li>{@link #clientMetrics(MeterRegistry, String)}: the Kafka client's own consumer metrics (notably
 * {@code kafka.consumer.fetch.manager.records.lag} per topic and partition), tagged with the group so lag is
 * addressable by group instead of by a client id.</li>
 * <li>{@link #recordInterceptor(String)}: {@code polaris.kafka.consumer.oldest.record.age}, the age of the oldest
 * record the group's consumer has taken but not finished (Kafka clients expose no record age), and
 * {@code polaris.kafka.consumer.records.skipped}, records given up on after retries.</li>
 * <li>{@code polaris.kafka.consumer.blocked.record.age}: how long the oldest unfinished record has been in the
 * consumer's hands, retries included. Unlike the age above it does not depend on the record's timestamp or on lag, so
 * it detects a blocked last record at lag 0.</li>
 * </ul>
 * One instance serves one registry; call {@link #recordInterceptor(String)} once per group. As an
 * {@link ApplicationListener} it also clears a group's in-flight state when its container stops.
 */
public class ConsumerGroupMetrics implements ApplicationListener<ConsumerStoppedEvent> {

    public static final String OLDEST_RECORD_AGE = "polaris.kafka.consumer.oldest.record.age";
    public static final String BLOCKED_RECORD_AGE = "polaris.kafka.consumer.blocked.record.age";
    public static final String RECORDS_SKIPPED = "polaris.kafka.consumer.records.skipped";
    public static final String GROUP = "group";

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, GroupTracker<?, ?>> trackers = new ConcurrentHashMap<>();

    public ConsumerGroupMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** Client metrics of every consumer created by the factory this listener is added to, tagged with the group. */
    public static <K, V> MicrometerConsumerListener<K, V> clientMetrics(MeterRegistry registry, String group) {
        return new MicrometerConsumerListener<>(registry, List.of(new ImmutableTag(GROUP, group)));
    }

    public <K, V> GroupTracker<K, V> recordInterceptor(String group) {
        GroupTracker<K, V> tracker = new GroupTracker<>(group);
        trackers.put(group, tracker);
        return tracker;
    }

    /** A container stopped: nothing it had in flight is in flight any more. */
    @Override
    public void onApplicationEvent(ConsumerStoppedEvent event) {
        MessageListenerContainer container = event.getContainer(MessageListenerContainer.class);
        String group = container == null ? null : container.getGroupId();
        GroupTracker<?, ?> tracker = group == null ? null : trackers.get(group);
        if (tracker != null) {
            tracker.clear();
        }
    }

    /**
     * Tracks in-flight records of one group. A record is in flight from the moment the container hands it to the
     * listener until it succeeds or is skipped; a failing record stays in flight while it is retried, so a poison
     * record that blocks a partition makes the age grow. State is also cleared when the partition is revoked or lost
     * ({@link #rebalanceListener()}) and when the container stops ({@link #clear()}), so a record that is no longer
     * this consumer's cannot keep the age growing.
     */
    public final class GroupTracker<K, V> implements RecordInterceptor<K, V> {

        private final String group;
        private final Map<TopicPartition, InFlight> inFlight = new ConcurrentHashMap<>();

        private GroupTracker(String group) {
            this.group = group;
            Gauge.builder(OLDEST_RECORD_AGE, this, GroupTracker::oldestAgeSeconds)
                    .description("Age of the oldest record taken by the consumer group and not yet processed; 0 when idle")
                    .baseUnit("seconds")
                    .tag(GROUP, group)
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(BLOCKED_RECORD_AGE, this, GroupTracker::blockedAgeSeconds)
                    .description("How long the oldest unfinished record has been held by the consumer group, retries included; 0 when idle")
                    .baseUnit("seconds")
                    .tag(GROUP, group)
                    .strongReference(true)
                    .register(registry);
        }

        /** Revoke and loss callbacks for {@link ContainerProperties#setConsumerRebalanceListener}. */
        public ConsumerAwareRebalanceListener rebalanceListener() {
            return new ConsumerAwareRebalanceListener() {
                @Override
                public void onPartitionsRevokedAfterCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                    partitions.forEach(inFlight::remove);
                }

                @Override
                public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                    partitions.forEach(inFlight::remove);
                }
            };
        }

        /** Forgets everything in flight (container stopped). */
        public void clear() {
            inFlight.clear();
        }

        @Override
        public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record,
                Consumer<K, V> consumer) {
            // A retry hands the same offset again: it keeps its first-taken time, so the blocked age keeps growing.
            inFlight.compute(new TopicPartition(record.topic(), record.partition()), (partition, held) ->
                    held != null && held.offset() == record.offset()
                            ? held
                            : new InFlight(record.offset(), record.timestamp(), clock.millis()));
            return record;
        }

        @Override
        public void success(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
            inFlight.remove(new TopicPartition(record.topic(), record.partition()));
        }

        /** The error handler gave up on this record and moved on: it no longer blocks the partition. */
        public void skipped(ConsumerRecord<?, ?> record) {
            inFlight.remove(new TopicPartition(record.topic(), record.partition()));
            Counter.builder(RECORDS_SKIPPED)
                    .description("Records skipped by the consumer group after exhausting retries")
                    .baseUnit("records")
                    .tag(GROUP, group)
                    .tag("topic", record.topic())
                    .register(registry)
                    .increment();
        }

        double oldestAgeSeconds() {
            long now = clock.millis();
            return inFlight.values().stream()
                    .mapToLong(held -> Math.max(0, now - held.timestamp()))
                    .max()
                    .orElse(0) / 1000.0;
        }

        double blockedAgeSeconds() {
            long now = clock.millis();
            return inFlight.values().stream()
                    .mapToLong(held -> Math.max(0, now - held.takenAtMillis()))
                    .max()
                    .orElse(0) / 1000.0;
        }
    }

    private record InFlight(long offset, long timestamp, long takenAtMillis) {
    }
}
