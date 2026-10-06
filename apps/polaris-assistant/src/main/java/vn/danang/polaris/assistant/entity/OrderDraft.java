package vn.danang.polaris.assistant.entity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import vn.danang.polaris.web.exception.DraftExpiredException;

/**
 * A staged, priced order awaiting the shopper's explicit confirmation (ADR-0004 §3.B).
 * <p>
 * The state machine lives here: {@link DraftStatus#WAITING_CONFIRMATION} is the only open state,
 * and every transition out of it goes through one of the guarded methods below, so an illegal move
 * (e.g. confirming a cancelled draft) fails fast with {@link IllegalStateException}. {@code @Version}
 * makes two concurrent transitions on the same draft (double confirm) lose with an optimistic-lock
 * conflict rather than both succeeding.
 * <p>
 * At most one draft per session is open. {@code openSessionId} mirrors {@code sessionId} while the
 * draft is open and is cleared on leaving that state; its UNIQUE constraint makes the database reject
 * a second open draft.
 * <p>
 * A draft id pins exactly the snapshot the shopper saw: a draft's lines, total and TTL never change
 * after staging. Re-staging supersedes the open draft (it is cancelled and a new draft with a new
 * id is staged, see {@code OrderDraftRepository#supersedeOpenDraft}), so confirming an old draft card
 * hits a non-open draft and is refused.
 */
@Entity
@Table(name = "assistant_order_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderDraft {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false, updatable = false)
    private String sessionId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private DraftStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", nullable = false)
    private List<DraftLine> items;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_order_number", length = 64)
    private String confirmedOrderNumber;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Getter(AccessLevel.NONE)
    @Column(name = "open_session_id", length = 64)
    private String openSessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Stages a new open draft for the session with a price snapshot and {@code expiresAt = now + ttl}.
     */
    public static OrderDraft stage(String sessionId, Long customerId, List<DraftLine> items, Duration ttl, Instant now) {
        OrderDraft draft = new OrderDraft();
        draft.id = "dft-" + UUID.randomUUID();
        draft.sessionId = requireText(sessionId, "sessionId");
        draft.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        draft.createdAt = Objects.requireNonNull(now, "now must not be null");
        draft.status = DraftStatus.WAITING_CONFIRMATION;
        draft.openSessionId = draft.sessionId;
        draft.applySnapshot(items, ttl, now);
        return draft;
    }

    /**
     * Confirmation gate, called <em>before</em> the order is placed: the draft must be open and
     * within its TTL.
     *
     * @throws IllegalStateException if the draft is no longer open
     * @throws DraftExpiredException if the TTL has elapsed; the draft stays open so the caller can
     *                               {@link #expire} it
     */
    public void requireConfirmable(Instant now) {
        requireOpen("be confirmed");
        if (isPastExpiry(Objects.requireNonNull(now, "now must not be null"))) {
            throw new DraftExpiredException(id, "Order draft " + id + " expired at " + expiresAt + ".");
        }
    }

    /**
     * WAITING_CONFIRMATION → CONFIRMED, recording the order that was placed and the confirming
     * request's key. Only records the outcome: the TTL was checked by {@link #requireConfirmable}
     * before placing, and an order that exists must be recorded even if it returned after expiry.
     */
    public void confirm(String orderNumber, String idempotencyKey, Instant now) {
        transitionTo(DraftStatus.CONFIRMED, now);
        this.confirmedOrderNumber = requireText(orderNumber, "orderNumber");
        this.idempotencyKey = idempotencyKey;
    }

    /** WAITING_CONFIRMATION → CANCELLED. */
    public void cancel(Instant now) {
        transitionTo(DraftStatus.CANCELLED, now);
    }

    /** WAITING_CONFIRMATION → EXPIRED. Only legal once the TTL has actually elapsed. */
    public void expire(Instant now) {
        if (!isPastExpiry(now)) {
            throw new IllegalStateException("Draft " + id + " cannot expire before " + expiresAt + ".");
        }
        transitionTo(DraftStatus.EXPIRED, now);
    }

    /** WAITING_CONFIRMATION → INVALIDATED (Order Management rejected the snapshot). */
    public void invalidate(Instant now) {
        transitionTo(DraftStatus.INVALIDATED, now);
    }

    public boolean isOpen() {
        return status.isOpen();
    }

    public boolean isPastExpiry(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean belongsTo(String sessionId) {
        return this.sessionId.equals(sessionId);
    }

    private void applySnapshot(List<DraftLine> items, Duration ttl, Instant now) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order draft needs at least one line.");
        }
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(now, "now must not be null");
        this.items = List.copyOf(items);
        this.totalAmount = items.stream().map(DraftLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        this.expiresAt = now.plus(ttl);
        this.updatedAt = now;
    }

    private void transitionTo(DraftStatus target, Instant now) {
        requireOpen("move to " + target);
        this.status = target;
        this.openSessionId = null;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private void requireOpen(String action) {
        if (!isOpen()) {
            throw new IllegalStateException("Draft " + id + " is " + status + " and cannot " + action + ".");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
