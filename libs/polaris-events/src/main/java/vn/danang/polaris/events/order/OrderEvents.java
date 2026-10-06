package vn.danang.polaris.events.order;

/**
 * Published contract names for the Order Context's lifecycle events (EM-002 §4.1–4.2).
 * Every type carries an {@link OrderLifecycleEvent} payload and is keyed by order number.
 */
public final class OrderEvents {

    /** Logical destination (the {@code polaris.order.lifecycle} topic). Owner: Order Context. */
    public static final String DESTINATION = "polaris.order.lifecycle";

    /** CloudEvents {@code source} attribute. */
    public static final String SOURCE = "/polaris/order";

    public static final String PLACED_V1 = "vn.danang.polaris.order.placed.v1";
    public static final String CONFIRMED_V1 = "vn.danang.polaris.order.confirmed.v1";
    public static final String PARCELED_V1 = "vn.danang.polaris.order.parceled.v1";
    public static final String DELIVERING_V1 = "vn.danang.polaris.order.delivering.v1";
    public static final String DELIVERED_V1 = "vn.danang.polaris.order.delivered.v1";

    private OrderEvents() {
    }
}
