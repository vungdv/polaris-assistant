package vn.danang.polaris.outbox.transport;

/**
 * Transport port of the outbox relay (ADR-0018, E3 design §3). A broker adapter (Kafka in Plan 2) implements
 * it; the relay, the publish port and the storage never change for a new transport (TR-E5).
 *
 * <p>Contract: {@link #send} returns only once the transport has durably accepted the event (e.g. the broker
 * acknowledged it) and bounds its own duration with a timeout. Any exception is a failed attempt: the event
 * stays pending and is retried with backoff. Delivery is at-least-once, so the same {@link OutgoingEvent#id()}
 * may be sent more than once.
 */
@FunctionalInterface
public interface EventTransport {

    /** Hands one event to the transport. Throws if the transport did not accept it. */
    void send(OutgoingEvent event) throws Exception;
}
