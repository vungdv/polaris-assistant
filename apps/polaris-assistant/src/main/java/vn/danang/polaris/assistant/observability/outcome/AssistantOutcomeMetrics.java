package vn.danang.polaris.assistant.observability.outcome;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.IntentManager;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.PriceChangedException;
import vn.danang.polaris.web.exception.ProductInactiveException;

/**
 * Agent outcome metrics: how chat turns and order drafts end. Every tag is bounded (enums, the configured
 * intents, the draft-invalidating problem types); session, draft and user ids never become tags.
 * <ul>
 *   <li>{@code polaris.assistant.turns{intent, outcome}}: one per chat turn that reached intent resolution.</li>
 *   <li>{@code polaris.assistant.drafts.staged}: drafts the agent staged (the denominator of conversion).</li>
 *   <li>{@code polaris.assistant.draft.lifetime{outcome, cause}}: one per draft that left WAITING_CONFIRMATION,
 *       timed from staging to that decision.</li>
 * </ul>
 * Draft meters inside a transaction are recorded after it commits, so a rolled-back or retried attempt
 * never counts.
 * <p>
 * The known series are registered at zero on startup ({@link #registerKnownSeries}): a series that first appears
 * already at 1 has no visible increase in Prometheus, so at low traffic the first event of each outcome was lost.
 */
@Component
public class AssistantOutcomeMetrics {

    static final String TURNS = "polaris.assistant.turns";
    static final String DRAFTS_STAGED = "polaris.assistant.drafts.staged";
    static final String DRAFT_LIFETIME = "polaris.assistant.draft.lifetime";
    static final String UNKNOWN_INTENT = "unknown";
    static final String NO_CAUSE = "none";
    /** Problem types that invalidate a draft (PlaceOrderOutcome.DRAFT_INVALIDATING_TYPES), as cause tags. */
    static final List<String> INVALIDATION_CAUSES = List.of(
            causeTag(PriceChangedException.TYPE), causeTag(InsufficientStockException.TYPE), causeTag(ProductInactiveException.TYPE));

    private static final Logger log = LoggerFactory.getLogger(AssistantOutcomeMetrics.class);

    /** How a chat turn ended. */
    public enum TurnOutcome {
        /** The resolved intent met its confidence threshold and the model replied. */
        ANSWERED,
        /** Confidence below threshold: answered as general conversation with its tools only. */
        FALLBACK,
        /** A tool call was denied by policy (e.g. anonymous caller, another user's session). */
        POLICY_DENIED,
        /** The model kept calling tools until the iteration limit, so the turn got the default reply. */
        ITERATION_LIMIT,
        /** The turn threw (model provider, tool infrastructure, persistence); the caller got an error response. */
        FAILED
    }

    /** How an order draft left WAITING_CONFIRMATION. */
    public enum DraftOutcome {
        /** The shopper confirmed and Order Management placed the order. */
        PLACED,
        /** The shopper clicked Cancel. */
        CANCELLED,
        /** The model discarded it on the shopper's request. */
        DISCARDED,
        /** A newer draft of the session replaced it. */
        SUPERSEDED,
        /** The price hold (TTL) elapsed before a decision: abandoned. */
        EXPIRED,
        /** Order Management rejected the snapshot at confirmation (price changed, out of stock, product inactive). */
        INVALIDATED
    }

    private final MeterRegistry registry;
    @Nullable
    private final ObjectProvider<IntentManager> intentManager;

    @Autowired
    public AssistantOutcomeMetrics(MeterRegistry registry, ObjectProvider<IntentManager> intentManager) {
        this.registry = registry;
        this.intentManager = intentManager;
    }

    public AssistantOutcomeMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.intentManager = null;
    }

    /** Records nothing that anyone reads; for collaborators constructed without Spring. */
    public static AssistantOutcomeMetrics detached() {
        return new AssistantOutcomeMetrics(new SimpleMeterRegistry());
    }

    /**
     * Registers every known series at zero: all draft outcomes (invalidated per known cause), and the turn
     * outcomes of each configured intent plus "unknown". An intent added later starts at its first turn.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerKnownSeries() {
        registry.counter(DRAFTS_STAGED);
        for (DraftOutcome outcome : DraftOutcome.values()) {
            List<String> causes = outcome == DraftOutcome.INVALIDATED ? INVALIDATION_CAUSES : List.of(NO_CAUSE);
            causes.forEach(cause -> registry.timer(DRAFT_LIFETIME, "outcome", tag(outcome), "cause", cause));
        }
        List<String> intents = new ArrayList<>(List.of(UNKNOWN_INTENT));
        try {
            IntentManager manager = intentManager != null ? intentManager.getIfAvailable() : null;
            if (manager != null) {
                manager.listIntents().stream().map(IntentDefinition::id)
                        .filter(id -> id != null && !id.isBlank()).forEach(intents::add);
            }
        } catch (RuntimeException e) {
            log.warn("Intent list unavailable; turn series register on first use error={}", e.getClass().getSimpleName());
        }
        for (String intent : intents) {
            for (TurnOutcome outcome : TurnOutcome.values()) {
                registry.counter(TURNS, "intent", intent, "outcome", tag(outcome));
            }
        }
    }

    public void turn(@Nullable ResolvedIntent intent, TurnOutcome outcome) {
        registry.counter(TURNS, "intent", intentTag(intent), "outcome", tag(outcome)).increment();
    }

    public void draftStaged() {
        afterCommit(() -> registry.counter(DRAFTS_STAGED).increment());
    }

    public void draftClosed(OrderDraft draft, DraftOutcome outcome, Instant now) {
        draftClosed(draft, outcome, null, now);
    }

    /**
     * @param problemType the RFC 7807 type that caused the outcome (INVALIDATED), or null
     */
    public void draftClosed(OrderDraft draft, DraftOutcome outcome, @Nullable String problemType, Instant now) {
        // Callers read "now" before taking the session lock, so a concurrent stager can supersede a draft created
        // a moment "later"; clamp instead of recording a negative duration (which Micrometer drops).
        Duration elapsed = draft.getCreatedAt() != null ? Duration.between(draft.getCreatedAt(), now) : Duration.ZERO;
        Duration lifetime = elapsed.isNegative() ? Duration.ZERO : elapsed;
        String cause = causeTag(problemType);
        afterCommit(() -> registry.timer(DRAFT_LIFETIME, "outcome", tag(outcome), "cause", cause).record(lifetime));
    }

    private static String intentTag(@Nullable ResolvedIntent intent) {
        // The id of the matched definition, not the classifier's raw answer: only configured intents become tags.
        if (intent == null || intent.intentDefinition() == null || intent.intentDefinition().id() == null
                || intent.intentDefinition().id().isBlank()) {
            return UNKNOWN_INTENT;
        }
        return intent.intentDefinition().id();
    }

    /** Last path segment of the problem type URI, e.g. https://polaris.local/errors/price-changed → price-changed. */
    static String causeTag(@Nullable String problemType) {
        if (problemType == null || problemType.isBlank()) {
            return NO_CAUSE;
        }
        String trimmed = problemType.endsWith("/") ? problemType.substring(0, problemType.length() - 1) : problemType;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void afterCommit(Runnable record) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record.run();
                }
            });
        } else {
            record.run();
        }
    }
}
