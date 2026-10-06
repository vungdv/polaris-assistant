package vn.danang.polaris.assistant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.danang.polaris.assistant.entity.AssistantMessage;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    /** Conversation history in append order (identity ids are assigned in insertion order). */
    List<AssistantMessage> findBySessionIdOrderByIdAsc(String sessionId);
}
