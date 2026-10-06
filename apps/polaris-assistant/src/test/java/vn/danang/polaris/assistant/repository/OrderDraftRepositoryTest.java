package vn.danang.polaris.assistant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * Proves the V14 {@code assistant_order_drafts} schema and the {@link OrderDraft} mapping agree
 * against real PostgreSQL: JSON snapshot round-trip, optimistic locking, and the database-enforced
 * "one open draft per session" invariant.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderDraftRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final List<DraftLine> LINES = List.of(
            DraftLine.of("NG-CHARGER-01", "Fast Charger 65W", 2, new BigDecimal("24.90")),
            DraftLine.of("NG-CABLE-02", "USB-C Cable", 1, new BigDecimal("9.50")));

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String newSession() {
        String id = "sess-" + UUID.randomUUID();
        sessionRepository.saveAndFlush(AssistantSession.open(id, "user-" + id, NOW));
        return id;
    }

    private OrderDraft stagedDraft(String sessionId) {
        return draftRepository.saveAndFlush(OrderDraft.stage(sessionId, 7L, LINES, OrderDraft.DEFAULT_TTL, NOW));
    }

    // =========================================================================
    // 1. Happy path
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given a staged draft, when reloaded, then the JSON price snapshot, total and TTL round-trip exactly")
        void round_trips_snapshot_total_and_expiry() {
            String sessionId = newSession();
            OrderDraft saved = stagedDraft(sessionId);
            entityManager.clear();

            OrderDraft reloaded = draftRepository.findById(saved.getId()).orElseThrow();

            assertThat(reloaded.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            assertThat(reloaded.getItems()).containsExactlyElementsOf(LINES);
            assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("59.30");
            assertThat(reloaded.getExpiresAt()).isEqualTo(NOW.plus(15, ChronoUnit.MINUTES));
            assertThat(reloaded.getCustomerId()).isEqualTo(7L);
            assertThat(reloaded.getVersion()).isZero();
        }

        @Test
        @DisplayName("Given an open draft, when looked up, then findOpenDraft returns it and the session-scoped lookup finds it")
        void finds_open_draft_and_session_scoped_draft() {
            String sessionId = newSession();
            OrderDraft draft = stagedDraft(sessionId);

            assertThat(draftRepository.findOpenDraft(sessionId)).map(OrderDraft::getId).contains(draft.getId());
            assertThat(draftRepository.findByIdAndSessionId(draft.getId(), sessionId)).isPresent();
        }

        @Test
        @DisplayName("Given a confirmed draft, when persisted, then status, order number and key are stored, version bumps and it is no longer open")
        void persists_confirmation_and_bumps_version() {
            String sessionId = newSession();
            OrderDraft draft = stagedDraft(sessionId);

            draft.confirm("ORD-000042", "idem-1", NOW.plusSeconds(60));
            OrderDraft confirmed = draftRepository.saveAndFlush(draft);
            entityManager.clear();

            OrderDraft reloaded = draftRepository.findById(confirmed.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(DraftStatus.CONFIRMED);
            assertThat(reloaded.getConfirmedOrderNumber()).isEqualTo("ORD-000042");
            assertThat(reloaded.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(reloaded.getVersion()).isEqualTo(1L);
            assertThat(draftRepository.findOpenDraft(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("Given the open draft was cancelled, when a new draft is staged in the same session, then it is accepted")
        void allows_new_open_draft_once_previous_left_open_state() {
            String sessionId = newSession();
            OrderDraft first = stagedDraft(sessionId);
            first.cancel(NOW.plusSeconds(30));
            draftRepository.saveAndFlush(first);

            OrderDraft second = stagedDraft(sessionId);

            assertThat(draftRepository.findOpenDraft(sessionId)).map(OrderDraft::getId).contains(second.getId());
        }
    }

    // =========================================================================
    // 2. Invalid input & invariants
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & invariants")
    class InvalidInput {

        @Test
        @DisplayName("Given a session with an open draft, when a second open draft is inserted, then the database rejects it")
        void rejects_second_open_draft_in_same_session() {
            String sessionId = newSession();
            stagedDraft(sessionId);

            assertThatThrownBy(() -> stagedDraft(sessionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Given a WAITING_CONFIRMATION row without open_session_id, when inserted natively, then the CHECK constraint rejects it")
        void rejects_open_draft_without_open_session_marker() {
            String sessionId = newSession();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO assistant_order_drafts (id, session_id, customer_id, status, items, total_amount, expires_at) "
                            + "VALUES (?, ?, 7, 'WAITING_CONFIRMATION', CAST('[]' AS JSON), 1.00, CURRENT_TIMESTAMP)",
                    "dft-" + UUID.randomUUID(), sessionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Given a closed status that still carries open_session_id, when inserted natively, then the CHECK constraint rejects it")
        void rejects_closed_draft_with_open_session_marker() {
            String sessionId = newSession();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO assistant_order_drafts (id, session_id, customer_id, status, items, total_amount, expires_at, open_session_id) "
                            + "VALUES (?, ?, 7, 'CANCELLED', CAST('[]' AS JSON), 1.00, CURRENT_TIMESTAMP, ?)",
                    "dft-" + UUID.randomUUID(), sessionId, sessionId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Given a draft for an unknown session, when saved, then the foreign key rejects it")
        void rejects_draft_for_unknown_session() {
            assertThatThrownBy(() -> stagedDraft("sess-does-not-exist"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Given two copies of the same draft, when both are confirmed, then the stale one loses with an optimistic-lock conflict")
        void double_confirm_with_stale_version_fails() {
            String sessionId = newSession();
            String draftId = stagedDraft(sessionId).getId();

            OrderDraft first = tx.execute(s -> draftRepository.findById(draftId).orElseThrow());
            OrderDraft second = tx.execute(s -> draftRepository.findById(draftId).orElseThrow());

            first.confirm("ORD-000001", "idem-a", NOW.plusSeconds(10));
            draftRepository.saveAndFlush(first);

            second.confirm("ORD-000002", "idem-b", NOW.plusSeconds(11));
            assertThatThrownBy(() -> draftRepository.saveAndFlush(second))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertThat(draftRepository.findById(draftId).orElseThrow().getConfirmedOrderNumber()).isEqualTo("ORD-000001");
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given a draft id from another session, when looked up scoped to a different session, then it is not found")
        void does_not_resolve_draft_through_another_session() {
            String owner = newSession();
            String other = newSession();
            OrderDraft draft = stagedDraft(owner);

            assertThat(draftRepository.findByIdAndSessionId(draft.getId(), other)).isEmpty();
            assertThat(draftRepository.findOpenDraft(other)).isEmpty();
        }

        @Test
        @DisplayName("Given an open draft, when superseded inside one transaction, then the old draft is CANCELLED and a new draft with a new id is the open one")
        void supersedes_open_draft_within_one_transaction() {
            String sessionId = newSession();
            OrderDraft original = stagedDraft(sessionId);
            List<DraftLine> newLines = List.of(DraftLine.of("NG-CHARGER-01", "Fast Charger 65W", 3, new BigDecimal("24.90")));

            OrderDraft replacement = tx.execute(s -> {
                // Old draft is managed in this persistence context, as it will be in S6's staging tool.
                draftRepository.findOpenDraft(sessionId).orElseThrow();
                return draftRepository.supersedeOpenDraft(
                        OrderDraft.stage(sessionId, 7L, newLines, OrderDraft.DEFAULT_TTL, NOW.plusSeconds(120)),
                        NOW.plusSeconds(120));
            });
            entityManager.clear();

            assertThat(replacement.getId()).isNotEqualTo(original.getId());
            assertThat(draftRepository.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELLED);
            OrderDraft open = draftRepository.findOpenDraft(sessionId).orElseThrow();
            assertThat(open.getId()).isEqualTo(replacement.getId());
            assertThat(open.getTotalAmount()).isEqualByComparingTo("74.70");
        }

        @Test
        @DisplayName("Given no open draft, when superseded, then the replacement simply becomes the open draft")
        void supersede_without_open_draft_just_stages() {
            String sessionId = newSession();

            OrderDraft replacement = draftRepository.supersedeOpenDraft(
                    OrderDraft.stage(sessionId, 7L, LINES, OrderDraft.DEFAULT_TTL, NOW), NOW);

            assertThat(draftRepository.findOpenDraft(sessionId)).map(OrderDraft::getId).contains(replacement.getId());
        }

        @Test
        @DisplayName("Given a superseded draft, when its old card is confirmed, then it is refused because the draft is no longer open")
        void confirming_superseded_draft_is_refused() {
            String sessionId = newSession();
            String oldId = stagedDraft(sessionId).getId();
            draftRepository.supersedeOpenDraft(OrderDraft.stage(sessionId, 7L, LINES, OrderDraft.DEFAULT_TTL, NOW), NOW);

            OrderDraft old = draftRepository.findByIdAndSessionId(oldId, sessionId).orElseThrow();

            assertThatThrownBy(() -> old.requireConfirmable(NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Given one transaction that cancels and inserts without an intermediate flush, then the UNIQUE open_session_id rejects it (why supersede flushes)")
        void cancel_then_insert_without_flush_violates_unique() {
            String sessionId = newSession();
            stagedDraft(sessionId);

            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                OrderDraft current = draftRepository.findOpenDraft(sessionId).orElseThrow();
                current.cancel(NOW);
                draftRepository.save(OrderDraft.stage(sessionId, 7L, LINES, OrderDraft.DEFAULT_TTL, NOW));
            })).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(draftRepository.findOpenDraft(sessionId)).isPresent();
        }

        @Test
        @DisplayName("Given a session with drafts, when the session is deleted, then its drafts are deleted by cascade")
        void cascades_session_delete_to_drafts() {
            String sessionId = newSession();
            OrderDraft draft = stagedDraft(sessionId);

            sessionRepository.deleteById(sessionId);
            sessionRepository.flush();
            entityManager.clear();

            assertThat(draftRepository.findById(draft.getId())).isEmpty();
        }
    }
}
