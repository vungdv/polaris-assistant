// domain/Order.java
package vn.danang.polaris.order.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.AbstractAggregateRoot;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.order.event.OrderCancelled;
import vn.danang.polaris.order.event.OrderConfirmed;
import vn.danang.polaris.order.event.OrderProgressed;
import vn.danang.polaris.order.event.OrderPlaced;

/**
 * The Order aggregate root.
 *
 * <p>Its status changes only through {@link #place}, {@link #confirm}, {@link #parcel}, {@link #dispatch}, {@link #deliver} and {@link #cancel}, which register the matching domain event
 * (TR-X7); Spring Data publishes registered events when the order is passed to {@code save}. There is no status
 * setter.
 *
 * <p>{@link #version} serialises event-raising transactions on the same order (TR-X8): of two concurrent changes,
 * the loser's flush fails and it rolls back together with any event it recorded, so an order's events commit in
 * outbox id order.
 */
@Entity
@Table(name = "orders")
@Getter @Setter
public class Order extends AbstractAggregateRoot<Order> {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic lock (V15_1, TR-X8). */
    @Version
    @Setter(AccessLevel.NONE)
    private Long version;

    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Setter(AccessLevel.NONE)
    private OrderStatus status;

    private BigDecimal totalAmount;
    private Instant placedAt;
    private Instant updatedAt;
    private String idempotencyKey;

    /** Partner that claimed the order (V16, TR-O2); null while {@code PLACED}. */
    @Setter(AccessLevel.NONE)
    private String assignedPartner;

    @Setter(AccessLevel.NONE)
    private Instant claimedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    /** A line to place: the (row-locked) product, priced at its current price, and the quantity. */
    public record Line(Product product, int quantity) {
        public Line {
            Objects.requireNonNull(product, "product");
        }
    }

    /**
     * Creates a {@link OrderStatus#PLACED} order with its items and total, and registers exactly one
     * {@link OrderPlaced}. Each line is priced at its product's current price.
     */
    public static Order place(String orderNumber, Customer customer, List<Line> lines, String idempotencyKey,
                              Instant placedAt) {
        Objects.requireNonNull(orderNumber, "orderNumber");
        Objects.requireNonNull(placedAt, "placedAt");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one item");
        }
        Order order = new Order();
        order.orderNumber = orderNumber;
        order.customer = customer;
        order.idempotencyKey = idempotencyKey;
        order.status = OrderStatus.PLACED;
        order.placedAt = placedAt;
        order.updatedAt = placedAt;

        BigDecimal total = BigDecimal.ZERO;
        List<OrderPlaced.Line> placedLines = new ArrayList<>();
        for (Line line : lines) {
            Product product = line.product();
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProduct(product);
            item.setQuantity(line.quantity());
            item.setUnitPrice(product.getPrice());
            order.items.add(item);
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(line.quantity())));
            placedLines.add(new OrderPlaced.Line(product.getSku(), product.getName(), line.quantity(), product.getPrice()));
        }
        order.totalAmount = total;

        order.registerEvent(new OrderPlaced(orderNumber, placedAt,
                customer != null ? customer.getId() : null,
                customer != null ? customer.getFullName() : null,
                customer != null ? customer.getEmail() : null,
                placedLines, total));
        return order;
    }

    /**
     * Cancels the order and registers exactly one {@link OrderCancelled}.
     *
     * @throws IllegalStateException if the current status is not cancellable; nothing changes and no event is registered
     */
    public void cancel(Instant at) {
        if (status == null || !status.isCancellable()) {
            throw new IllegalStateException(
                    "Order " + orderNumber + " cannot be cancelled — current status is " + status);
        }
        status = OrderStatus.CANCELLED;
        updatedAt = at;
        registerEvent(new OrderCancelled(orderNumber, at));
    }

    /**
     * Claims a {@link OrderStatus#PLACED} order for a partner: status becomes {@link OrderStatus#CONFIRMED}, the
     * partner and claim time are recorded, and exactly one {@link OrderConfirmed} is registered.
     *
     * @throws IllegalStateException if the order is not {@code PLACED}; nothing changes and no event is registered
     */
    public void confirm(String partnerId, Instant at) {
        Objects.requireNonNull(partnerId, "partnerId");
        Objects.requireNonNull(at, "at");
        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException("Order " + orderNumber + " cannot be claimed — current status is " + status);
        }
        status = OrderStatus.CONFIRMED;
        assignedPartner = partnerId;
        claimedAt = at;
        updatedAt = at;
        List<OrderPlaced.Line> lines = items.stream()
                .map(i -> new OrderPlaced.Line(i.getProduct().getSku(), i.getProduct().getName(),
                        i.getQuantity(), i.getUnitPrice()))
                .toList();
        registerEvent(new OrderConfirmed(orderNumber, at, partnerId,
                customer != null ? customer.getId() : null,
                customer != null ? customer.getFullName() : null,
                customer != null ? customer.getEmail() : null,
                lines, totalAmount));
    }

    /** {@code CONFIRMED → PARCELED}: the partner packed the order. Registers exactly one {@link OrderProgressed}. */
    public void parcel(Instant at) {
        progress(OrderStatus.CONFIRMED, OrderStatus.PARCELED, at);
    }

    /** {@code PARCELED → DELIVERING}: the partner dispatched the order. Registers exactly one {@link OrderProgressed}. */
    public void dispatch(Instant at) {
        progress(OrderStatus.PARCELED, OrderStatus.DELIVERING, at);
    }

    /** {@code DELIVERING → DELIVERED}: the order reached the customer. Registers exactly one {@link OrderProgressed}. */
    public void deliver(Instant at) {
        progress(OrderStatus.DELIVERING, OrderStatus.DELIVERED, at);
    }

    private void progress(OrderStatus expected, OrderStatus next, Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != expected) {
            throw new IllegalStateException(
                    "Order " + orderNumber + " cannot become " + next + " — current status is " + status);
        }
        status = next;
        updatedAt = at;
        registerEvent(new OrderProgressed(orderNumber, at, next, assignedPartner,
                customer != null ? customer.getId() : null,
                customer != null ? customer.getFullName() : null,
                customer != null ? customer.getEmail() : null,
                lineSnapshot(), totalAmount));
    }

    private List<OrderPlaced.Line> lineSnapshot() {
        return items.stream()
                .map(i -> new OrderPlaced.Line(i.getProduct().getSku(), i.getProduct().getName(),
                        i.getQuantity(), i.getUnitPrice()))
                .toList();
    }
}
