package vn.danang.polaris.order.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.order.event.OrderCancelled;
import vn.danang.polaris.order.event.OrderConfirmed;
import vn.danang.polaris.order.event.OrderPlaced;
import vn.danang.polaris.order.event.OrderProgressed;
import vn.danang.polaris.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TR-X7: status changes only through the aggregate's methods, each registering exactly one domain event. */
@DisplayName("Order aggregate")
class OrderTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-29T10:00:00Z");

    private static Product product(String sku, String name, String price) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        return product;
    }

    private static Customer customer() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Alice Tran");
        customer.setEmail("alice.tran@example.com");
        return customer;
    }

    /** The events Spring Data would publish on {@code save}. */
    private static Collection<?> registeredEvents(Order order) {
        return ReflectionTestUtils.invokeMethod(order, "domainEvents");
    }

    private static Order placed() {
        return Order.place("ORD-000042", customer(), List.of(
                new Order.Line(product("NG-EARBUD-01", "Nova Wireless Earbuds", "49.90"), 1),
                new Order.Line(product("NG-CHARGER-01", "Fast Charger", "24.90"), 2)), "key-1", PLACED_AT);
    }

    @Test
    @DisplayName("place → PLACED with priced items and total, and exactly one OrderPlaced snapshot")
    void place_registersExactlyOneOrderPlaced() {
        Order order = placed();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("99.70");
        assertThat(order.getItems()).hasSize(2).allSatisfy(item -> assertThat(item.getOrder()).isSameAs(order));
        assertThat(order.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(order.getPlacedAt()).isEqualTo(PLACED_AT);

        assertThat(registeredEvents(order)).singleElement().isEqualTo(new OrderPlaced("ORD-000042", PLACED_AT,
                1L, "Alice Tran", "alice.tran@example.com",
                List.of(new OrderPlaced.Line("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.90")),
                        new OrderPlaced.Line("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90"))),
                order.getTotalAmount()));
    }

    @Test
    @DisplayName("place without lines is rejected and creates nothing")
    void place_withoutLines_isRejected() {
        assertThatThrownBy(() -> Order.place("ORD-1", customer(), List.of(), null, PLACED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cancel → CANCELLED and exactly one more event, OrderCancelled")
    void cancel_registersExactlyOneOrderCancelled() {
        Order order = placed();
        Instant at = PLACED_AT.plusSeconds(60);

        order.cancel(at);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getUpdatedAt()).isEqualTo(at);
        assertThat(registeredEvents(order)).hasSize(2).last().isEqualTo(new OrderCancelled("ORD-000042", at));
    }

    @Test
    @DisplayName("cancel of a non-cancellable order is rejected, changes nothing and registers no event")
    void cancel_notCancellable_registersNothing() {
        Order order = placed();
        order.cancel(PLACED_AT);
        int before = registeredEvents(order).size();

        assertThatThrownBy(() -> order.cancel(PLACED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be cancelled");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(registeredEvents(order)).hasSize(before);
    }

    @Test
    @DisplayName("TR-X7: Order exposes no public status setter")
    void noPublicStatusSetter() {
        assertThat(Order.class.getMethods()).noneMatch(m -> m.getName().equals("setStatus"));
        assertThat(Order.class.getMethods()).noneMatch(m -> m.getName().equals("setVersion"));
    }

    @Test
    @DisplayName("TR-X7: OrderRepository has no bulk (@Modifying) updates that could bypass the aggregate")
    void noBulkOrderUpdates() {
        assertThat(OrderRepository.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(org.springframework.data.jpa.repository.Modifying.class));
    }

    @Test
    @DisplayName("confirm → CONFIRMED with partner and claim time, and exactly one more event, OrderConfirmed")
    void confirm_registersExactlyOneOrderConfirmed() {
        Order order = placed();
        Instant at = PLACED_AT.plusSeconds(30);

        order.confirm("partner-a", at);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getAssignedPartner()).isEqualTo("partner-a");
        assertThat(order.getClaimedAt()).isEqualTo(at);
        assertThat(order.getUpdatedAt()).isEqualTo(at);
        assertThat(registeredEvents(order)).hasSize(2).last().isEqualTo(new OrderConfirmed("ORD-000042", at, "partner-a",
                1L, "Alice Tran", "alice.tran@example.com",
                List.of(new OrderPlaced.Line("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("49.90")),
                        new OrderPlaced.Line("NG-CHARGER-01", "Fast Charger", 2, new BigDecimal("24.90"))),
                order.getTotalAmount()));
    }

    @Test
    @DisplayName("confirm of a claimed order (repeat, even by the winner) is rejected, changes nothing and registers no event")
    void confirm_twice_registersNothing() {
        Order order = placed();
        order.confirm("partner-a", PLACED_AT);
        int before = registeredEvents(order).size();

        assertThatThrownBy(() -> order.confirm("partner-a", PLACED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.confirm("partner-b", PLACED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(order.getAssignedPartner()).isEqualTo("partner-a");
        assertThat(order.getClaimedAt()).isEqualTo(PLACED_AT);
        assertThat(registeredEvents(order)).hasSize(before);
    }

    @Test
    @DisplayName("confirm of a cancelled order is rejected and leaves no partner")
    void confirm_cancelled_isRejected() {
        Order order = placed();
        order.cancel(PLACED_AT);
        int before = registeredEvents(order).size();

        assertThatThrownBy(() -> order.confirm("partner-a", PLACED_AT)).isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getAssignedPartner()).isNull();
        assertThat(registeredEvents(order)).hasSize(before);
    }

    @Test
    @DisplayName("parcel → dispatch → deliver walks CONFIRMED to DELIVERED, one OrderProgressed each with the partner")
    void progress_registersOneEventPerTransition() {
        Order order = placed();
        order.confirm("partner-a", PLACED_AT);
        ReflectionTestUtils.invokeMethod(order, "clearDomainEvents");

        order.parcel(PLACED_AT.plusSeconds(1));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARCELED);
        order.dispatch(PLACED_AT.plusSeconds(2));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERING);
        order.deliver(PLACED_AT.plusSeconds(3));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);

        assertThat(registeredEvents(order)).hasSize(3).allSatisfy(e -> {
            OrderProgressed p = (OrderProgressed) e;
            assertThat(p.partnerId()).isEqualTo("partner-a");
            assertThat(p.totalAmount()).isEqualByComparingTo("99.70");
            assertThat(p.lines()).hasSize(2);
        });
        assertThat(registeredEvents(order)).extracting(e -> ((OrderProgressed) e).status())
                .containsExactly(OrderStatus.PARCELED, OrderStatus.DELIVERING, OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("progress from the wrong status throws and changes nothing")
    void progress_fromWrongStatus_throws() {
        Order order = placed();
        order.confirm("partner-a", PLACED_AT);
        ReflectionTestUtils.invokeMethod(order, "clearDomainEvents");

        assertThatThrownBy(() -> order.dispatch(PLACED_AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.deliver(PLACED_AT)).isInstanceOf(IllegalStateException.class);
        order.parcel(PLACED_AT);
        assertThatThrownBy(() -> order.parcel(PLACED_AT)).isInstanceOf(IllegalStateException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARCELED);
        assertThat(registeredEvents(order)).hasSize(1);
    }
}
