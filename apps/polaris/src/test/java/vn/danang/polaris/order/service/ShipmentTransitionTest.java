package vn.danang.polaris.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import vn.danang.polaris.events.fulfilment.ShipmentStep;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.service.ShipmentTransition.Decision;
import vn.danang.polaris.order.service.ShipmentTransition.Reason;

import static vn.danang.polaris.events.fulfilment.ShipmentStep.DELIVERED;
import static vn.danang.polaris.events.fulfilment.ShipmentStep.DISPATCHED;
import static vn.danang.polaris.events.fulfilment.ShipmentStep.PACKED;
import static vn.danang.polaris.order.entity.OrderStatus.CONFIRMED;
import static vn.danang.polaris.order.entity.OrderStatus.DELIVERING;
import static vn.danang.polaris.order.entity.OrderStatus.PARCELED;
import static vn.danang.polaris.order.entity.OrderStatus.PLACED;

@DisplayName("Shipment transition table (TR-O4)")
class ShipmentTransitionTest {

    private static final String PARTNER = "partner-a";
    private static final String OTHER = "partner-b";

    /** status × step → expected outcome for the assigned partner; a null next means ignored for the reason. */
    private static Stream<Arguments> table() {
        return Stream.of(
                // PLACED: nobody has claimed it
                row(PLACED, PACKED, null, Reason.NOT_CLAIMED),
                row(PLACED, DISPATCHED, null, Reason.NOT_CLAIMED),
                row(PLACED, DELIVERED, null, Reason.NOT_CLAIMED),
                // CONFIRMED
                row(CONFIRMED, PACKED, PARCELED, null),
                row(CONFIRMED, DISPATCHED, null, Reason.OUT_OF_ORDER),
                row(CONFIRMED, DELIVERED, null, Reason.OUT_OF_ORDER),
                // PARCELED
                row(PARCELED, PACKED, null, Reason.DUPLICATE),
                row(PARCELED, DISPATCHED, DELIVERING, null),
                row(PARCELED, DELIVERED, null, Reason.OUT_OF_ORDER),
                // DELIVERING
                row(DELIVERING, PACKED, null, Reason.LATE),
                row(DELIVERING, DISPATCHED, null, Reason.DUPLICATE),
                row(DELIVERING, DELIVERED, OrderStatus.DELIVERED, null),
                // DELIVERED
                row(OrderStatus.DELIVERED, PACKED, null, Reason.LATE),
                row(OrderStatus.DELIVERED, DISPATCHED, null, Reason.LATE),
                row(OrderStatus.DELIVERED, DELIVERED, null, Reason.DUPLICATE),
                // CANCELLED
                row(OrderStatus.CANCELLED, PACKED, null, Reason.CANCELLED),
                row(OrderStatus.CANCELLED, DISPATCHED, null, Reason.CANCELLED),
                row(OrderStatus.CANCELLED, DELIVERED, null, Reason.CANCELLED));
    }

    private static Arguments row(OrderStatus status, ShipmentStep step, OrderStatus next, Reason reason) {
        return Arguments.of(status, step, next, reason);
    }

    @ParameterizedTest(name = "{0} + {1} from the assigned partner → {2} {3}")
    @MethodSource("table")
    void assignedPartner(OrderStatus status, ShipmentStep step, OrderStatus next, Reason reason) {
        Decision decision = ShipmentTransition.decide(status, status == PLACED ? null : PARTNER, step, PARTNER);

        assertThat(decision.next()).isEqualTo(next);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.applies()).isEqualTo(next != null);
    }

    @ParameterizedTest(name = "{0} + {1} from another partner → never applies")
    @MethodSource("statusAndStep")
    void otherPartner_neverApplies(OrderStatus status, ShipmentStep step) {
        Decision decision = ShipmentTransition.decide(status, status == PLACED ? null : PARTNER, step, OTHER);

        assertThat(decision.applies()).isFalse();
        Reason expected = switch (status) {
            case CANCELLED -> Reason.CANCELLED;
            case PLACED -> Reason.NOT_CLAIMED;
            default -> Reason.WRONG_PARTNER;
        };
        assertThat(decision.reason()).isEqualTo(expected);
    }

    private static Stream<Arguments> statusAndStep() {
        return Stream.of(OrderStatus.values())
                .flatMap(s -> Stream.of(ShipmentStep.values()).map(step -> Arguments.of(s, step)));
    }

    @Test
    @DisplayName("the table covers every status and step")
    void tableIsExhaustive() {
        assertThat(table().count()).isEqualTo((long) OrderStatus.values().length * ShipmentStep.values().length);
    }

    @Test
    @DisplayName("a cancelled order that kept its partner still ignores the report as cancelled")
    void cancelledOrderWithPartner() {
        assertThat(ShipmentTransition.decide(OrderStatus.CANCELLED, PARTNER, PACKED, PARTNER).reason())
                .isEqualTo(Reason.CANCELLED);
    }
}
