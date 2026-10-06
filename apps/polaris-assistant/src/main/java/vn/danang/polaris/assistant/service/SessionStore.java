package vn.danang.polaris.assistant.service;

import java.util.List;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;

/**
 * Durable conversation store: owns the assistant session and its message history, and enforces
 * that only the caller who opened a session can read or extend it.
 */
public interface SessionStore {

    /**
     * Returns the session's history, oldest first, as a mutable list. Opens the session for
     * {@code userId} if it doesn't exist yet.
     * <p>
     * Must not be called inside an active transaction: opening the session commits in its own
     * transaction so a concurrent first message can be re-read and owner-checked.
     *
     * @throws IllegalStateException if called inside an active transaction
     * @throws SessionAccessDeniedException if the session belongs to another user
     */
    List<AssistantMessage> loadHistory(String sessionId, String userId);

    /**
     * Appends the turn's new messages to the session, in order.
     *
     * @throws SessionAccessDeniedException if the session belongs to another user
     */
    void append(String sessionId, String userId, List<AssistantMessage> messages);
}
