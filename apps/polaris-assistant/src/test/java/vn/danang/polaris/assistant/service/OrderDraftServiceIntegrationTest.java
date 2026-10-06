package vn.danang.polaris.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * {@link OrderDraftService} against real PostgreSQL: staging supersedes with a new draft id, lapsed drafts
 * expire, the owner check holds, and concurrent staging of one session leaves exactly one open draft.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderDraftServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final List<DraftLine> LINES = List.of(
            DraftLine.of("NG-CHARGER-01", "Fast Charger 65W", 2, new BigDecimal("24.90")));

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderDraftService springService;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OrderDraftService serviceAt(Instant now) {
        return new OrderDraftService(sessionRepository, draftRepository, transactionManager, new AssistantOutcomeMetrics(meters),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private String newSession(String userId) {
        String id = "sess-" + UUID.randomUUID();
        sessionRepository.saveAndFlush(AssistantSession.open(id, userId, NOW));
        return id;
    }

    private List<OrderDraft> draftsOf(String sessionId) {
        return draftRepository.findAll().stream().filter(d -> d.belongsTo(sessionId)).toList();
    }

    @Nested
    @DisplayName("1. Staging")
    class Staging {

        @Test
        @DisplayName("Given a session, when staged, then an open draft with snapshot, total and 15-minute TTL is persisted")
        void stages_open_draft() {
            String sessionId = newSession("alice");

            OrderDraft draft = serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            OrderDraft stored = draftRepository.findById(draft.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            assertThat(stored.getCustomerId()).isEqualTo(7L);
            assertThat(stored.getTotalAmount()).isEqualByComparingTo("49.80");
            assertThat(stored.getExpiresAt()).isEqualTo(NOW.plus(OrderDraft.DEFAULT_TTL));
            assertThat(stored.getItems()).containsExactlyElementsOf(LINES);
        }

        @Test
        @DisplayName("Given an open draft, when re-staged, then a new draft id is open and the old one is CANCELLED")
        void restaging_supersedes_with_new_id() {
            String sessionId = newSession("alice");
            OrderDraft first = serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            OrderDraft second = serviceAt(NOW.plusSeconds(60)).stage(sessionId, "alice", 7L, LINES);

            assertThat(second.getId()).isNotEqualTo(first.getId());
            assertThat(draftRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELLED);
            assertThat(draftRepository.findOpenDraft(sessionId)).map(OrderDraft::getId).contains(second.getId());
        }

        @Test
        @DisplayName("Given an open draft past its TTL, when re-staged, then the old draft is EXPIRED, not cancelled")
        void restaging_expires_lapsed_draft() {
            String sessionId = newSession("alice");
            OrderDraft first = serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            serviceAt(NOW.plus(OrderDraft.DEFAULT_TTL).plusSeconds(1)).stage(sessionId, "alice", 7L, LINES);

            assertThat(draftRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(DraftStatus.EXPIRED);
        }

        @Test
        @DisplayName("Given another user's session, when staging, then 403-style denial and no draft")
        void other_user_is_denied() {
            String sessionId = newSession("alice");

            assertThatThrownBy(() -> serviceAt(NOW).stage(sessionId, "mallory", 7L, LINES))
                    .isInstanceOf(SessionAccessDeniedException.class);
            assertThat(draftsOf(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("Given an active transaction, when staging, then refused so a retry never continues a rollback-only transaction")
        void refuses_inside_active_transaction() {
            String sessionId = newSession("alice");

            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    springService.stage(sessionId, "alice", 7L, LINES)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Given concurrent staging on one session, then all succeed serialized and exactly one draft stays open")
        void concurrent_staging_leaves_one_open_draft() throws Exception {
            String sessionId = newSession("alice");
            int threads = 6;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<OrderDraft>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<OrderDraft> task = () -> {
                        start.await();
                        return springService.stage(sessionId, "alice", 7L, LINES);
                    };
                    futures.add(pool.submit(task));
                }
                start.countDown();
                for (Future<OrderDraft> future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdownNow();
            }

            List<OrderDraft> drafts = draftsOf(sessionId);
            assertThat(drafts).hasSize(threads);
            assertThat(drafts).filteredOn(OrderDraft::isOpen).hasSize(1);
            assertThat(drafts).filteredOn(d -> d.getStatus() == DraftStatus.CANCELLED).hasSize(threads - 1);
        }
    }

    @Test
    @DisplayName("Given a real second open draft insert, then PostgreSQL's violation is recognized as the open-draft race the service retries")
    void recognizes_real_open_draft_unique_violation() {
        String sessionId = newSession("alice");
        serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

        Throwable violation = org.assertj.core.api.Assertions.catchThrowable(() ->
                draftRepository.saveAndFlush(OrderDraft.stage(sessionId, 7L, LINES, OrderDraft.DEFAULT_TTL, NOW)));

        assertThat(violation).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(OrderDraftService.isOpenDraftUniqueViolation(violation)).isTrue();
    }

    @Nested
    @DisplayName("2. Discarding")
    class Discarding {

        @Test
        @DisplayName("Given an open draft, when discarded, then it is CANCELLED and no draft stays open")
        void discards_open_draft() {
            String sessionId = newSession("alice");
            OrderDraft draft = serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            assertThat(serviceAt(NOW.plusSeconds(10)).discardOpenDraft(sessionId, "alice"))
                    .get().extracting(OrderDraft::getStatus).isEqualTo(DraftStatus.CANCELLED);
            assertThat(draftRepository.findById(draft.getId()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELLED);
            assertThat(draftRepository.findOpenDraft(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("Given a lapsed open draft, when discarded, then it is EXPIRED")
        void discarding_lapsed_draft_expires_it() {
            String sessionId = newSession("alice");
            serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            assertThat(serviceAt(NOW.plus(OrderDraft.DEFAULT_TTL)).discardOpenDraft(sessionId, "alice"))
                    .get().extracting(OrderDraft::getStatus).isEqualTo(DraftStatus.EXPIRED);
        }

        @Test
        @DisplayName("Given no open draft, when discarded, then empty")
        void nothing_to_discard() {
            String sessionId = newSession("alice");

            assertThat(serviceAt(NOW).discardOpenDraft(sessionId, "alice")).isEmpty();
        }

        @Test
        @DisplayName("Given another user's session, when discarding, then denied and the draft stays open")
        void other_user_cannot_discard() {
            String sessionId = newSession("alice");
            serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            assertThatThrownBy(() -> serviceAt(NOW).discardOpenDraft(sessionId, "mallory"))
                    .isInstanceOf(SessionAccessDeniedException.class);
            assertThat(draftRepository.findOpenDraft(sessionId)).isPresent();
        }
    }

    @Nested
    @DisplayName("4. Outcome metrics")
    class OutcomeMetrics {

        private double closed(String outcome) {
            var timer = meters.find("polaris.assistant.draft.lifetime").tags("outcome", outcome, "cause", "none").timer();
            return timer != null ? timer.count() : 0;
        }

        private double staged() {
            var counter = meters.find("polaris.assistant.drafts.staged").counter();
            return counter != null ? counter.count() : 0;
        }

        @Test
        @DisplayName("Given a draft re-staged, then two staged and the first one 'superseded'")
        void restaging_counts_superseded() {
            String sessionId = newSession("alice");
            serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);
            serviceAt(NOW.plusSeconds(60)).stage(sessionId, "alice", 7L, LINES);

            assertThat(staged()).isEqualTo(2);
            assertThat(closed("superseded")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given a lapsed draft re-staged, then it counts as 'expired', not 'superseded'")
        void lapsed_restaging_counts_expired() {
            String sessionId = newSession("alice");
            serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);
            serviceAt(NOW.plus(OrderDraft.DEFAULT_TTL).plusSeconds(1)).stage(sessionId, "alice", 7L, LINES);

            assertThat(closed("expired")).isEqualTo(1);
            assertThat(closed("superseded")).isZero();
        }

        @Test
        @DisplayName("Given the model discards the open draft, then 'discarded'; nothing to discard counts nothing")
        void discard_counts_discarded() {
            String sessionId = newSession("alice");
            serviceAt(NOW).stage(sessionId, "alice", 7L, LINES);

            serviceAt(NOW.plusSeconds(30)).discardOpenDraft(sessionId, "alice");
            serviceAt(NOW.plusSeconds(40)).discardOpenDraft(sessionId, "alice");

            assertThat(closed("discarded")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given staging is denied, then nothing is counted")
        void denied_staging_counts_nothing() {
            String sessionId = newSession("alice");

            assertThatThrownBy(() -> serviceAt(NOW).stage(sessionId, "mallory", 7L, LINES))
                    .isInstanceOf(SessionAccessDeniedException.class);

            assertThat(staged()).isZero();
        }
    }
}
