package vn.danang.polaris.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;

/**
 * Unit tests for {@link JpaSessionStore}'s open-session race: two first messages with the same
 * new session id. The loser's insert fails on the primary key; it must re-read the winner and let
 * the owner check decide instead of surfacing a 500.
 */
class JpaSessionStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private AssistantSessionRepository sessionRepository;
    private AssistantMessageRepository messageRepository;
    private JpaSessionStore store;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AssistantSessionRepository.class);
        messageRepository = mock(AssistantMessageRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        store = new JpaSessionStore(sessionRepository, messageRepository, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
        when(messageRepository.findBySessionIdOrderByIdAsc("sess-1")).thenReturn(List.of());
    }

    private void loseOpenRaceTo(String winner) {
        when(sessionRepository.findById("sess-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(AssistantSession.open("sess-1", winner, NOW)));
        when(sessionRepository.saveAndFlush(any(AssistantSession.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key assistant_sessions_pkey"));
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given the same user won the race, when loading history, then the winner's session is used")
        void same_user_continues_after_losing_the_race() {
            loseOpenRaceTo("user-alice");

            assertThat(store.loadHistory("sess-1", "user-alice")).isEmpty();
        }
    }

    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("Given another user won the race, when loading history, then SessionAccessDeniedException (403), not a 500")
        void other_user_gets_access_denied_after_losing_the_race() {
            loseOpenRaceTo("user-alice");

            assertThatThrownBy(() -> store.loadHistory("sess-1", "user-mallory"))
                    .isInstanceOf(SessionAccessDeniedException.class);
            verify(messageRepository, never()).findBySessionIdOrderByIdAsc("sess-1");
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given the insert failed but the session is gone on re-read, when loading history, then the original integrity error propagates")
        void propagates_integrity_error_when_winner_cannot_be_found() {
            when(sessionRepository.findById("sess-1")).thenReturn(Optional.empty());
            when(sessionRepository.saveAndFlush(any(AssistantSession.class)))
                    .thenThrow(new DataIntegrityViolationException("value too long"));

            assertThatThrownBy(() -> store.loadHistory("sess-1", "user-alice"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
