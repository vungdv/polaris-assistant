package vn.danang.polaris.outbox.transport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import vn.danang.polaris.outbox.trace.W3cTraceContext;

/**
 * What a transport receives: the CloudEvents 1.0 attributes of a recorded event, its logical destination and
 * partition key, the JSON payload as recorded, and the W3C trace context to propagate.
 *
 * @param id           CloudEvents {@code id} ({@code ce_id}), the same on every attempt (TR-E4)
 * @param type         CloudEvents {@code type}
 * @param source       CloudEvents {@code source}
 * @param time         CloudEvents {@code time}: when the event was recorded
 * @param destination  logical destination (a topic in Plan 2)
 * @param key          aggregate id: the partition and ordering key (TR-X6)
 * @param payload      JSON {@code data}, exactly as recorded ({@value #DATA_CONTENT_TYPE})
 * @param traceContext trace context to propagate: the relay's hand-off span, a child of the raising request (TR-E7)
 */
public record OutgoingEvent(
        UUID id,
        String type,
        String source,
        Instant time,
        String destination,
        String key,
        String payload,
        W3cTraceContext traceContext) {

    /** CloudEvents {@code datacontenttype} of every payload. */
    public static final String DATA_CONTENT_TYPE = "application/json";

    public OutgoingEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        traceContext = traceContext == null ? W3cTraceContext.NONE : traceContext;
    }

    /** W3C Trace Context headers ({@code traceparent}, {@code tracestate}) to attach; absent values are omitted. */
    public Map<String, String> traceHeaders() {
        Map<String, String> headers = new LinkedHashMap<>(2);
        if (traceContext.traceparent() != null) {
            headers.put("traceparent", traceContext.traceparent());
            if (traceContext.tracestate() != null) {
                headers.put("tracestate", traceContext.tracestate());
            }
        }
        return Map.copyOf(headers);
    }
}
