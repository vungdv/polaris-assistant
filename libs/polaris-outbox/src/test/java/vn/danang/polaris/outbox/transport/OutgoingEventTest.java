package vn.danang.polaris.outbox.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.danang.polaris.outbox.trace.W3cTraceContext;

class OutgoingEventTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void traceHeaders_carryTraceparentAndTracestate() {
        OutgoingEvent event = event(new W3cTraceContext(TRACEPARENT, "vendor=1"));

        assertThat(event.traceHeaders()).isEqualTo(Map.of("traceparent", TRACEPARENT, "tracestate", "vendor=1"));
    }

    @Test
    void traceHeaders_omitAbsentValues() {
        assertThat(event(new W3cTraceContext(TRACEPARENT, null)).traceHeaders())
                .isEqualTo(Map.of("traceparent", TRACEPARENT));
        assertThat(event(null).traceHeaders()).isEmpty();
        assertThat(event(null).traceContext()).isEqualTo(W3cTraceContext.NONE);
    }

    @Test
    void requiredAttributes_areValidated() {
        assertThatThrownBy(() -> new OutgoingEvent(null, "t", "/s", Instant.now(), "d", "k", "{}", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutgoingEvent(UUID.randomUUID(), "t", "/s", Instant.now(), "d", null, "{}", null))
                .isInstanceOf(NullPointerException.class);
    }

    private static OutgoingEvent event(W3cTraceContext trace) {
        return new OutgoingEvent(UUID.randomUUID(), "t.v1", "/s", Instant.now(), "dest", "key", "{}", trace);
    }
}
