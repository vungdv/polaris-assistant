package vn.danang.polaris.order.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.events.fulfilment.ShipmentEvent;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.ShipmentTransition.Decision;
import vn.danang.polaris.order.service.ShipmentTransition.Reason;

/**
 * Applies a shipment report to its order (TR-O4, TR-O5). The order is row-locked first (ADR-0007, TR-X8), so the
 * status check and the transition are one atomic step: a duplicate delivery of the same report finds the order
 * already advanced and is a no-op, and needs no dedupe store.
 */
@Service
public class ShipmentProgressService {

    /** Result of one report: the milestone reached, or why nothing changed. */
    public record Outcome(Decision decision) {
        public boolean applied() {
            return decision.applies();
        }
    }

    private final OrderRepository orderRepo;

    public ShipmentProgressService(OrderRepository orderRepo) {
        this.orderRepo = orderRepo;
    }

    @Transactional
    public Outcome apply(ShipmentEvent report) {
        Order order = orderRepo.findByOrderNumberForUpdate(report.orderNumber()).orElse(null);
        if (order == null) {
            return new Outcome(new Decision(null, Reason.UNKNOWN_ORDER));
        }
        Decision decision = ShipmentTransition.decide(order.getStatus(), order.getAssignedPartner(),
                report.step(), report.partnerId());
        if (decision.applies()) {
            Instant now = Instant.now();
            switch (report.step()) {
                case PACKED -> order.parcel(now);
                case DISPATCHED -> order.dispatch(now);
                case DELIVERED -> order.deliver(now);
            }
            orderRepo.save(order);
        }
        return new Outcome(decision);
    }
}
