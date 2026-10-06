package vn.danang.polaris.assistant.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;

/**
 * Test double for {@link SessionStore} with the same contract as {@link JpaSessionStore}
 * (open on first load, owner check, ordered history), kept in memory.
 */
class InMemorySessionStore implements SessionStore {

    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, List<AssistantMessage>> messages = new HashMap<>();

    @Override
    public List<AssistantMessage> loadHistory(String sessionId, String userId) {
        owners.putIfAbsent(sessionId, userId);
        requireOwner(sessionId, userId);
        return new ArrayList<>(messages.getOrDefault(sessionId, List.of()));
    }

    @Override
    public void append(String sessionId, String userId, List<AssistantMessage> newMessages) {
        requireOwner(sessionId, userId);
        newMessages.forEach(m -> m.setSessionId(sessionId));
        messages.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(newMessages);
    }

    List<AssistantMessage> persisted(String sessionId) {
        return messages.getOrDefault(sessionId, List.of());
    }

    private void requireOwner(String sessionId, String userId) {
        if (!userId.equals(owners.get(sessionId))) {
            throw new SessionAccessDeniedException(sessionId);
        }
    }
}
