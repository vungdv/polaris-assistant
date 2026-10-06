package vn.danang.polaris.outbox.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import vn.danang.polaris.outbox.store.PendingEvent;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/** Hand-off spans continue the recorded request trace, and their context is what the transport propagates. */
class HandOffTracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    private final List<SpanData> spans = new ArrayList<>();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(spans)))
            .build();
    private final HandOffTracing tracing = new HandOffTracing(openTelemetry(tracerProvider));

    @AfterEach
    void close() {
        tracerProvider.close();
    }

    @Test
    void handOffSpan_isAChildOfTheRecordedContext_andItsContextIsHandedToTheTransport() {
        PendingEvent event = event(TRACEPARENT, "vendor=abc");
        OutgoingEvent handed;
        String currentSpanId;
        try (HandOffTracing.HandOff handOff = tracing.begin(event)) {
            handed = handOff.outgoingEvent();
            currentSpanId = Span.current().getSpanContext().getSpanId();
        }

        SpanData span = spans.getFirst();
        assertThat(currentSpanId).as("the hand-off span is current while open").isEqualTo(span.getSpanId());
        assertThat(Span.current().getSpanContext().isValid()).as("and no longer once closed").isFalse();
        assertThat(span.getName()).isEqualTo("outbox publish polaris.test");
        assertThat(span.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(span.getParentSpanId()).isEqualTo(SPAN_ID);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.message.id")))
                .isEqualTo(event.eventId().toString());
        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name"))).isEqualTo("polaris.test");
        assertThat(span.getAttributes().get(AttributeKey.longKey("polaris.outbox.attempt"))).isEqualTo(3L);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);

        assertThat(handed.traceContext().traceparent()).isEqualTo("00-" + TRACE_ID + "-" + span.getSpanId() + "-01");
        assertThat(handed.traceContext().tracestate()).isEqualTo("vendor=abc");
        assertThat(handed.id()).isEqualTo(event.eventId());
        assertThat(handed.payload()).isEqualTo(event.payload());
        assertThat(handed.time()).isEqualTo(event.occurredAt());
    }

    @Test
    void failedHandOff_recordsTheExceptionAndErrorStatus() {
        try (HandOffTracing.HandOff handOff = tracing.begin(event(TRACEPARENT, null))) {
            handOff.failed(new IOException("broker unavailable"));
        }

        SpanData span = spans.getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anySatisfy(e -> assertThat(e.getName()).isEqualTo("exception"));
    }

    @Test
    void withoutARecordedContext_theHandOffStartsItsOwnTrace() {
        OutgoingEvent handed;
        try (HandOffTracing.HandOff handOff = tracing.begin(event(null, null))) {
            handed = handOff.outgoingEvent();
        }

        SpanData span = spans.getFirst();
        assertThat(span.getParentSpanContext().isValid()).isFalse();
        assertThat(handed.traceContext().traceparent()).contains(span.getTraceId());
    }

    @Test
    void withoutATracingSdk_theRecordedContextIsHandedOverUnchanged() {
        OutgoingEvent handed;
        try (HandOffTracing.HandOff handOff = new HandOffTracing(OpenTelemetry.noop()).begin(event(TRACEPARENT, "vendor=abc"))) {
            handed = handOff.outgoingEvent();
        }

        assertThat(handed.traceContext().traceparent()).isEqualTo(TRACEPARENT);
        assertThat(handed.traceContext().tracestate()).isEqualTo("vendor=abc");
    }

    private static PendingEvent event(String traceparent, String tracestate) {
        return new PendingEvent(1L, UUID.randomUUID(), "t.v1", "/s", "polaris.test", "K-1", "{\"a\":1}",
                traceparent, tracestate, Instant.parse("2026-09-29T10:00:00Z"), 2);
    }

    private static OpenTelemetry openTelemetry(TracerProvider tracerProvider) {
        return new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return tracerProvider;
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }
        };
    }

    private record CollectingExporter(List<SpanData> spans) implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
