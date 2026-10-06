package vn.danang.polaris.assistant.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics.DraftOutcome;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.web.exception.DraftConflictException;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;

/**
 * Draft lifecycle for the model-callable tools (BPMN {@code A_Stage}, {@code A_EndDeclined}): stage a
 * priced draft and discard the open one. Nothing here touches Order Management's data.
 * <p>
 * Each operation is one short transaction that locks the session row first
 * ({@link AssistantSessionRepository#findByIdForUpdate}), which both owner-checks the caller and keeps the
 * lock order session → drafts shared with {@code SessionStore#append}. Concurrent stagers of one session
 * are therefore serialized; if a concurrent writer that does not take the session lock still wins a race
 * (optimistic-lock conflict, or the {@code uq_assistant_drafts_open_session} unique violation), the whole
 * transaction is rolled back and retried once in a fresh transaction, never continued. Any other integrity
 * violation is not a race and propagates unchanged.
 */
@Service
public class OrderDraftService {

    private static final Logger log = LoggerFactory.getLogger(OrderDraftService.class);
    private static final int MAX_ATTEMPTS = 2;
    /** V14's UNIQUE (open_session_id): the only integrity violation that means "lost a staging race". */
    static final String OPEN_DRAFT_UNIQUE_CONSTRAINT = "uq_assistant_drafts_open_session";

    private final AssistantSessionRepository sessionRepository;
    private final OrderDraftRepository draftRepository;
    private final TransactionTemplate transaction;
    private final AssistantOutcomeMetrics outcomeMetrics;
    private final Clock clock;

    @Autowired
    public OrderDraftService(
            AssistantSessionRepository sessionRepository,
            OrderDraftRepository draftRepository,
            PlatformTransactionManager transactionManager,
            AssistantOutcomeMetrics outcomeMetrics) {
        this(sessionRepository, draftRepository, transactionManager, outcomeMetrics, Clock.systemUTC());
    }

    OrderDraftService(
            AssistantSessionRepository sessionRepository,
            OrderDraftRepository draftRepository,
            PlatformTransactionManager transactionManager,
            AssistantOutcomeMetrics outcomeMetrics,
            Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository must not be null");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.outcomeMetrics = Objects.requireNonNull(outcomeMetrics, "outcomeMetrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Stages a new {@code WAITING_CONFIRMATION} draft with the given price snapshot and
     * {@code expiresAt = now + 15 min}. A still-open draft of the session is superseded (cancelled, or
     * expired if its TTL already elapsed), so every staging yields a new draft id.
     *
     * @throws SessionAccessDeniedException if the session belongs to another user
     * @throws DraftConflictException       if a concurrent draft change kept winning
     */
    @CustomNextSpan(
            name = "assistant.order_draft.stage",
            tags = {
                    @SpanTag(key = "agent.session_id", expression = "#sessionId"),
                    @SpanTag(key = "assistant.draft.line_count", expression = "#lines?.size()")
            },
            resultTags = {
                    @SpanTag(key = "assistant.draft.id", expression = "#result?.id")
            }
    )
    public OrderDraft stage(String sessionId, String userId, Long customerId, List<DraftLine> lines) {
        OrderDraft draft = inTransaction(sessionId, () -> {
            Instant now = clock.instant();
            lockOwnedSession(sessionId, userId);
            expireLapsedOpenDraft(sessionId, now);
            draftRepository.findOpenDraft(sessionId)
                    .ifPresent(open -> outcomeMetrics.draftClosed(open, DraftOutcome.SUPERSEDED, now));
            OrderDraft staged = draftRepository.supersedeOpenDraft(
                    OrderDraft.stage(sessionId, customerId, lines, OrderDraft.DEFAULT_TTL, now), now);
            outcomeMetrics.draftStaged();
            return staged;
        });
        log.info("Staged order draft draftId={} sessionId={} customerId={} lines={} total={} expiresAt={}",
                draft.getId(), sessionId, customerId, draft.getItems().size(), draft.getTotalAmount(), draft.getExpiresAt());
        return draft;
    }

    /**
     * Discards the session's open draft: {@code CANCELLED}, or {@code EXPIRED} if its TTL already elapsed.
     *
     * @return the draft after the transition, or empty if the session had no open draft
     * @throws SessionAccessDeniedException if the session belongs to another user
     * @throws DraftConflictException       if a concurrent draft change kept winning
     */
    @CustomNextSpan(
            name = "assistant.order_draft.discard",
            tags = {
                    @SpanTag(key = "agent.session_id", expression = "#sessionId")
            }
    )
    public Optional<OrderDraft> discardOpenDraft(String sessionId, String userId) {
        Optional<OrderDraft> discarded = inTransaction(sessionId, () -> {
            Instant now = clock.instant();
            lockOwnedSession(sessionId, userId);
            return draftRepository.findOpenDraft(sessionId).map(open -> {
                if (open.isPastExpiry(now)) {
                    open.expire(now);
                    outcomeMetrics.draftClosed(open, DraftOutcome.EXPIRED, now);
                } else {
                    open.cancel(now);
                    outcomeMetrics.draftClosed(open, DraftOutcome.DISCARDED, now);
                }
                return draftRepository.saveAndFlush(open);
            });
        });
        discarded.ifPresent(draft -> log.info("Discarded order draft draftId={} sessionId={} status={}",
                draft.getId(), sessionId, draft.getStatus()));
        return discarded;
    }

    private void lockOwnedSession(String sessionId, String userId) {
        AssistantSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalStateException("Assistant session " + sessionId + " was not opened."));
        if (!session.isOwnedBy(userId)) {
            log.warn("Denied draft change on assistant session sessionId={} userId={}", sessionId, userId);
            throw new SessionAccessDeniedException(sessionId);
        }
    }

    private void expireLapsedOpenDraft(String sessionId, Instant now) {
        draftRepository.findOpenDraft(sessionId)
                .filter(open -> open.isPastExpiry(now))
                .ifPresent(open -> {
                    open.expire(now);
                    draftRepository.saveAndFlush(open);
                    outcomeMetrics.draftClosed(open, DraftOutcome.EXPIRED, now);
                });
    }

    /** True only for the "one open draft per session" constraint; other integrity errors are real bugs. */
    static boolean isOpenDraftUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(OPEN_DRAFT_UNIQUE_CONSTRAINT)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private <T> T inTransaction(String sessionId, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A retry needs a fresh transaction; joining the caller's would continue a rollback-only one.
            throw new IllegalStateException("OrderDraftService must not be called inside an active transaction.");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                if (e instanceof DataIntegrityViolationException && !isOpenDraftUniqueViolation(e)) {
                    throw e;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("Order draft change lost to a concurrent writer sessionId={} attempts={}", sessionId, attempt);
                    throw new DraftConflictException(sessionId, e);
                }
                log.info("Order draft change conflicted, retrying sessionId={} attempt={} error={}",
                        sessionId, attempt, e.getClass().getSimpleName());
            }
        }
    }
}
