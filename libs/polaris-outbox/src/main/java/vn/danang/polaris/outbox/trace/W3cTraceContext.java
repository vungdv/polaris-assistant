package vn.danang.polaris.outbox.trace;

import java.util.HashMap;
import java.util.Map;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

/**
 * W3C Trace Context headers captured from an OpenTelemetry context with the standard propagator (TR-E7).
 *
 * @param traceparent {@code traceparent}, or {@code null} when there is no valid span
 * @param tracestate  {@code tracestate}, or {@code null} when empty
 */
public record W3cTraceContext(String traceparent, String tracestate) {

    public static final W3cTraceContext NONE = new W3cTraceContext(null, null);

    /** Captures the trace context of the calling thread's current span. */
    public static W3cTraceContext captureCurrent() {
        return capture(Context.current());
    }

    public static W3cTraceContext capture(Context context) {
        Map<String, String> headers = new HashMap<>(2);
        W3CTraceContextPropagator.getInstance().inject(context, headers, Map::put);
        String traceparent = headers.get("traceparent");
        if (traceparent == null) {
            return NONE;
        }
        String tracestate = headers.get("tracestate");
        return new W3cTraceContext(traceparent, tracestate == null || tracestate.isEmpty() ? null : tracestate);
    }
}
