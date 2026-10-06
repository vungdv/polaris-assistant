package vn.danang.polaris.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.web.exception.DraftConflictException;

/**
 * Conflict handling of {@link OrderDraftService}: a lost race rolls the whole transaction back and is
 * retried once in a fresh transaction; a second loss surfaces as {@link DraftConflictException}.
 */
class OrderDraftServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final List<DraftLine> LINES = List.of(DraftLine.of("SKU-1", "Item", 1, new BigDecimal("5.00")));

    private AssistantSessionRepository sessionRepository;
    private OrderDraftRepository draftRepository;
    private PlatformTransactionManager transactionManager;
    private OrderDraftService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AssistantSessionRepository.class);
        draftRepository = mock(OrderDraftRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(sessionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(AssistantSession.open("s1", "alice", NOW)));
        when(draftRepository.findOpenDraft(anyString())).thenReturn(Optional.empty());
        service = new OrderDraftService(sessionRepository, draftRepository, transactionManager, AssistantOutcomeMetrics.detached(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Given the first attempt loses an optimistic-lock race, when staging, then it is rolled back and retried once")
    void retries_once_after_conflict() {
        when(draftRepository.supersedeOpenDraft(any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(OrderDraft.class, "dft-old"))
                .thenAnswer(inv -> inv.getArgument(0));

        OrderDraft draft = service.stage("s1", "alice", 7L, LINES);

        assertThat(draft.isOpen()).isTrue();
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    @DisplayName("Given every attempt hits the open-draft unique constraint, when staging, then DraftConflictException")
    void gives_up_after_second_conflict() {
        when(draftRepository.supersedeOpenDraft(any(), any()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new RuntimeException("duplicate key value violates unique constraint \"uq_assistant_drafts_open_session\"")));

        assertThatThrownBy(() -> service.stage("s1", "alice", 7L, LINES)).isInstanceOf(DraftConflictException.class);
        verify(transactionManager, times(2)).rollback(any());
    }

    @Test
    @DisplayName("Given an integrity violation other than the open-draft constraint, when staging, then it propagates without retry")
    void other_integrity_violations_are_not_retried() {
        when(draftRepository.supersedeOpenDraft(any(), any()))
                .thenThrow(new DataIntegrityViolationException("violates check constraint \"chk_assistant_drafts_status\""));

        assertThatThrownBy(() -> service.stage("s1", "alice", 7L, LINES)).isInstanceOf(DataIntegrityViolationException.class);
        verify(transactionManager, times(1)).rollback(any());
    }
}
