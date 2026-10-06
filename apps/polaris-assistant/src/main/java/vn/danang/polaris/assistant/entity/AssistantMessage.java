package vn.danang.polaris.assistant.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "assistant_messages")
@Getter
@Setter
public final class AssistantMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable only for rows written before V14; the SessionStore always sets it.
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private MessageRole role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** On a reply row: the comma-separated types of the cards in {@link #widgetPayload}; null otherwise. */
    @Column(name = "widget_type", length = 64)
    private String widgetType;

    /**
     * Meaning depends on the row:
     * <ul>
     *   <li>tool-call row (role ASSISTANT with {@link #toolCallId} set): the JSON arguments of the model's
     *       tool call, replayed to the model as {@code functionCall.args};</li>
     *   <li>reply row (role ASSISTANT, no tool call id): the turn's cards as a JSON array
     *       {@code [{type, payload}]}, the same {@code widgets} the chat response returned; never sent to the model.</li>
     * </ul>
     */
    @Column(name = "widget_payload", columnDefinition = "TEXT")
    private String widgetPayload;

    @Column(name = "tool_call_id", length = 64)
    private String toolCallId;

    @Column(name = "thought_signature", columnDefinition = "TEXT")
    private String thoughtSignature;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    public static AssistantMessage of(String messageText){
        var userMsg = new AssistantMessage();
        userMsg.setRole(MessageRole.USER);
        userMsg.setContent(messageText);
        userMsg.setCreatedAt(Instant.now());
        return userMsg;
    }

    public static AssistantMessage of(String message, MessageRole messageRole, String thoughtSignature) {
        return AssistantMessage.of(message, messageRole, thoughtSignature, "");
    }

    public static AssistantMessage of(String message, MessageRole messageRole, String thoughtSignature, String toolCallId) {
        var msg = new AssistantMessage();
        msg.setContent(message);
        msg.setRole(messageRole != null ? messageRole : MessageRole.USER);
        msg.setThoughtSignature(thoughtSignature);
        msg.setCreatedAt(Instant.now());
        msg.toolCallId = toolCallId;
        return msg;
    }
}
