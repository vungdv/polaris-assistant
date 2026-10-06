package vn.danang.polaris.assistant.observability.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.IntentManager;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics.DraftOutcome;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics.TurnOutcome;

class AssistantOutcomeMetricsTest {

    private static final Instant STAGED_AT = Instant.parse("2026-10-02T10:00:00Z");

    private SimpleMeterRegistry meters;
    private AssistantOutcomeMetrics metrics;
    private OrderDraft draft;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        metrics = new AssistantOutcomeMetrics(meters);
        draft = OrderDraft.stage("s1", 7L, List.of(DraftLine.of("SKU-1", "Item", 1, new BigDecimal("5.00"))),
                OrderDraft.DEFAULT_TTL, STAGED_AT);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Timer lifetime(String outcome, String cause) {
        return meters.find(AssistantOutcomeMetrics.DRAFT_LIFETIME).tags("outcome", outcome, "cause", cause).timer();
    }

    @Test
    @DisplayName("Given no transaction, when a draft closes, then its lifetime is recorded at once, timed from staging")
    void records_immediately_without_transaction() {
        metrics.draftClosed(draft, DraftOutcome.PLACED, STAGED_AT.plusSeconds(90));

        assertThat(lifetime("placed", "none").count()).isEqualTo(1);
        assertThat(lifetime("placed", "none").totalTime(TimeUnit.SECONDS)).isEqualTo(90);
    }

    @Test
    @DisplayName("Given a transaction, when a draft closes, then nothing is recorded until it commits")
    void defers_until_commit() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.draftStaged();
        metrics.draftClosed(draft, DraftOutcome.SUPERSEDED, STAGED_AT.plusSeconds(10));
        assertThat(meters.getMeters()).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(meters.find(AssistantOutcomeMetrics.DRAFTS_STAGED).counter().count()).isEqualTo(1);
        assertThat(lifetime("superseded", "none").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given a transaction that rolls back, then nothing is recorded")
    void rollback_records_nothing() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.draftClosed(draft, DraftOutcome.EXPIRED, STAGED_AT.plusSeconds(10));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("Given an RFC 7807 type, then the cause tag is its last path segment")
    void cause_is_problem_type_slug() {
        metrics.draftClosed(draft, DraftOutcome.INVALIDATED, "https://polaris.local/errors/out-of-stock", STAGED_AT);

        assertThat(lifetime("invalidated", "out-of-stock").count()).isEqualTo(1);
        assertThat(AssistantOutcomeMetrics.causeTag(null)).isEqualTo("none");
        assertThat(AssistantOutcomeMetrics.causeTag("https://polaris.local/errors/price-changed/")).isEqualTo("price-changed");
    }

    @Test
    @DisplayName("Given a resolved intent, then the tag is the matched definition id; no definition or a blank one is 'unknown'")
    void intent_tag_is_bounded_by_definitions() {
        IntentDefinition search = new IntentDefinition("catalog.product.search", "search", List.of());
        metrics.turn(new ResolvedIntent("catalog.product.search", 0.9, true, List.of(), search), TurnOutcome.ANSWERED);
        metrics.turn(new ResolvedIntent("made.up.by.classifier", 0.9, true, List.of(), IntentDefinition.empty()), TurnOutcome.ANSWERED);
        metrics.turn(null, TurnOutcome.FAILED);

        assertThat(meters.find(AssistantOutcomeMetrics.TURNS).tags("intent", "catalog.product.search", "outcome", "answered").counter().count())
                .isEqualTo(1);
        assertThat(meters.find(AssistantOutcomeMetrics.TURNS).tags("intent", "unknown", "outcome", "answered").counter().count())
                .isEqualTo(1);
        assertThat(meters.find(AssistantOutcomeMetrics.TURNS).tags("intent", "unknown", "outcome", "failed").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Given startup, then every known series exists at zero, so the first real event is a visible 0 -> 1 increase")
    void registers_known_series_at_zero() {
        IntentManager intents = org.mockito.Mockito.mock(IntentManager.class);
        org.mockito.Mockito.when(intents.listIntents()).thenReturn(List.of(new IntentDefinition("commerce.order.place", "order", List.of())));
        ObjectProvider<IntentManager> provider = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(intents);

        new AssistantOutcomeMetrics(meters, provider).registerKnownSeries();

        assertThat(meters.find(AssistantOutcomeMetrics.DRAFTS_STAGED).counter().count()).isZero();
        // 5 outcomes with cause none + invalidated x 3 causes
        assertThat(meters.find(AssistantOutcomeMetrics.DRAFT_LIFETIME).timers()).hasSize(8).allSatisfy(t -> assertThat(t.count()).isZero());
        assertThat(lifetime("invalidated", "price-changed")).isNotNull();
        // (configured intent + unknown) x 5 turn outcomes
        assertThat(meters.find(AssistantOutcomeMetrics.TURNS).counters()).hasSize(10);
        assertThat(meters.find(AssistantOutcomeMetrics.TURNS).tags("intent", "commerce.order.place", "outcome", "answered").counter()).isNotNull();
    }

    @Test
    @DisplayName("Given the intent store fails at startup, then draft and 'unknown' turn series are still registered")
    void registers_without_intents() {
        IntentManager intents = org.mockito.Mockito.mock(IntentManager.class);
        org.mockito.Mockito.when(intents.listIntents()).thenThrow(new IllegalStateException("redis down"));
        ObjectProvider<IntentManager> provider = org.mockito.Mockito.mock();
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(intents);

        new AssistantOutcomeMetrics(meters, provider).registerKnownSeries();

        assertThat(meters.find(AssistantOutcomeMetrics.DRAFT_LIFETIME).timers()).hasSize(8);
        assertThat(meters.find(AssistantOutcomeMetrics.TURNS).counters()).hasSize(5)
                .allSatisfy(c -> assertThat(c.getId().getTag("intent")).isEqualTo("unknown"));
    }
}
