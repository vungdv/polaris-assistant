package vn.danang.polaris.order.event;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import vn.danang.polaris.events.order.OrderEvents;
import vn.danang.polaris.events.order.OrderLifecycleEvent;
import vn.danang.polaris.events.order.OrderMilestone;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.IntegrationEventPublisher;

/**
 * Translates Order domain events into published {@code order.*.v1} integration events (ADR-0018, E2 design §5).
 *
 * <p>A plain synchronous {@link EventListener}: it runs on the caller's thread inside the transaction that saved
 * the order, so the event is recorded if and only if the order change commits (TR-E1). Never {@code @Async} and
 * never {@code AFTER_COMMIT}. A failure here propagates out of {@code save} and rolls the change back.
 *
 * <p>This is the only Order code that sees a published contract, and it depends only on the publish port.
 */
@Component
public class OrderIntegrationEventTranslator {

    private static final Logger log = LoggerFactory.getLogger(OrderIntegrationEventTranslator.class);

    /** Currency of all catalogue prices (EM-002 §4.2). */
    static final String CURRENCY = "USD";

    private final IntegrationEventPublisher publisher;

    public OrderIntegrationEventTranslator(IntegrationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void on(OrderPlaced placed) {
        OrderLifecycleEvent payload = new OrderLifecycleEvent(
                placed.orderNumber(),
                OrderMilestone.PLACED,
                placed.occurredAt(),
                new OrderLifecycleEvent.Customer(placed.customerId(), placed.customerName(), placed.customerEmail()),
                placed.lines().stream()
                        .map(l -> new OrderLifecycleEvent.Item(l.sku(), l.name(), l.quantity(), l.unitPrice()))
                        .toList(),
                placed.totalAmount(),
                CURRENCY,
                null);
        UUID ceId = publisher.publish(new IntegrationEvent(
                OrderMilestone.PLACED.type(), OrderEvents.SOURCE, OrderEvents.DESTINATION, placed.orderNumber(), payload));
        log.info("Order event recorded: orderNumber={}, ce_id={}, ce_type={}",
                placed.orderNumber(), ceId, OrderMilestone.PLACED.type());
    }

    @EventListener
    public void on(OrderConfirmed confirmed) {
        OrderLifecycleEvent payload = new OrderLifecycleEvent(
                confirmed.orderNumber(),
                OrderMilestone.CONFIRMED,
                confirmed.occurredAt(),
                new OrderLifecycleEvent.Customer(confirmed.customerId(), confirmed.customerName(), confirmed.customerEmail()),
                confirmed.lines().stream()
                        .map(l -> new OrderLifecycleEvent.Item(l.sku(), l.name(), l.quantity(), l.unitPrice()))
                        .toList(),
                confirmed.totalAmount(),
                CURRENCY,
                confirmed.partnerId());
        UUID ceId = publisher.publish(new IntegrationEvent(
                OrderMilestone.CONFIRMED.type(), OrderEvents.SOURCE, OrderEvents.DESTINATION, confirmed.orderNumber(), payload));
        log.info("Order event recorded: orderNumber={}, ce_id={}, ce_type={}",
                confirmed.orderNumber(), ceId, OrderMilestone.CONFIRMED.type());
    }

    @EventListener
    public void on(OrderProgressed progressed) {
        OrderMilestone milestone = OrderMilestone.valueOf(progressed.status().name());
        OrderLifecycleEvent payload = new OrderLifecycleEvent(
                progressed.orderNumber(),
                milestone,
                progressed.occurredAt(),
                new OrderLifecycleEvent.Customer(progressed.customerId(), progressed.customerName(), progressed.customerEmail()),
                progressed.lines().stream()
                        .map(l -> new OrderLifecycleEvent.Item(l.sku(), l.name(), l.quantity(), l.unitPrice()))
                        .toList(),
                progressed.totalAmount(),
                CURRENCY,
                progressed.partnerId());
        UUID ceId = publisher.publish(new IntegrationEvent(
                milestone.type(), OrderEvents.SOURCE, OrderEvents.DESTINATION, progressed.orderNumber(), payload));
        log.info("Order event recorded: orderNumber={}, ce_id={}, ce_type={}",
                progressed.orderNumber(), ceId, milestone.type());
    }
}
