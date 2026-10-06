package vn.danang.polaris.events.fulfilment;

import java.util.Arrays;
import java.util.Optional;

/** The three shipment steps reported on {@link FulfilmentEvents#DESTINATION}, each with its CloudEvents type. */
public enum ShipmentStep {

    PACKED(FulfilmentEvents.SHIPMENT_PACKED_V1),
    DISPATCHED(FulfilmentEvents.SHIPMENT_DISPATCHED_V1),
    DELIVERED(FulfilmentEvents.SHIPMENT_DELIVERED_V1);

    private final String type;

    ShipmentStep(String type) {
        this.type = type;
    }

    /** CloudEvents {@code type} for this step. */
    public String type() {
        return type;
    }

    /** Resolves a CloudEvents {@code type}; empty for any type that is not a shipment step. */
    public static Optional<ShipmentStep> fromType(String type) {
        return Arrays.stream(values()).filter(s -> s.type.equals(type)).findFirst();
    }
}
