package vn.danang.polaris.fulfilment;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.context.Context;
import vn.danang.polaris.events.fulfilment.ShipmentStep;

/**
 * One emulated partner (F2 design §4). An offer is claimed after a random pause; the pause and the later steps are
 * scheduled with the offer's OpenTelemetry {@link Context}, so the claim call and the shipment sends stay in the
 * offer's trace and the consumer thread is never blocked. The winner reports packed, dispatched and delivered a step
 * delay apart; a loser or a failed claim logs and drops the offer. A claim is never retried (TR-F3, D4).
 */
class PartnerAgent {

    private static final Logger log = LoggerFactory.getLogger(PartnerAgent.class);

    private final String partnerId;
    private final FulfilmentProperties.ClaimPause claimPause;
    private final Duration stepDelay;
    private final RandomGenerator random;
    private final ScheduledExecutorService scheduler;
    private final ClaimClient claimClient;
    private final ShipmentPublisher shipments;
    private final FulfilmentMetrics metrics;
    private final Clock clock;

    PartnerAgent(String partnerId, FulfilmentProperties properties, RandomGenerator random,
            ScheduledExecutorService scheduler, ClaimClient claimClient, ShipmentPublisher shipments,
            FulfilmentMetrics metrics, Clock clock) {
        this.partnerId = partnerId;
        this.claimPause = properties.claimPause();
        this.stepDelay = properties.stepDelay();
        this.random = random;
        this.scheduler = scheduler;
        this.claimClient = claimClient;
        this.shipments = shipments;
        this.metrics = metrics;
        this.clock = clock;
    }

    String partnerId() {
        return partnerId;
    }

    /** Called on the listener thread inside the offer's trace; returns immediately. */
    void onOffer(String orderNumber) {
        long pauseMs = randomPauseMillis();
        log.info("Offer received orderNumber={} partnerId={} claimPauseMs={}", orderNumber, partnerId, pauseMs);
        schedule(() -> claim(orderNumber), pauseMs);
    }

    private long randomPauseMillis() {
        long min = claimPause.min().toMillis();
        long max = claimPause.max().toMillis();
        return min == max ? min : random.nextLong(min, max + 1);
    }

    private void claim(String orderNumber) {
        long start = System.nanoTime();
        ClaimResult result = claimClient.claim(orderNumber, partnerId);
        metrics.claim(partnerId, result, Duration.ofNanos(System.nanoTime() - start));
        switch (result) {
            case WON -> {
                log.info("Claim won orderNumber={} partnerId={}", orderNumber, partnerId);
                ship(orderNumber, ShipmentStep.PACKED, 0);
            }
            case LOST -> log.info("Claim lost, dropping offer orderNumber={} partnerId={}", orderNumber, partnerId);
            case FAILED -> log.warn("Claim failed, dropping offer (no retry) orderNumber={} partnerId={}", orderNumber, partnerId);
        }
    }

    private void ship(String orderNumber, ShipmentStep step, long delayMs) {
        schedule(() -> {
            shipments.publish(orderNumber, partnerId, step, clock.instant());
            ShipmentStep[] steps = ShipmentStep.values();
            if (step.ordinal() + 1 < steps.length) {
                ship(orderNumber, steps[step.ordinal() + 1], stepDelay.toMillis());
            }
        }, delayMs);
    }

    private void schedule(Runnable task, long delayMs) {
        scheduler.schedule(Context.current().wrap(() -> {
            try {
                task.run();
            }
            catch (RuntimeException e) {
                log.error("Partner task failed partnerId={}", partnerId, e);
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }
}
