package vn.danang.polaris.outbox.telemetry;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import vn.danang.polaris.outbox.store.PendingEvent;
import vn.danang.polaris.outbox.trace.W3cTraceContext;
import vn.danang.polaris.outbox.transport.OutgoingEvent;

/**
 * Hand-off spans (E3 design §7): each hand-off runs in a PRODUCER span whose parent is the trace context
 * recorded with the event (TR-E7), not the relay thread's context. The span's context is what the transport
 * receives. Without a tracing SDK the span is non-recording and the recorded context is handed over unchanged.
 */
public class HandOffTracing {

    static final String INSTRUMENTATION_SCOPE = "vn.danang.polaris.outbox";

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final Tracer tracer;

    public HandOffTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * Starts the hand-off span of {@code event} and makes it current until the returned handle is closed, so
     * the transport call, the delivery bookkeeping and their logs all carry its {@code trace_id}/{@code span_id}.
     */
    public HandOff begin(PendingEvent event) {
        Span span = tracer.spanBuilder("outbox publish " + event.destination())
                .setParent(recordedContext(event))
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("messaging.destination.name", event.destination())
                .setAttribute("messaging.message.id", event.eventId().toString())
                .setAttribute("polaris.outbox.event_key", event.key())
                .setAttribute("polaris.outbox.attempt", event.attempts() + 1L)
                .startSpan();
        Scope scope = span.makeCurrent();
        OutgoingEvent outgoing = new OutgoingEvent(event.eventId(), event.type(), event.source(), event.occurredAt(),
                event.destination(), event.key(), event.payload(), W3cTraceContext.captureCurrent());
        return new HandOff(span, scope, outgoing);
    }

    static Context recordedContext(PendingEvent event) {
        if (event.traceparent() == null) {
            return Context.root();
        }
        Map<String, String> carrier = new HashMap<>(2);
        carrier.put("traceparent", event.traceparent());
        if (event.tracestate() != null) {
            carrier.put("tracestate", event.tracestate());
        }
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, GETTER);
    }

    /** An open hand-off span, current on this thread until closed. */
    public static final class HandOff implements AutoCloseable {

        private final Span span;
        private final Scope scope;
        private final OutgoingEvent outgoingEvent;

        private HandOff(Span span, Scope scope, OutgoingEvent outgoingEvent) {
            this.span = span;
            this.scope = scope;
            this.outgoingEvent = outgoingEvent;
        }

        /** The event to give the transport, carrying this span's trace context. */
        public OutgoingEvent outgoingEvent() {
            return outgoingEvent;
        }

        /** Records a failed hand-off on the span. */
        public void failed(Throwable failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getClass().getSimpleName());
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
