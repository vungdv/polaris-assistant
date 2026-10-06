package vn.danang.polaris.outbox.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

class W3cTraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void currentSampledSpan_isCapturedAsTraceparent() {
        SpanContext span = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope ignored = Span.wrap(span).makeCurrent()) {
            assertThat(W3cTraceContext.captureCurrent())
                    .isEqualTo(new W3cTraceContext("00-" + TRACE_ID + "-" + SPAN_ID + "-01", null));
        }
    }

    @Test
    void tracestate_isCapturedWhenPresent() {
        SpanContext span = SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getDefault(),
                TraceState.builder().put("vendor", "value").build());

        W3cTraceContext captured = W3cTraceContext.capture(Context.root().with(Span.wrap(span)));

        assertThat(captured.traceparent()).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
        assertThat(captured.tracestate()).isEqualTo("vendor=value");
    }

    @Test
    void noSpan_capturesNothing() {
        assertThat(W3cTraceContext.capture(Context.root())).isEqualTo(W3cTraceContext.NONE);
        assertThat(W3cTraceContext.captureCurrent()).isEqualTo(W3cTraceContext.NONE);
    }
}
