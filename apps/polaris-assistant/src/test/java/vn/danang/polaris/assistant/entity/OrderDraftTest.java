package vn.danang.polaris.assistant.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import vn.danang.polaris.web.exception.DraftExpiredException;

/**
 * Unit tests for the {@link OrderDraft} state machine:
 * WAITING_CONFIRMATION → CONFIRMED | CANCELLED | EXPIRED | INVALIDATED, each exactly once.
 */
class OrderDraftTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant AFTER_TTL = NOW.plus(OrderDraft.DEFAULT_TTL);
    private static final List<DraftLine> LINES = List.of(
            DraftLine.of("NG-CHARGER-01", "Fast Charger 65W", 2, new BigDecimal("24.90")));

    private static OrderDraft staged() {
        return OrderDraft.stage("sess-1", 7L, LINES, OrderDraft.DEFAULT_TTL, NOW);
    }

    // =========================================================================
    // 1. Happy path — staging and each legal transition
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid lines, when staged, then the draft is open with total, 15-minute expiry and a dft- id")
        void stages_open_draft_with_snapshot() {
            OrderDraft draft = staged();

            assertThat(draft.getId()).startsWith("dft-");
            assertThat(draft.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            assertThat(draft.isOpen()).isTrue();
            assertThat(draft.getTotalAmount()).isEqualByComparingTo("49.80");
            assertThat(draft.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
            assertThat(draft.belongsTo("sess-1")).isTrue();
        }

        @Test
        @DisplayName("Given an open draft, when confirmed, then it records the order number and idempotency key")
        void confirms_open_draft() {
            OrderDraft draft = staged();

            draft.confirm("ORD-000042", "idem-1", NOW.plusSeconds(60));

            assertThat(draft.getStatus()).isEqualTo(DraftStatus.CONFIRMED);
            assertThat(draft.getConfirmedOrderNumber()).isEqualTo("ORD-000042");
            assertThat(draft.getIdempotencyKey()).isEqualTo("idem-1");
            assertThat(draft.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(draft.isOpen()).isFalse();
        }

        @Test
        @DisplayName("Given an open draft, when cancelled or invalidated, then it moves to that terminal state")
        void cancels_and_invalidates_open_draft() {
            OrderDraft cancelled = staged();
            cancelled.cancel(NOW);
            OrderDraft invalidated = staged();
            invalidated.invalidate(NOW);

            assertThat(cancelled.getStatus()).isEqualTo(DraftStatus.CANCELLED);
            assertThat(invalidated.getStatus()).isEqualTo(DraftStatus.INVALIDATED);
        }

        @Test
        @DisplayName("Given an open draft past its TTL, when expired, then it moves to EXPIRED")
        void expires_open_draft_after_ttl() {
            OrderDraft draft = staged();

            assertThat(draft.isPastExpiry(AFTER_TTL)).isTrue();
            draft.expire(AFTER_TTL);

            assertThat(draft.getStatus()).isEqualTo(DraftStatus.EXPIRED);
        }
    }

    // =========================================================================
    // 2. Invalid input & illegal transitions
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        static Stream<Arguments> terminalDrafts() {
            return Stream.of(
                    Arguments.of("CONFIRMED", (Consumer<OrderDraft>) d -> d.confirm("ORD-1", null, NOW)),
                    Arguments.of("CANCELLED", (Consumer<OrderDraft>) d -> d.cancel(NOW)),
                    Arguments.of("EXPIRED", (Consumer<OrderDraft>) d -> d.expire(AFTER_TTL)),
                    Arguments.of("INVALIDATED", (Consumer<OrderDraft>) d -> d.invalidate(NOW)));
        }

        @ParameterizedTest(name = "{0} draft rejects every further transition")
        @MethodSource("terminalDrafts")
        @DisplayName("Given a draft in a terminal state, when any transition is attempted, then IllegalStateException")
        void terminal_states_reject_further_transitions(String state, Consumer<OrderDraft> toTerminal) {
            OrderDraft draft = staged();
            toTerminal.accept(draft);
            assertThat(draft.getStatus().name()).isEqualTo(state);

            assertThatThrownBy(() -> draft.confirm("ORD-2", null, AFTER_TTL)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> draft.cancel(AFTER_TTL)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> draft.expire(AFTER_TTL)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> draft.invalidate(AFTER_TTL)).isInstanceOf(IllegalStateException.class);
            assertThat(draft.getStatus().name()).isEqualTo(state);
        }

        @Test
        @DisplayName("Given an open draft past its TTL, when the confirm gate is checked, then DraftExpiredException and it stays open so it can be expired")
        void gate_rejects_after_ttl() {
            OrderDraft draft = staged();

            assertThatThrownBy(() -> draft.requireConfirmable(AFTER_TTL))
                    .isInstanceOf(DraftExpiredException.class)
                    .extracting(e -> ((DraftExpiredException) e).getDraftId()).isEqualTo(draft.getId());
            assertThat(draft.isOpen()).isTrue();

            draft.expire(AFTER_TTL);
            assertThat(draft.getStatus()).isEqualTo(DraftStatus.EXPIRED);
        }

        @Test
        @DisplayName("Given a draft that is no longer open, when the confirm gate is checked, then IllegalStateException")
        void gate_rejects_non_open_draft() {
            OrderDraft draft = staged();
            draft.cancel(NOW);

            assertThatThrownBy(() -> draft.requireConfirmable(NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Given an open draft still within its TTL, when expired, then IllegalStateException and it stays open")
        void cannot_expire_before_ttl() {
            OrderDraft draft = staged();

            assertThatThrownBy(() -> draft.expire(NOW.plusSeconds(899))).isInstanceOf(IllegalStateException.class);
            assertThat(draft.isOpen()).isTrue();
        }

        @Test
        @DisplayName("Given no lines, when staged, then IllegalArgumentException")
        void rejects_empty_lines() {
            assertThatThrownBy(() -> OrderDraft.stage("sess-1", 7L, List.of(), OrderDraft.DEFAULT_TTL, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Given a non-positive quantity or blank sku, when a line is built, then IllegalArgumentException")
        void rejects_invalid_lines() {
            assertThatThrownBy(() -> DraftLine.of("NG-1", "x", 0, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DraftLine.of(" ", "x", 1, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Given a blank order number, when confirmed, then IllegalArgumentException")
        void rejects_blank_order_number() {
            assertThatThrownBy(() -> staged().confirm(" ", null, NOW)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // 3. Edge cases
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given one millisecond before expiry, when the confirm gate is checked, then it passes")
        void gate_passes_just_before_expiry() {
            OrderDraft draft = staged();

            draft.requireConfirmable(AFTER_TTL.minusMillis(1));

            assertThat(draft.isOpen()).isTrue();
        }

        @Test
        @DisplayName("Given the gate passed in time but the placed order returned after the TTL, when confirmed, then the order is still recorded")
        void confirm_records_order_placed_across_the_ttl_boundary() {
            OrderDraft draft = staged();
            draft.requireConfirmable(AFTER_TTL.minusSeconds(1));

            draft.confirm("ORD-000042", "idem-1", AFTER_TTL.plusSeconds(5));

            assertThat(draft.getStatus()).isEqualTo(DraftStatus.CONFIRMED);
            assertThat(draft.getConfirmedOrderNumber()).isEqualTo("ORD-000042");
        }

        @Test
        @DisplayName("Given the exact expiry instant, when checked, then the draft counts as expired")
        void expiry_boundary_is_inclusive() {
            OrderDraft draft = staged();

            assertThat(draft.isPastExpiry(AFTER_TTL.minusMillis(1))).isFalse();
            assertThat(draft.isPastExpiry(AFTER_TTL)).isTrue();
        }

        @Test
        @DisplayName("Given a line without an explicit total, when built, then lineTotal = unitPrice x quantity")
        void derives_line_total() {
            assertThat(DraftLine.of("NG-1", "x", 3, new BigDecimal("1.10")).lineTotal()).isEqualByComparingTo("3.30");
        }
    }
}
