package vn.danang.polaris.assistant.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A conversation between one authenticated caller and the assistant (ADR-0004 §3.A). The session
 * id is chosen by the client; {@code userId} records who opened it, and only that caller may
 * continue it or act on its drafts.
 */
@Entity
@Table(name = "assistant_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssistantSession {

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false, updatable = false)
    private String userId;

    @Setter
    @Column(name = "customer_id")
    private Long customerId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private SessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static AssistantSession open(String id, String userId, Instant now) {
        AssistantSession session = new AssistantSession();
        session.id = Objects.requireNonNull(id, "id must not be null");
        session.userId = Objects.requireNonNull(userId, "userId must not be null");
        session.status = SessionStatus.ACTIVE;
        session.createdAt = Objects.requireNonNull(now, "now must not be null");
        session.updatedAt = now;
        return session;
    }

    public boolean isOwnedBy(String callerId) {
        return userId.equals(callerId);
    }
}
