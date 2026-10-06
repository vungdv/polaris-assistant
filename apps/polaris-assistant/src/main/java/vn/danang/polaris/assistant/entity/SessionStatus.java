package vn.danang.polaris.assistant.entity;

/**
 * Lifecycle states of an {@link AssistantSession} (ADR-0004 §3.A).
 */
public enum SessionStatus {
    ACTIVE,
    WAITING_CONFIRMATION,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    CLOSED
}
