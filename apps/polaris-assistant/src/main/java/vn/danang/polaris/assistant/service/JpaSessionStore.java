package vn.danang.polaris.assistant.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;

/**
 * {@link SessionStore} backed by {@code assistant_sessions} / {@code assistant_messages}.
 * Each call is its own short transaction, so no connection is held while the model is thinking.
 */
@Component
public class JpaSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionStore.class);

    private final AssistantSessionRepository sessionRepository;
    private final AssistantMessageRepository messageRepository;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    @Autowired
    public JpaSessionStore(
            AssistantSessionRepository sessionRepository,
            AssistantMessageRepository messageRepository,
            PlatformTransactionManager transactionManager) {
        this(sessionRepository, messageRepository, transactionManager, Clock.systemUTC());
    }

    JpaSessionStore(
            AssistantSessionRepository sessionRepository,
            AssistantMessageRepository messageRepository,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository must not be null");
        this.newTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public List<AssistantMessage> loadHistory(String sessionId, String userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        AssistantSession session = sessionRepository.findById(sessionId)
                .orElseGet(() -> openSession(sessionId, userId));
        requireOwner(session, userId);
        return new ArrayList<>(messageRepository.findBySessionIdOrderByIdAsc(sessionId));
    }

    /**
     * Appends under a row lock on the session, so concurrent turns of one session are serialized
     * and each turn's messages get contiguous ids (history is read in id order).
     */
    @Override
    @Transactional
    public void append(String sessionId, String userId, List<AssistantMessage> messages) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (messages == null || messages.isEmpty()) {
            return;
        }
        AssistantSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalStateException("Assistant session " + sessionId + " was not opened."));
        requireOwner(session, userId);
        session.setUpdatedAt(clock.instant());
        messages.forEach(message -> message.setSessionId(sessionId));
        messageRepository.saveAll(messages);
    }

    /**
     * Inserts the session in its own transaction. If a concurrent first message won the race for
     * the same id, the insert fails on the primary key; re-read the winner in a fresh transaction
     * so the caller's owner check decides (same user continues, another user gets a 403).
     */
    private AssistantSession openSession(String sessionId, String userId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A caller's transaction would still see its own snapshot after our REQUIRES_NEW insert
            // races, and would mask the owner check; see SessionStore#loadHistory.
            throw new IllegalStateException("SessionStore.loadHistory must not be called inside an active transaction.");
        }
        try {
            AssistantSession opened = newTransaction.execute(status ->
                    sessionRepository.saveAndFlush(AssistantSession.open(sessionId, userId, clock.instant())));
            log.info("Opened assistant session sessionId: {}, userId: {}", sessionId, userId);
            return opened;
        } catch (DataIntegrityViolationException e) {
            log.info("Assistant session sessionId: {} was opened concurrently; re-reading", sessionId);
            return newTransaction.execute(status -> sessionRepository.findById(sessionId))
                    .orElseThrow(() -> e);
        }
    }

    private static void requireOwner(AssistantSession session, String userId) {
        if (!session.isOwnedBy(userId)) {
            log.warn("Denied access to assistant session sessionId: {}, userId: {}", session.getId(), userId);
            throw new SessionAccessDeniedException(session.getId());
        }
    }
}
