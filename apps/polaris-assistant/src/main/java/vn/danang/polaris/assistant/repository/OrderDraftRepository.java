package vn.danang.polaris.assistant.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.assistant.entity.OrderDraft;

public interface OrderDraftRepository extends JpaRepository<OrderDraft, String> {

    /** Scoped to the session so a draft id from another session never resolves (IDOR). */
    Optional<OrderDraft> findByIdAndSessionId(String id, String sessionId);

    /**
     * The session's open ({@code WAITING_CONFIRMATION}) draft, if any. Looked up through the
     * unique {@code open_session_id} column, which is set only while a draft is open.
     */
    default Optional<OrderDraft> findOpenDraft(String sessionId) {
        return findByOpenSessionId(sessionId);
    }

    Optional<OrderDraft> findByOpenSessionId(String sessionId);

    /**
     * Makes {@code replacement} the session's only open draft: the current open draft (if any) is
     * cancelled and flushed <em>before</em> the replacement is inserted. The flush matters because
     * Hibernate runs inserts before updates, so without it the new row would hit the UNIQUE
     * {@code open_session_id} while the old one still holds it. Joins the caller's transaction.
     * <p>
     * Lock ordering: if the same transaction also appends messages to the session, lock the session
     * first ({@link AssistantSessionRepository#findByIdForUpdate}) and only then call this, so draft
     * writers and {@code SessionStore#append} always lock session before drafts.
     *
     * @param replacement a freshly {@linkplain OrderDraft#stage staged} draft
     * @return the persisted replacement
     */
    @Transactional
    default OrderDraft supersedeOpenDraft(OrderDraft replacement, Instant now) {
        if (!replacement.isOpen()) {
            throw new IllegalArgumentException("Replacement draft " + replacement.getId() + " must be open.");
        }
        findOpenDraft(replacement.getSessionId()).ifPresent(current -> {
            current.cancel(now);
            saveAndFlush(current);
        });
        return saveAndFlush(replacement);
    }
}
