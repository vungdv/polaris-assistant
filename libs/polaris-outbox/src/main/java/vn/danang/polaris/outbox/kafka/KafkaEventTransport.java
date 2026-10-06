package vn.danang.polaris.outbox.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Kafka implementation of the outbox transport port (ADR-0019). Each event is sent as a binary-mode CloudEvent
 * ({@link CloudEventsKafkaBinding}) to the topic named by its destination, keyed by its aggregate id.
 *
 * <p><b>Delivery (TR-B3).</b> {@link #send} returns only once the broker acknowledged the record with
 * {@code acks=all} on an idempotent producer, so an acknowledged event is durable and the producer's own retries
 * create neither duplicates nor reordering within a partition. Together with the relay's one-in-flight-per-key rule
 * this keeps a key's events in commit order. Every send is bounded by {@code sendTimeout} (metadata wait plus
 * delivery), as the port requires; a timeout or broker error is a failed attempt that the relay retries with its
 * backoff.
 *
 * <p><b>Tracing (TR-B4).</b> The relay's hand-off span is current during {@code send}, and its context is put in
 * the {@code traceparent}/{@code tracestate} headers. With observation enabled on the template, Spring Kafka's
 * producer span is created as a child of the hand-off span and replaces those headers with its own context, so
 * consumers continue the raising request's trace either way.
 *
 * <p><b>Topics (TR-B1, TR-B5).</b> Topics are never auto-created: owners declare them as {@code NewTopic} beans,
 * which {@link KafkaAdmin} provisions at startup. If the broker was unavailable then, the first send provisions
 * them before producing, so events flow as soon as Kafka returns (TR-B7).
 */
public class KafkaEventTransport implements EventTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventTransport.class);

    private final KafkaTemplate<String, byte[]> template;
    private final KafkaAdmin admin;
    private final Duration sendTimeout;
    private volatile boolean topicsProvisioned;

    /**
     * @param template    a template over a producer configured with {@link #producerOverrides(Duration)}
     * @param admin       provisions the declared topics, or {@code null} to leave provisioning to others
     * @param sendTimeout upper bound of each phase of a send (metadata wait, then broker acknowledgement)
     */
    public KafkaEventTransport(KafkaTemplate<String, byte[]> template, KafkaAdmin admin, Duration sendTimeout) {
        this.template = Objects.requireNonNull(template, "template");
        this.admin = admin;
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        this.topicsProvisioned = admin == null;
    }

    /**
     * Producer settings this transport relies on, applied over the application's Kafka producer properties:
     * durable, idempotent, ordered delivery (TR-B3), bounded sends, and a raw {@code byte[]} value so the record
     * carries no serializer type headers (TR-B2).
     */
    public static Map<String, Object> producerOverrides(Duration sendTimeout) {
        int timeoutMs = Math.toIntExact(sendTimeout.toMillis());
        return Map.of(
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
                ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE,
                ProducerConfig.LINGER_MS_CONFIG, 0,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeoutMs,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, timeoutMs,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    }

    @Override
    public void send(OutgoingEvent event) throws Exception {
        provisionTopicsOnce();
        ProducerRecord<String, byte[]> record = CloudEventsKafkaBinding.toRecord(event);
        // The producer bounds the metadata wait and the delivery by sendTimeout; the extra second only guards
        // the future against a lost completion.
        RecordMetadata metadata = template.send(record)
                .get(sendTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS)
                .getRecordMetadata();
        log.debug("Kafka record sent ce_id={} ce_type={} key={} topic={} partition={} offset={}",
                event.id(), event.type(), event.key(), metadata.topic(), metadata.partition(), metadata.offset());
    }

    private void provisionTopicsOnce() {
        if (!topicsProvisioned) {
            // Throws while the broker is unavailable: a failed attempt, retried with the relay's backoff.
            admin.initialize();
            topicsProvisioned = true;
        }
    }

    KafkaTemplate<String, byte[]> template() {
        return template;
    }

    @Override
    public void close() {
        template.getProducerFactory().reset();
    }
}
