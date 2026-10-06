package vn.danang.polaris.outbox.kafka;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;

import org.apache.kafka.clients.producer.ProducerRecord;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.kafka.KafkaMessageFactory;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Maps an outbox event to a Kafka record with the CloudEvents 1.0 Kafka protocol binding in <b>binary mode</b>
 * (ADR-0019, TR-B2), using the CloudEvents SDK rather than hand-written headers:
 * <ul>
 * <li>attributes become {@code ce_*} headers ({@code ce_specversion}, {@code ce_id}, {@code ce_type},
 * {@code ce_source}, {@code ce_time}) and {@code datacontenttype} becomes {@code content-type};</li>
 * <li>the record value is the JSON payload exactly as recorded, with no serializer type headers;</li>
 * <li>the record key is the aggregate id, so a key's events share a partition and keep their order;</li>
 * <li>the W3C {@code traceparent}/{@code tracestate} handed over by the relay are added as headers (TR-B4).</li>
 * </ul>
 */
public final class CloudEventsKafkaBinding {

    private CloudEventsKafkaBinding() {
    }

    /** The CloudEvent carried by {@code event}. */
    public static CloudEvent toCloudEvent(OutgoingEvent event) {
        return CloudEventBuilder.v1()
                .withId(event.id().toString())
                .withType(event.type())
                .withSource(URI.create(event.source()))
                .withTime(event.time().atOffset(ZoneOffset.UTC))
                .withDataContentType(OutgoingEvent.DATA_CONTENT_TYPE)
                .withData(event.payload().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    /** The binary-mode record for {@code event}: topic = destination, key = aggregate id. */
    public static ProducerRecord<String, byte[]> toRecord(OutgoingEvent event) {
        ProducerRecord<String, byte[]> record = KafkaMessageFactory
                .createWriter(event.destination(), event.key())
                .writeBinary(toCloudEvent(event));
        event.traceHeaders().forEach((name, value) -> {
            record.headers().remove(name);
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        });
        return record;
    }
}
