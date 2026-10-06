package vn.danang.polaris.fulfilment;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import vn.danang.polaris.events.fulfilment.ShipmentStep;

/** Invocation, outcome and latency metrics (TR-X2, F2 design §7). */
@Component
public class FulfilmentMetrics {

    private final MeterRegistry registry;

    FulfilmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void offer(String partner, String outcome) {
        registry.counter("polaris.fulfilment.offers", "partner", partner, "outcome", outcome).increment();
    }

    void claim(String partner, ClaimResult result, Duration duration) {
        registry.counter("polaris.fulfilment.claims", "partner", partner, "outcome", result.name().toLowerCase()).increment();
        registry.timer("polaris.fulfilment.claim.duration", "partner", partner).record(duration);
    }

    void shipment(String partner, ShipmentStep step, boolean sent) {
        registry.counter("polaris.fulfilment.shipments", "partner", partner, "step", step.name().toLowerCase(),
                "outcome", sent ? "sent" : "failed").increment();
    }
}
