package vn.danang.polaris.outbox.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.store.PendingByType;

/**
 * Outbox metrics (E3 design §7): backlog and oldest-pending age read from the table when sampled, in total
 * and per {@code event_type}, and hand-off latency, outcome and delivery lag recorded by the relay. Exported by the
 * app's registry (OTLP). Names, units and labels are a contract;
 * new label keys must also be allow-listed in the Collector's {@code transform/metric_allowlist}.
 *
 * <p><b>Gauge callbacks never block the registry's publish thread.</b> The OTLP registry reads every gauge on
 * one thread per step, so a callback stuck on the database would stop <em>all</em> metrics of the app (JVM
 * included). The database is therefore read on a dedicated thread: a callback waits at most {@code queryTimeout} for
 * a snapshot in total (the wait is shared by all gauges of a publish), and a query that hangs only occupies that
 * thread. While no snapshot younger than {@code staleAfter} exists the gauges report NaN (not exported), so a stale
 * value is never presented as current.
 */
public class OutboxMetrics implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    public static final String BACKLOG = "polaris.outbox.backlog";
    public static final String OLDEST_PENDING_AGE = "polaris.outbox.oldest.pending.age";
    public static final String PENDING_BY_TYPE = "polaris.outbox.pending";
    public static final String PENDING_OLDEST_AGE_BY_TYPE = "polaris.outbox.pending.oldest.age";
    public static final String DELIVERY_LAG = "polaris.outbox.delivery.lag";
    public static final String HANDOFF = "polaris.outbox.handoff";
    public static final String PURGED = "polaris.outbox.purged";
    public static final String EVENT_TYPE = "event_type";

    /** One snapshot serves every gauge read of a scrape (production value). */
    public static final Duration DEFAULT_SNAPSHOT_TTL = Duration.ofSeconds(5);
    /** Longest a gauge callback waits for the database, in total per hung query. */
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(2);
    /** A snapshot older than this is not reported. */
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofSeconds(30);

    private final MeterRegistry registry;
    private final OutboxRelayStore store;
    private final Clock clock;
    private final Duration snapshotTtl;
    private final Duration queryTimeout;
    private final Duration staleAfter;
    private final Counter purged;
    private final Map<String, Boolean> typesWithGauges = new ConcurrentHashMap<>();
    private final ExecutorService queryThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "polaris-outbox-metrics-query");
        thread.setDaemon(true);
        return thread;
    });

    private final Object queryLock = new Object();
    private Future<?> inFlight; // guarded by queryLock
    private long inFlightSinceNanos; // guarded by queryLock
    private volatile Snapshot snapshot;
    private volatile boolean lastQueryFailed;

    /** Reads on every sample (no snapshot cache): for tests and embedding. Production wiring uses the long form. */
    public OutboxMetrics(MeterRegistry registry, OutboxRelayStore store, Clock clock) {
        this(registry, store, clock, Duration.ZERO, DEFAULT_QUERY_TIMEOUT, DEFAULT_STALE_AFTER);
    }

    public OutboxMetrics(MeterRegistry registry, OutboxRelayStore store, Clock clock, Duration snapshotTtl,
            Duration queryTimeout, Duration staleAfter) {
        this.registry = registry;
        this.store = store;
        this.clock = clock;
        this.snapshotTtl = snapshotTtl;
        this.queryTimeout = queryTimeout;
        this.staleAfter = staleAfter;
        Gauge.builder(BACKLOG, this, m -> m.read(s -> (double) s.backlog()))
                .description("Recorded integration events not yet handed to a transport")
                .baseUnit("events")
                .strongReference(true)
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE, this, m -> m.read(s -> s.oldest().map(m::ageSeconds).orElse(0.0)))
                .description("Age of the oldest pending integration event; 0 when none is pending")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
        this.purged = Counter.builder(PURGED)
                .description("Delivered integration events removed by the retention purge")
                .baseUnit("events")
                .register(registry);
    }

    /** One transport invocation: latency and outcome. */
    public void recordHandOff(String destination, String eventType, boolean success, Duration duration) {
        Timer.builder(HANDOFF)
                .description("Transport hand-off attempts of outbox events")
                .tag("destination", destination)
                .tag(EVENT_TYPE, eventType)
                .tag("outcome", success ? "success" : "failure")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** One delivered event: time from recording to accepted hand-off. */
    public void recordDelivered(String destination, String eventType, Duration lag) {
        Timer.builder(DELIVERY_LAG)
                .description("Time from recording an integration event to its accepted hand-off")
                .tag("destination", destination)
                .tag(EVENT_TYPE, eventType)
                .publishPercentileHistogram()
                .register(registry)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }

    public void recordPurged(int count) {
        purged.increment(count);
    }

    @Override
    public void close() {
        queryThread.shutdownNow();
    }

    /** A gauge value from the current snapshot, or NaN when there is none that is recent enough. */
    private double read(Function<Snapshot, Double> value) {
        Snapshot current = currentSnapshot();
        return current == null ? Double.NaN : value.apply(current);
    }

    private Double perType(String type, Function<PendingByType, Double> value) {
        Snapshot current = currentSnapshot();
        if (current == null) {
            return Double.NaN;
        }
        PendingByType pending = current.byType().get(type);
        return pending == null ? 0.0 : value.apply(pending);
    }

    private Boolean registerTypeGauges(String type) {
        Gauge.builder(PENDING_BY_TYPE, this, m -> m.perType(type, p -> (double) p.count()))
                .description("Pending integration events of one event type")
                .baseUnit("events")
                .tag(EVENT_TYPE, type)
                .strongReference(true)
                .register(registry);
        Gauge.builder(PENDING_OLDEST_AGE_BY_TYPE, this, m -> m.perType(type, p -> m.ageSeconds(p.oldestOccurredAt())))
                .description("Age of the oldest pending integration event of one event type; 0 when none is pending")
                .baseUnit("seconds")
                .tag(EVENT_TYPE, type)
                .strongReference(true)
                .register(registry);
        return Boolean.TRUE;
    }

    /**
     * The snapshot to report: the cached one while younger than the TTL, otherwise a fresh one loaded on the query
     * thread, waiting at most the query timeout (counted from when that query started, so concurrent callers of one
     * publish share one wait). Null when no snapshot younger than {@code staleAfter} exists.
     */
    private Snapshot currentSnapshot() {
        Snapshot cached = snapshot;
        Instant now = clock.instant();
        if (cached != null && fresh(cached.at(), now, snapshotTtl)) {
            return cached;
        }
        Future<?> query;
        long remainingNanos;
        synchronized (queryLock) {
            if (inFlight == null || inFlight.isDone()) {
                inFlight = queryThread.submit(this::load);
                inFlightSinceNanos = System.nanoTime();
            }
            query = inFlight;
            remainingNanos = queryTimeout.toNanos() - (System.nanoTime() - inFlightSinceNanos);
        }
        try {
            query.get(Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException slowQuery) {
            logOnce("Outbox metrics query did not finish within " + queryTimeout + "; reporting no value until it does");
        } catch (ExecutionException failure) {
            logOnce("Outbox metrics query failed: " + failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Snapshot latest = snapshot;
        return latest != null && fresh(latest.at(), clock.instant(), staleAfter) ? latest : null;
    }

    /** Younger than the limit; a clock that went backwards counts as not fresh. */
    private static boolean fresh(Instant at, Instant now, Duration limit) {
        return !now.isBefore(at) && Duration.between(at, now).compareTo(limit) < 0;
    }

    private void logOnce(String message) {
        if (!lastQueryFailed) {
            lastQueryFailed = true;
            log.warn(message);
        }
    }

    /** Runs on the query thread: the only place that touches the database for metrics. */
    private void load() {
        long backlog = store.countPending();
        Optional<Instant> oldest = store.oldestPendingOccurredAt();
        List<PendingByType> rows = store.pendingByType();
        Map<String, PendingByType> byType = rows.stream().collect(Collectors.toMap(PendingByType::type, Function.identity()));
        snapshot = new Snapshot(clock.instant(), backlog, oldest, byType);
        if (lastQueryFailed) {
            lastQueryFailed = false;
            log.info("Outbox metrics query recovered");
        }
        byType.keySet().forEach(type -> typesWithGauges.computeIfAbsent(type, this::registerTypeGauges));
    }

    private double ageSeconds(Instant since) {
        return Math.max(0, Duration.between(since, clock.instant()).toMillis()) / 1000.0;
    }

    private record Snapshot(Instant at, long backlog, Optional<Instant> oldest, Map<String, PendingByType> byType) {
    }
}
