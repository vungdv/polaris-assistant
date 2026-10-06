package vn.danang.polaris.outbox.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.internals.AutoOffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.MessageListenerContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ConsumerGroupMetricsTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final ConsumerGroupMetrics metrics = new ConsumerGroupMetrics(registry, clock);
    private final MockConsumer<String, String> consumer = new MockConsumer<>(AutoOffsetResetStrategy.EARLIEST.name());

    private static ConsumerRecord<String, String> record(String topic, int partition, Instant timestamp) {
        return record(topic, partition, 0L, timestamp);
    }

    private static ConsumerRecord<String, String> record(String topic, int partition, long offset, Instant timestamp) {
        return new ConsumerRecord<>(topic, partition, offset, timestamp.toEpochMilli(),
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "k", "v",
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }

    private double age(String group) {
        return registry.get(ConsumerGroupMetrics.OLDEST_RECORD_AGE).tag("group", group).gauge().value();
    }

    @Test
    void idleGroup_reportsZero() {
        metrics.<String, String>recordInterceptor("g1");
        assertThat(age("g1")).isZero();
    }

    @Test
    void inFlightRecord_setsAge_thenSuccessClearsIt_andTheOldestPartitionWins() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        var older = record("t", 0, NOW.minusSeconds(90));
        var newer = record("t", 1, NOW.minusSeconds(5));
        tracker.intercept(older, consumer);
        tracker.intercept(newer, consumer);
        assertThat(age("g1")).isEqualTo(90);

        tracker.success(older, consumer);
        assertThat(age("g1")).isEqualTo(5);
        tracker.success(newer, consumer);
        assertThat(age("g1")).isZero();
    }

    @Test
    void aFailingRecord_staysInFlight_untilItIsSkipped_whichIsCounted() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        var poison = record("t", 0, NOW.minusSeconds(60));
        tracker.intercept(poison, consumer);
        tracker.failure(poison, new IllegalStateException("boom"), consumer);
        assertThat(age("g1")).as("a failing record still blocks its partition").isEqualTo(60);

        tracker.skipped(poison);
        assertThat(age("g1")).isZero();
        assertThat(registry.get(ConsumerGroupMetrics.RECORDS_SKIPPED).tag("group", "g1").tag("topic", "t").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void groupsAreIndependent() {
        var a = metrics.<String, String>recordInterceptor("a");
        metrics.<String, String>recordInterceptor("b");
        a.intercept(record("t", 0, NOW.minusSeconds(30)), consumer);
        assertThat(age("a")).isEqualTo(30);
        assertThat(age("b")).isZero();
    }

    private double blocked(String group) {
        return registry.get(ConsumerGroupMetrics.BLOCKED_RECORD_AGE).tag("group", group).gauge().value();
    }

    // ---- Poison signal that does not depend on lag or on the record timestamp

    @Test
    void blockedAge_countsFromFirstTake_acrossRetriesOfTheSameOffset_evenForTheLastRecord() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        var last = record("t", 0, 41L, NOW); // the last record of the partition: lag reads 0 while it is held
        tracker.intercept(last, consumer);
        now.set(NOW.plusSeconds(100));
        tracker.failure(last, new IllegalStateException("boom"), consumer);
        tracker.intercept(last, consumer); // retry: the container hands the same offset again
        now.set(NOW.plusSeconds(400));

        assertThat(blocked("g1")).as("grows through the retries").isEqualTo(400);
        tracker.success(last, consumer);
        assertThat(blocked("g1")).isZero();
    }

    @Test
    void blockedAge_isNotRaisedByAnOldRecord_thatIsProcessedPromptly() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        tracker.intercept(record("t", 0, NOW.minusSeconds(3600)), consumer); // catching up on an old backlog
        assertThat(age("g1")).as("record age is the backlog signal").isEqualTo(3600);
        assertThat(blocked("g1")).as("but nothing is blocked").isZero();
    }

    @Test
    void anotherOffset_replacesTheHeldRecord() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        tracker.intercept(record("t", 0, 1L, NOW), consumer);
        now.set(NOW.plusSeconds(500));
        tracker.intercept(record("t", 0, 2L, NOW.plusSeconds(500)), consumer);
        assertThat(blocked("g1")).isZero();
    }

    @Test
    void skippingThePoisonRecord_clearsTheBlockedAge() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        var poison = record("t", 0, NOW);
        tracker.intercept(poison, consumer);
        now.set(NOW.plusSeconds(600));
        assertThat(blocked("g1")).isEqualTo(600);
        tracker.skipped(poison);
        assertThat(blocked("g1")).isZero();
    }

    // ---- In-flight state is cleared on revoke, loss and container stop

    @Test
    void revokedPartitions_dropTheirInFlightRecords_andOnlyThose() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        tracker.intercept(record("t", 0, NOW.minusSeconds(100)), consumer);
        tracker.intercept(record("t", 1, NOW.minusSeconds(50)), consumer);
        now.set(NOW.plusSeconds(1000));

        tracker.rebalanceListener().onPartitionsRevokedAfterCommit(consumer, List.of(new TopicPartition("t", 0)));

        assertThat(age("g1")).as("partition 1 is still held").isEqualTo(1050);
        assertThat(blocked("g1")).isEqualTo(1000);
        tracker.rebalanceListener().onPartitionsRevokedAfterCommit(consumer, List.of(new TopicPartition("t", 1)));
        assertThat(age("g1")).isZero();
        assertThat(blocked("g1")).isZero();
    }

    @Test
    void lostPartitions_dropTheirInFlightRecords() {
        var tracker = metrics.<String, String>recordInterceptor("g1");
        tracker.intercept(record("t", 0, NOW), consumer);
        now.set(NOW.plusSeconds(1000));
        tracker.rebalanceListener().onPartitionsLost(consumer, List.of(new TopicPartition("t", 0)));
        assertThat(blocked("g1")).isZero();
        assertThat(age("g1")).isZero();
    }

    @Test
    void containerStop_clearsThatGroupOnly() {
        var a = metrics.<String, String>recordInterceptor("a");
        var b = metrics.<String, String>recordInterceptor("b");
        a.intercept(record("t", 0, NOW), consumer);
        b.intercept(record("t", 0, NOW), consumer);
        now.set(NOW.plusSeconds(700));

        MessageListenerContainer stopped = org.mockito.Mockito.mock(MessageListenerContainer.class);
        org.mockito.Mockito.when(stopped.getGroupId()).thenReturn("a");
        metrics.onApplicationEvent(new ConsumerStoppedEvent(stopped, stopped, ConsumerStoppedEvent.Reason.NORMAL));

        assertThat(blocked("a")).isZero();
        assertThat(age("a")).isZero();
        assertThat(blocked("b")).isEqualTo(700);
    }
}
