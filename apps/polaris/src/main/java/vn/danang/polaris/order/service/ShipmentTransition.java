package vn.danang.polaris.order.service;

import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.order.entity.OrderStatus;

/**
 * The shipment-report transition table (TR-O4): a report advances an order only when it comes from the assigned
 * partner and the order is in the expected previous status. Everything else is a no-op with a reason.
 *
 * <pre>
 *   PACKED     CONFIRMED  → PARCELED
 *   DISPATCHED PARCELED   → DELIVERING
 *   DELIVERED  DELIVERING → DELIVERED
 * </pre>
 */
public final class ShipmentTransition {

    /** Why a report changed nothing; {@link #tag()} is the metric/log value. */
    public enum Reason {
        UNKNOWN_ORDER, CANCELLED, NOT_CLAIMED, WRONG_PARTNER, DUPLICATE, LATE, OUT_OF_ORDER;

        public String tag() {
            return name().toLowerCase();
        }
    }

    /** Either advance to {@code next}, or ignore for {@code reason}. */
    public record Decision(OrderStatus next, Reason reason) {
        public boolean applies() {
            return next != null;
        }
    }

    private ShipmentTransition() {
    }

    public static OrderStatus expectedPrevious(ShipmentStep step) {
        return switch (step) {
            case PACKED -> OrderStatus.CONFIRMED;
            case DISPATCHED -> OrderStatus.PARCELED;
            case DELIVERED -> OrderStatus.DELIVERING;
        };
    }

    public static OrderStatus target(ShipmentStep step) {
        return switch (step) {
            case PACKED -> OrderStatus.PARCELED;
            case DISPATCHED -> OrderStatus.DELIVERING;
            case DELIVERED -> OrderStatus.DELIVERED;
        };
    }

    public static Decision decide(OrderStatus status, String assignedPartner, ShipmentStep step, String reportingPartner) {
        if (status == OrderStatus.CANCELLED) {
            return ignore(Reason.CANCELLED);
        }
        if (status == OrderStatus.PLACED || assignedPartner == null) {
            return ignore(Reason.NOT_CLAIMED);
        }
        if (!assignedPartner.equals(reportingPartner)) {
            return ignore(Reason.WRONG_PARTNER);
        }
        if (status == expectedPrevious(step)) {
            return new Decision(target(step), null);
        }
        int current = status.ordinal();
        int reported = target(step).ordinal();
        if (current == reported) {
            return ignore(Reason.DUPLICATE);
        }
        return ignore(current > reported ? Reason.LATE : Reason.OUT_OF_ORDER);
    }

    private static Decision ignore(Reason reason) {
        return new Decision(null, reason);
    }
}
