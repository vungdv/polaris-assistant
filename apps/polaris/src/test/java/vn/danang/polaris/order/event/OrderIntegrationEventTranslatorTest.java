package vn.danang.polaris.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.events.order.OrderMilestone;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.IntegrationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Maps the {@link OrderPlaced} domain event to the published {@code order.placed.v1} contract (TR-E5, TR-O1). */
@DisplayName("OrderIntegrationEventTranslator")
class OrderIntegrationEventTranslatorTest {

    /** Fake of the publish port: the only boundary the translator talks to. */
    private static final class RecordingPublisher implements IntegrationEventPublisher {
        final List<IntegrationEvent> published = new ArrayList<>();

        @Override
        public UUID publish(IntegrationEvent event) {
            published.add(event);
            return UUID.randomUUID();
        }
    }

    private static final Instant AT = Instant.parse("2026-09-29T10:00:00Z");

    private static OrderPlaced placed() {
        return new OrderPlaced("ORD-000042", AT, 1L, "Alice Tran", "alice.tran@example.com",
                List.of(new OrderPlaced.Line("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.90")),
                        new OrderPlaced.Line("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90"))),
                new BigDecimal("99.70"));
    }

    @Test
    @DisplayName("OrderPlaced → exactly one order.placed.v1 keyed by order number, carrying customer, items and total")
    void orderPlaced_isPublishedAsOrderPlacedV1() {
        RecordingPublisher publisher = new RecordingPublisher();

        new OrderIntegrationEventTranslator(publisher).on(placed());

        assertThat(publisher.published).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("vn.danang.polaris.order.placed.v1");
            assertThat(event.source()).isEqualTo("/polaris/order");
            assertThat(event.destination()).isEqualTo("polaris.order.lifecycle");
            assertThat(event.key()).isEqualTo("ORD-000042");
            assertThat(event.data()).isEqualTo(new OrderLifecycleEvent("ORD-000042", OrderMilestone.PLACED, AT,
                    new OrderLifecycleEvent.Customer(1L, "Alice Tran", "alice.tran@example.com"),
                    List.of(new OrderLifecycleEvent.Item("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.90")),
                            new OrderLifecycleEvent.Item("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90"))),
                    new BigDecimal("99.70"), "USD", null));
        });
    }

    @Test
    @DisplayName("OrderProgressed → parceled/delivering/delivered event with customer, items, total and the partner")
    void orderProgressed_isPublishedAsMatchingMilestone() {
        RecordingPublisher publisher = new RecordingPublisher();
        var translator = new OrderIntegrationEventTranslator(publisher);
        var lines = List.of(new OrderPlaced.Line("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90")));

        for (OrderStatus status : List.of(OrderStatus.PARCELED, OrderStatus.DELIVERING, OrderStatus.DELIVERED)) {
            translator.on(new OrderProgressed("ORD-000042", AT, status, "partner-a", 1L, "Alice Tran",
                    "alice.tran@example.com", lines, new BigDecimal("49.80")));
        }

        assertThat(publisher.published).extracting(IntegrationEvent::type).containsExactly(
                "vn.danang.polaris.order.parceled.v1", "vn.danang.polaris.order.delivering.v1",
                "vn.danang.polaris.order.delivered.v1");
        assertThat(publisher.published).allSatisfy(event -> {
            assertThat(event.key()).isEqualTo("ORD-000042");
            OrderLifecycleEvent data = (OrderLifecycleEvent) event.data();
            assertThat(data.assignedPartner()).isEqualTo("partner-a");
            assertThat(data.customer().id()).isEqualTo(1L);
            assertThat(data.items()).hasSize(1);
            assertThat(data.totalAmount()).isEqualByComparingTo("49.80");
        });
    }

    @Test
    @DisplayName("A publish failure propagates, so the order change rolls back with it")
    void publishFailure_propagates() {
        IntegrationEventPublisher failing = event -> {
            throw new IllegalStateException("store down");
        };

        assertThatThrownBy(() -> new OrderIntegrationEventTranslator(failing).on(placed()))
                .isInstanceOf(IllegalStateException.class);
    }
}
