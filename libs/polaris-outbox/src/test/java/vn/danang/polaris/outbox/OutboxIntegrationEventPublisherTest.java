package vn.danang.polaris.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.store.OutboxRecord;
import vn.danang.polaris.outbox.store.OutboxStore;
import vn.danang.polaris.outbox.trace.W3cTraceContext;

class OutboxIntegrationEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:15:30Z");
    private static final UUID ID = UUID.fromString("0b6f1a52-3c3e-4f0e-9a3f-1d2c3b4a5e6f");
    private static final W3cTraceContext TRACE =
            new W3cTraceContext("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");

    private final List<OutboxRecord> appended = new ArrayList<>();
    private final OutboxStore store = appended::add;
    private final OutboxIntegrationEventPublisher publisher = new OutboxIntegrationEventPublisher(
            store, new JsonMapper(), Clock.fixed(NOW, ZoneOffset.UTC), () -> ID, () -> TRACE);

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test
    void readOnlyTransaction_failsFastWithoutWriting() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThatThrownBy(() -> publisher.publish(event(new Payload("A-1", 3))))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("read-only");
        assertThat(appended).isEmpty();
    }

    @Test
    void outsideTransaction_failsFastWithoutWriting() {
        assertThatThrownBy(() -> publisher.publish(event(new Payload("A-1", 3))))
                .isInstanceOf(IllegalTransactionStateException.class)
                .hasMessageContaining("test.happened.v1");
        assertThat(appended).isEmpty();
    }

    @Test
    void insideTransaction_appendsOneRecordWithIdTimeTraceAndJsonPayload() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        UUID returned = publisher.publish(event(new Payload("A-1", 3)));

        assertThat(returned).isEqualTo(ID);
        assertThat(appended).containsExactly(new OutboxRecord(ID, "test.happened.v1", "/test", "test.topic", "A-1",
                "{\"id\":\"A-1\",\"count\":3}", TRACE.traceparent(), TRACE.tracestate(), NOW));
    }

    @Test
    void unserializablePayload_throwsSoTheBusinessChangeRollsBack() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> publisher.publish(event(new Exploding())))
                .isInstanceOf(JacksonException.class);
        assertThat(appended).isEmpty();
    }

    @Test
    void defaultConstructor_assignsDistinctRandomIds() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        OutboxIntegrationEventPublisher real = new OutboxIntegrationEventPublisher(store, new JsonMapper());

        UUID first = real.publish(event(new Payload("A-1", 1)));
        UUID second = real.publish(event(new Payload("A-1", 2)));

        assertThat(first).isNotEqualTo(second);
        assertThat(appended).extracting(OutboxRecord::eventId).containsExactly(first, second);
    }

    private static IntegrationEvent event(Object data) {
        return new IntegrationEvent("test.happened.v1", "/test", "test.topic", "A-1", data);
    }

    record Payload(String id, int count) {
    }

    static class Exploding {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }
}
