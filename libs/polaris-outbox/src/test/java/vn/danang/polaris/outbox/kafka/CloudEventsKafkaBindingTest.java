package vn.danang.polaris.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.KafkaMessageFactory;
import vn.danang.polaris.outbox.trace.W3cTraceContext;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

class CloudEventsKafkaBindingTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final UUID ID = UUID.fromString("0b6f0a58-7d7f-4a8a-9d8e-3b2d6c1f0e11");
    private static final Instant TIME = Instant.parse("2026-09-29T10:15:30.123Z");

    private OutgoingEvent event(W3cTraceContext trace) {
        return new OutgoingEvent(ID, "vn.danang.polaris.order.placed.v1", "/polaris/order", TIME,
                "polaris.order.lifecycle", "ORD-1", "{\"orderNumber\":\"ORD-1\"}", trace);
    }

    private static Map<String, String> headers(ProducerRecord<String, byte[]> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            assertThat(headers).as("header %s appears once", header.key()).doesNotContainKey(header.key());
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    @Test
    void binaryMode_attributesAsCeHeaders_payloadAsValue_keyedByAggregate() {
        ProducerRecord<String, byte[]> record = CloudEventsKafkaBinding.toRecord(
                event(new W3cTraceContext(TRACEPARENT, "polaris=t1")));

        assertThat(record.topic()).isEqualTo("polaris.order.lifecycle");
        assertThat(record.key()).isEqualTo("ORD-1");
        assertThat(record.partition()).isNull();
        assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo("{\"orderNumber\":\"ORD-1\"}");
        assertThat(headers(record)).containsOnly(
                Map.entry("ce_specversion", "1.0"),
                Map.entry("ce_id", ID.toString()),
                Map.entry("ce_type", "vn.danang.polaris.order.placed.v1"),
                Map.entry("ce_source", "/polaris/order"),
                Map.entry("ce_time", "2026-09-29T10:15:30.123Z"),
                Map.entry("content-type", "application/json"),
                Map.entry("traceparent", TRACEPARENT),
                Map.entry("tracestate", "polaris=t1"));
    }

    @Test
    void withoutTraceContext_noTraceHeaders() {
        assertThat(headers(CloudEventsKafkaBinding.toRecord(event(W3cTraceContext.NONE))))
                .doesNotContainKeys("traceparent", "tracestate");
    }

    @Test
    void cloudEventsSdkReadsTheRecordBack() {
        ProducerRecord<String, byte[]> record = CloudEventsKafkaBinding.toRecord(event(W3cTraceContext.NONE));

        CloudEvent read = KafkaMessageFactory.createReader(record.headers(), record.value()).toEvent();

        assertThat(read).isEqualTo(CloudEventsKafkaBinding.toCloudEvent(event(W3cTraceContext.NONE)));
        assertThat(read.getTime().toInstant()).isEqualTo(TIME);
    }

    @Test
    void producerOverrides_durableIdempotentOrderedAndBounded() {
        Map<String, Object> overrides = KafkaEventTransport.producerOverrides(Duration.ofSeconds(7));

        assertThat(overrides)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 7000)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 7000)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        // Kafka rejects idempotence unless delivery.timeout.ms >= linger.ms + request.timeout.ms
        assertThat((int) overrides.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isGreaterThanOrEqualTo(
                (int) overrides.get(ProducerConfig.LINGER_MS_CONFIG)
                        + (int) overrides.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG));
    }
}
