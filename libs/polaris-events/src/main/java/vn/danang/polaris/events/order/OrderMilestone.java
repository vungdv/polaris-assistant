package vn.danang.polaris.events.order;

import java.util.Arrays;
import java.util.Optional;

/** The five order milestones announced on {@link OrderEvents#DESTINATION}, each with its CloudEvents type. */
public enum OrderMilestone {

    PLACED(OrderEvents.PLACED_V1),
    CONFIRMED(OrderEvents.CONFIRMED_V1),
    PARCELED(OrderEvents.PARCELED_V1),
    DELIVERING(OrderEvents.DELIVERING_V1),
    DELIVERED(OrderEvents.DELIVERED_V1);

    private final String type;

    OrderMilestone(String type) {
        this.type = type;
    }

    /** CloudEvents {@code type} for this milestone. */
    public String type() {
        return type;
    }

    /** Resolves a CloudEvents {@code type}; empty for any type that is not an order milestone. */
    public static Optional<OrderMilestone> fromType(String type) {
        return Arrays.stream(values()).filter(m -> m.type.equals(type)).findFirst();
    }
}
