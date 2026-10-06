package vn.danang.polaris.events.fulfilment;

/**
 * Published contract names for the Fulfilment Context's shipment events (EM-002 §4.1–4.2).
 * Every type carries a {@link ShipmentEvent} payload and is keyed by order number.
 */
public final class FulfilmentEvents {

    /** Logical destination (the {@code polaris.fulfilment.shipments} topic). Owner: Fulfilment Context. */
    public static final String DESTINATION = "polaris.fulfilment.shipments";

    /** CloudEvents {@code source} attribute. */
    public static final String SOURCE = "/polaris/fulfilment";

    public static final String SHIPMENT_PACKED_V1 = "vn.danang.polaris.fulfilment.shipment.packed.v1";
    public static final String SHIPMENT_DISPATCHED_V1 = "vn.danang.polaris.fulfilment.shipment.dispatched.v1";
    public static final String SHIPMENT_DELIVERED_V1 = "vn.danang.polaris.fulfilment.shipment.delivered.v1";

    private FulfilmentEvents() {
    }
}
