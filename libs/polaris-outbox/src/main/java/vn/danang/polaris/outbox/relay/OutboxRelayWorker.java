package vn.danang.polaris.outbox.relay;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Owns the relay thread (E3 design §4): polls the relay every {@code pollInterval}, draining back-to-back while
 * batches come back full, and runs the retention purge every {@code purgeInterval}. Both run on one daemon
 * thread, so they never overlap. With no transport configured the relay is idle and events stay pending.
 */
public class OutboxRelayWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    private final Supplier<OutboxRelay> relayFactory;
    private final OutboxRetention retention;
    private final Duration pollInterval;
    private final Duration purgeInterval;

    private volatile ScheduledExecutorService executor;
    private volatile OutboxRelay relay;

    /**
     * @param relayFactory resolved at start; returns {@code null} when no transport is configured
     */
    public OutboxRelayWorker(Supplier<OutboxRelay> relayFactory, OutboxRetention retention,
            Duration pollInterval, Duration purgeInterval) {
        if (pollInterval.isNegative() || pollInterval.isZero() || purgeInterval.isNegative() || purgeInterval.isZero()) {
            throw new IllegalArgumentException("poll and purge intervals must be positive");
        }
        this.relayFactory = relayFactory;
        this.retention = retention;
        this.pollInterval = pollInterval;
        this.purgeInterval = purgeInterval;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        relay = relayFactory.get();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "polaris-outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        if (relay == null) {
            log.info("Outbox relay idle: no event transport configured; recorded events stay pending");
        } else {
            log.info("Outbox relay started poll_interval={}", pollInterval);
            executor.scheduleWithFixedDelay(this::relayTick, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
        executor.scheduleWithFixedDelay(this::purgeTick, purgeInterval.toMillis(), purgeInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService running = executor;
        if (running == null) {
            return;
        }
        executor = null;
        running.shutdown();
        try {
            if (!running.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                running.shutdownNow();
            }
        } catch (InterruptedException e) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Outbox relay stopped");
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** True when a transport is configured and the relay polls. */
    public boolean isRelaying() {
        return isRunning() && relay != null;
    }

    void relayTick() {
        try {
            OutboxRelay.CycleResult result;
            do {
                result = relay.relayOnce();
            } while (result.drainAgain() && isRunning());
        } catch (RuntimeException failure) {
            // A scheduled task that throws is never run again: log and poll on the next interval.
            log.error("Outbox relay cycle failed; retrying in {}", pollInterval, failure);
        } catch (Error fatal) {
            // OutOfMemoryError, StackOverflowError...: don't keep polling in an unknown state. The relay stops;
            // recorded events stay pending and the backlog metrics show them.
            log.error("Outbox relay stopped by a fatal error; recorded events stay pending", fatal);
            throw fatal;
        }
    }

    void purgeTick() {
        try {
            retention.purgeOnce();
        } catch (RuntimeException failure) {
            log.error("Outbox retention purge failed; retrying in {}", purgeInterval, failure);
        }
    }
}
