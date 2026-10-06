package vn.danang.polaris.outbox;

import java.util.Objects;

/**
 * An integration event to publish: a published contract, never a context-internal domain event (ADR-0018).
 *
 * @param type        CloudEvents {@code type}, e.g. {@code vn.danang.polaris.order.placed.v1}
 * @param source      CloudEvents {@code source}, e.g. {@code /polaris/order}
 * @param destination logical destination (a topic once a broker transport exists)
 * @param key         aggregate id; the partition and ordering key (TR-X6)
 * @param data        payload, serialized to JSON when the event is recorded
 */
public record IntegrationEvent(String type, String source, String destination, String key, Object data) {

    public IntegrationEvent {
        requireText(type, "type");
        requireText(source, "source");
        requireText(destination, "destination");
        requireText(key, "key");
        Objects.requireNonNull(data, "data");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
