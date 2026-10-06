package vn.danang.polaris.order.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

import io.cloudevents.rw.CloudEventRWException;
import tools.jackson.core.exc.StreamReadException;

/**
 * Messaging-stability S1: the shipment listener's error handler recovers (skips) a non-retryable failure on the first
 * attempt and retries a transient one. The handler is driven through its {@code CommonErrorHandler} seam as the
 * container drives it: the listener's exception arrives wrapped in {@link ListenerExecutionFailedException}.
 */
class ShipmentErrorClassificationTest {

    private final ConsumerRecord<String, byte[]> record = new ConsumerRecord<>("shipments", 0, 7L, "ORD-1", new byte[0]);
    private final List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
    private DefaultErrorHandler handler;

    @BeforeEach
    void setUp() {
        // One retry with no delay: a retryable failure is recovered only on its second attempt.
        handler = ShipmentListenerConfiguration.errorHandler((r, e) -> recovered.add(r), new FixedBackOff(0, 1));
    }

    /** One delivery attempt failing with {@code cause}; true when the handler recovered (skipped) the record. */
    private boolean fails(Exception cause) {
        return handler.handleOne(new ListenerExecutionFailedException("listener failed", cause), record,
                mock(Consumer.class), mock(MessageListenerContainer.class));
    }

    @Test
    @DisplayName("Malformed CloudEvent, unparseable JSON or an invalid argument → skipped on the first attempt")
    void nonRetryable_recoveredAtOnce() {
        assertThat(fails(CloudEventRWException.newInvalidSpecVersion("0.1"))).isTrue();
        assertThat(fails(new StreamReadException(null, "unexpected token"))).isTrue();
        assertThat(fails(new IllegalArgumentException("unknown step"))).isTrue();
        assertThat(recovered).hasSize(3);
    }

    @Test
    @DisplayName("Lock timeout, query timeout or lost connection → retried before it is skipped")
    void transient_retriedFirst() {
        for (Exception e : List.of(new CannotAcquireLockException("lock wait"), new QueryTimeoutException("timeout"),
                new DataAccessResourceFailureException("connection refused"))) {
            assertThat(fails(e)).as("first attempt of %s", e).isFalse();
            assertThat(fails(e)).as("after the retry of %s", e).isTrue();
        }
        assertThat(recovered).hasSize(3);
    }

    @Test
    @DisplayName("Transient DB error caused by an IllegalArgumentException → still retried (outermost match wins)")
    void transientWrappingNonRetryable_retried() {
        var e = new DataAccessResourceFailureException("connection lost", new IllegalArgumentException("bad socket"));

        assertThat(fails(e)).isFalse();
        assertThat(recovered).isEmpty();
    }

    @Test
    @DisplayName("Unclassified failure → default policy, retried")
    void unclassified_retried() {
        assertThat(fails(new IllegalStateException("bug"))).isFalse();
    }
}
