package vn.danang.polaris.assistant.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.OrderConfirmedCard;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics.DraftOutcome;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.web.exception.DraftProblemException;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.web.exception.DraftExpiredException;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.ProductInactiveException;

/**
 * The confirmation gate (BPMN {@code A_Gate} → {@code A_Execute} → {@code A_ConfirmCard}, or
 * {@code A_EndDeclined}): the only path that turns a draft into an order. It runs on the shopper's explicit
 * "Submit Order" click, never on the model's judgment, and calls {@code place_order} itself.
 * <p>
 * Transaction boundaries. Confirming is three steps, so no database transaction or row lock is held while
 * Order Management places the order (an MCP round trip of up to the configured timeout):
 * <ol>
 *   <li><b>Gate</b> (short transaction): lock the session ({@code SELECT ... FOR UPDATE}, the lock order
 *       shared with every draft writer and {@code SessionStore#append}), check the owner, load the draft and
 *       decide. A confirmed draft replays its order; a cancelled or invalidated one is refused; a lapsed one
 *       is moved to {@code EXPIRED}, committed, and only then refused with {@link DraftExpiredException}, so
 *       the expiry is never rolled back.</li>
 *   <li><b>Execute</b> (no transaction): {@code place_order} with the snapshot's {@code expected_unit_price}
 *       per line and {@code idempotency_key = draftId}.</li>
 *   <li><b>Record</b> (short transaction, same lock order): {@code CONFIRMED} with the order number, or
 *       {@code INVALIDATED}; then append an assistant message so the model knows on its next turn.</li>
 * </ol>
 * Exactly one order per draft does not rely on the gate's lock (it is released during step 2): every
 * confirmation of a draft, whatever its {@code Idempotency-Key} header, sends the same key (the draft id),
 * and Order Management's idempotency, bound to the customer, returns the original order to every later or
 * concurrent caller. Step 3 then records the first result and replays it for the others.
 */
@Service
public class OrderDraftConfirmationService {

    static final String PLACE_ORDER_TOOL = "place_order";

    private static final Logger log = LoggerFactory.getLogger(OrderDraftConfirmationService.class);

    private final AssistantSessionRepository sessionRepository;
    private final OrderDraftRepository draftRepository;
    private final SessionStore sessionStore;
    private final PolarisMcpClient polarisMcpClient;
    private final CurrentCustomerClient currentCustomerClient;
    private final UserContext userContext;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final AssistantOutcomeMetrics outcomeMetrics;
    private final Clock clock;

    @Autowired
    public OrderDraftConfirmationService(
            AssistantSessionRepository sessionRepository,
            OrderDraftRepository draftRepository,
            SessionStore sessionStore,
            PolarisMcpClient polarisMcpClient,
            CurrentCustomerClient currentCustomerClient,
            UserContext userContext,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            AssistantOutcomeMetrics outcomeMetrics) {
        this(sessionRepository, draftRepository, sessionStore, polarisMcpClient, currentCustomerClient, userContext,
                objectMapper, transactionManager, outcomeMetrics, Clock.systemUTC());
    }

    OrderDraftConfirmationService(
            AssistantSessionRepository sessionRepository,
            OrderDraftRepository draftRepository,
            SessionStore sessionStore,
            PolarisMcpClient polarisMcpClient,
            CurrentCustomerClient currentCustomerClient,
            UserContext userContext,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            AssistantOutcomeMetrics outcomeMetrics,
            Clock clock) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.polarisMcpClient = Objects.requireNonNull(polarisMcpClient, "polarisMcpClient must not be null");
        this.currentCustomerClient = Objects.requireNonNull(currentCustomerClient, "currentCustomerClient must not be null");
        this.userContext = Objects.requireNonNull(userContext, "userContext must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.outcomeMetrics = Objects.requireNonNull(outcomeMetrics, "outcomeMetrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Result of a confirmation.
     *
     * @param orderNumber the order placed from the draft
     * @param created     true if this request recorded the confirmation (201); false for a replay (200)
     * @param response    the assistant's message with the {@code ORDER_CONFIRMED} card
     */
    public record Confirmation(String orderNumber, boolean created, ChatMessageResponse response) {}

    /**
     * Confirms the draft: places its order once and records it.
     *
     * @param userId         the signed-in caller; must own the session
     * @param idempotencyKey the confirming request's {@code Idempotency-Key}, recorded on the draft
     * @throws SessionAccessDeniedException if the session belongs to another user (403)
     * @throws DraftExpiredException        if the TTL elapsed; the draft is {@code EXPIRED} (409)
     * @throws DraftProblemException        for every other refusal, including Order Management's problems
     */
    @CustomNextSpan(
            name = "assistant.order_draft.confirm",
            tags = {
                    @SpanTag(key = "agent.session_id", expression = "#sessionId"),
                    @SpanTag(key = "assistant.draft.id", expression = "#draftId")
            },
            resultTags = {
                    @SpanTag(key = "assistant.order.number", expression = "#result?.orderNumber()"),
                    @SpanTag(key = "assistant.draft.confirm.replayed", expression = "#result != null && !#result.created()")
            }
    )
    public Confirmation confirm(String sessionId, String userId, String draftId, String idempotencyKey) {
        String callerToken = requireSignedIn(userId);

        // 1. Gate
        Gate gate = inTransaction(() -> openGate(sessionId, userId, draftId));
        if (gate.expired() != null) {
            log.info("Refused confirmation of expired order draft draftId={} sessionId={} expiresAt={}",
                    draftId, sessionId, gate.draft().getExpiresAt());
            throw gate.expired();
        }
        if (gate.draft().getConfirmedOrderNumber() != null) {
            log.info("Replayed confirmed order draft draftId={} sessionId={} orderNumber={}",
                    draftId, sessionId, gate.draft().getConfirmedOrderNumber());
            return replay(gate.draft(), gate.draft().getConfirmedOrderNumber(), callerToken);
        }

        // 2. Execute, outside any transaction
        OrderDraft draft = gate.draft();
        PlaceOrderOutcome outcome = placeOrder(draft);

        // 3. Record
        return switch (outcome) {
            case PlaceOrderOutcome.Placed placed -> recordPlaced(draft, userId, placed, idempotencyKey, callerToken);
            case PlaceOrderOutcome.Rejected rejected -> throw recordRejected(draft, userId, rejected);
            case PlaceOrderOutcome.Failed failed -> {
                log.error("Order placement failed for draft draftId={} sessionId={} reason={}", draftId, sessionId, failed.reason());
                throw DraftProblemException.placementFailed(draftId,
                        "Order Management did not confirm the order for draft " + draftId + ". The draft is still open.");
            }
        };
    }

    /**
     * Cancels the draft (the shopper declined): {@code CANCELLED}, or {@code EXPIRED} if its TTL had already
     * elapsed. Idempotent: a draft that can no longer become an order is returned unchanged.
     *
     * @throws SessionAccessDeniedException if the session belongs to another user (403)
     * @throws DraftProblemException        if the draft doesn't exist in the session, or was already confirmed
     */
    @CustomNextSpan(
            name = "assistant.order_draft.cancel",
            tags = {
                    @SpanTag(key = "agent.session_id", expression = "#sessionId"),
                    @SpanTag(key = "assistant.draft.id", expression = "#draftId")
            },
            resultTags = {
                    @SpanTag(key = "assistant.draft.status", expression = "#result?.status?.name()")
            }
    )
    public OrderDraft cancel(String sessionId, String userId, String draftId) {
        requireSignedIn(userId);
        OrderDraft draft = inTransaction(() -> {
            Instant now = clock.instant();
            OrderDraft current = lockOwnedDraft(sessionId, userId, draftId);
            switch (current.getStatus()) {
                case CONFIRMED -> throw DraftProblemException.draftAlreadyConfirmed(draftId, current.getConfirmedOrderNumber());
                case WAITING_CONFIRMATION -> {
                    if (current.isPastExpiry(now)) {
                        current.expire(now);
                        outcomeMetrics.draftClosed(current, DraftOutcome.EXPIRED, now);
                        return draftRepository.saveAndFlush(current);
                    }
                    current.cancel(now);
                    outcomeMetrics.draftClosed(current, DraftOutcome.CANCELLED, now);
                    OrderDraft cancelled = draftRepository.saveAndFlush(current);
                    appendNote(sessionId, userId, "The shopper cancelled order draft " + draftId
                            + " with the Cancel button. No order was placed. Stage a new draft if they want to order.", null);
                    return cancelled;
                }
                default -> {
                    return current;
                }
            }
        });
        log.info("Cancel requested for order draft draftId={} sessionId={} status={}", draftId, sessionId, draft.getStatus());
        return draft;
    }

    // ---------------------------------------------------------------- gate

    /** The gate's decision: the draft, and the expiry to raise once the EXPIRED status is committed. */
    private record Gate(OrderDraft draft, @Nullable DraftExpiredException expired) {}

    private Gate openGate(String sessionId, String userId, String draftId) {
        Instant now = clock.instant();
        OrderDraft draft = lockOwnedDraft(sessionId, userId, draftId);
        switch (draft.getStatus()) {
            case CONFIRMED -> {
                return new Gate(draft, null);
            }
            case CANCELLED -> throw DraftProblemException.draftCancelled(draftId);
            case INVALIDATED -> throw DraftProblemException.draftInvalidated(draftId);
            case EXPIRED -> throw expiredException(draft);
            case WAITING_CONFIRMATION -> {
                try {
                    draft.requireConfirmable(now);
                    return new Gate(draft, null);
                } catch (DraftExpiredException expired) {
                    draft.expire(now);
                    outcomeMetrics.draftClosed(draft, DraftOutcome.EXPIRED, now);
                    OrderDraft persisted = draftRepository.saveAndFlush(draft);
                    appendNote(sessionId, userId, "Order draft " + draftId + " expired before the shopper confirmed it (15-minute price hold). "
                            + "No order was placed. Offer to stage it again at the current prices and stock.", null);
                    return new Gate(persisted, expired);
                }
            }
            default -> throw new IllegalStateException("Unhandled draft status " + draft.getStatus());
        }
    }

    // ---------------------------------------------------------------- execute

    private PlaceOrderOutcome placeOrder(OrderDraft draft) {
        List<Map<String, Object>> items = draft.getItems().stream()
                .map(line -> Map.<String, Object>of(
                        "sku", line.sku(),
                        "quantity", line.quantity(),
                        "expected_unit_price", line.unitPrice()))
                .toList();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("items", items);
        arguments.put("idempotency_key", draft.getId());
        if (userContext.isStaff()) {
            // Shoppers are resolved by Order Management from their own token; only staff name the customer.
            arguments.put("customer_id", draft.getCustomerId());
        }
        try {
            log.info("Placing order for draft draftId={} sessionId={} customerId={} lines={} total={}",
                    draft.getId(), draft.getSessionId(), draft.getCustomerId(), items.size(), draft.getTotalAmount());
            CallToolResult result = polarisMcpClient.callTool(PLACE_ORDER_TOOL, arguments);
            return PlaceOrderOutcome.from(result);
        } catch (RuntimeException e) {
            return new PlaceOrderOutcome.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- record

    private Confirmation recordPlaced(OrderDraft draft, String userId, PlaceOrderOutcome.Placed placed,
                                      String idempotencyKey, String callerToken) {
        String sessionId = draft.getSessionId();
        String draftId = draft.getId();
        // best-effort lookup over HTTP, done before the transaction
        OrderConfirmedCard card = OrderConfirmedCard.from(draft, placed.orderNumber(), customerName(draft.getCustomerId(), callerToken));

        Confirmation confirmation = inTransaction(() -> {
            Instant now = clock.instant();
            OrderDraft current = lockOwnedDraft(sessionId, userId, draftId);
            if (current.getConfirmedOrderNumber() != null) {
                // a concurrent confirmation of this draft recorded first; Order Management returned it the same order
                if (!current.getConfirmedOrderNumber().equals(placed.orderNumber())) {
                    log.error("Order draft draftId={} is confirmed as {} but place_order returned {}",
                            draftId, current.getConfirmedOrderNumber(), placed.orderNumber());
                }
                return null;
            }
            if (current.isOpen()) {
                current.confirm(placed.orderNumber(), idempotencyKey, now);
                draftRepository.saveAndFlush(current);
                outcomeMetrics.draftClosed(current, DraftOutcome.PLACED, now);
            } else {
                // Cancelled or superseded while the order was being placed. The order exists, so report it.
                log.warn("Order placed for a draft that left WAITING_CONFIRMATION meanwhile draftId={} status={} orderNumber={}",
                        draftId, current.getStatus(), placed.orderNumber());
            }
            AssistantMessage note = appendNote(sessionId, userId, describePlaced(card), card.toWidget());
            return new Confirmation(placed.orderNumber(), true, response(sessionId, note.getContent(), now, card));
        });
        if (confirmation == null) {
            OrderDraft recorded = draftRepository.findByIdAndSessionId(draftId, sessionId).orElseThrow();
            return replay(recorded, recorded.getConfirmedOrderNumber(), callerToken);
        }
        log.info("Confirmed order draft draftId={} sessionId={} orderNumber={} replayedByOrderManagement={}",
                draftId, sessionId, placed.orderNumber(), placed.replayed());
        return confirmation;
    }

    private DraftProblemException recordRejected(OrderDraft draft, String userId, PlaceOrderOutcome.Rejected rejected) {
        String sessionId = draft.getSessionId();
        String draftId = draft.getId();
        if (rejected.invalidatesDraft()) {
            inTransaction(() -> {
                OrderDraft current = lockOwnedDraft(sessionId, userId, draftId);
                if (current.isOpen()) {
                    Instant now = clock.instant();
                    current.invalidate(now);
                    draftRepository.saveAndFlush(current);
                    outcomeMetrics.draftClosed(current, DraftOutcome.INVALIDATED, rejected.type(), now);
                    appendNote(sessionId, userId, "Order Management rejected order draft " + draftId + " at confirmation ("
                            + rejected.type() + "): " + rejected.problem().get("detail")
                            + " No order was placed. Offer to stage a refreshed draft.", null);
                }
                return null;
            });
            log.info("Invalidated order draft draftId={} sessionId={} problemType={}", draftId, sessionId, rejected.type());
            ProblemDetail problem = rejected.toProblemDetail();
            problem.setProperty("draftId", draftId);
            problem.setProperty("draftStatus", "INVALIDATED");
            addRemedy(problem, rejected);
            return new DraftProblemException(problem);
        }
        int status = rejected.toProblemDetail().getStatus();
        log.warn("Order placement refused for draft draftId={} sessionId={} problemType={} status={}",
                draftId, sessionId, rejected.type(), status);
        if (status == HttpStatus.FORBIDDEN.value() || status == HttpStatus.NOT_FOUND.value()
                || status == HttpStatus.UNPROCESSABLE_CONTENT.value()) {
            // The caller's own problem (no permission, no linked customer, key bound to another customer): pass through.
            ProblemDetail problem = rejected.toProblemDetail();
            problem.setProperty("draftId", draftId);
            return new DraftProblemException(problem);
        }
        // validation / internal errors: our request or Order Management is at fault, not the shopper
        return DraftProblemException.placementFailed(draftId,
                "Order Management could not place the order for draft " + draftId + ". The draft is still open.");
    }

    private static void addRemedy(ProblemDetail problem, PlaceOrderOutcome.Rejected rejected) {
        Map<String, Object> properties = problem.getProperties() != null ? problem.getProperties() : Map.of();
        if (properties.containsKey("actions")) {
            return;
        }
        if (InsufficientStockException.TYPE.equals(rejected.type())) {
            problem.setProperty("remedy", "The item sold out or has too little stock. Nothing was charged; choose an alternative or a smaller quantity.");
            problem.setProperty("actions", List.of(
                    Map.of("label", "Search Alternatives", "action", "search_alternatives", "query", String.valueOf(rejected.problem().get("sku"))),
                    Map.of("label", "Refresh Draft", "action", "refresh_draft")));
        } else if (ProductInactiveException.TYPE.equals(rejected.type())) {
            problem.setProperty("remedy", "Remove the listed products or choose alternatives. Nothing was charged and no stock was taken.");
            problem.setProperty("actions", List.of(Map.of("label", "Search Alternatives", "action", "search_alternatives")));
        } else {
            problem.setProperty("remedy", "Review the current prices and confirm again. Nothing was charged and no stock was taken.");
            problem.setProperty("actions", List.of(Map.of("label", "Refresh Draft", "action", "refresh_draft")));
        }
    }

    private Confirmation replay(OrderDraft draft, String orderNumber, String callerToken) {
        OrderConfirmedCard card = OrderConfirmedCard.from(draft, orderNumber, customerName(draft.getCustomerId(), callerToken));
        return new Confirmation(orderNumber, false, response(draft.getSessionId(), describePlaced(card), draft.getUpdatedAt(), card));
    }

    // ---------------------------------------------------------------- helpers

    private String requireSignedIn(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            throw DraftProblemException.unauthenticated();
        }
        return userContext.resolveCallerBearerToken().orElseThrow(DraftProblemException::unauthenticated);
    }

    /** Session lock first (owner check), then the draft, scoped to the session so another session's id never resolves. */
    private OrderDraft lockOwnedDraft(String sessionId, String userId, String draftId) {
        AssistantSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> DraftProblemException.draftNotFound(draftId));
        if (!session.isOwnedBy(userId)) {
            log.warn("Denied order draft action on assistant session sessionId={} userId={} draftId={}", sessionId, userId, draftId);
            throw new SessionAccessDeniedException(sessionId);
        }
        return draftRepository.findByIdAndSessionId(draftId, sessionId)
                .orElseThrow(() -> DraftProblemException.draftNotFound(draftId));
    }

    private static DraftExpiredException expiredException(OrderDraft draft) {
        return new DraftExpiredException(draft.getId(), "Order draft " + draft.getId() + " expired at " + draft.getExpiresAt() + ".");
    }

    /** Joins the caller's transaction (the session is already locked by it), so the lock order stays session → drafts → messages. */
    private AssistantMessage appendNote(String sessionId, String userId, String content, @Nullable ChatWidget widget) {
        AssistantMessage note = AssistantMessage.of(content, MessageRole.ASSISTANT, null);
        note.setCreatedAt(clock.instant());
        if (widget != null) {
            try {
                note.setWidgetPayload(objectMapper.writeValueAsString(List.of(widget)));
                note.setWidgetType(widget.type());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize widget type={} error={}", widget.type(), e.getMessage());
            }
        }
        sessionStore.append(sessionId, userId, List.of(note));
        return note;
    }

    private String customerName(Long customerId, String callerToken) {
        try {
            if (userContext.isStaff()) {
                return currentCustomerClient.findCustomerName(customerId, callerToken).orElse(null);
            }
            return currentCustomerClient.findCurrentCustomer(callerToken)
                    .filter(customer -> customer.id().equals(customerId))
                    .map(CustomerRef::fullName)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.info("Customer name unavailable for the confirmed-order card customerId={} error={}", customerId, e.getClass().getSimpleName());
            return null;
        }
    }

    private static ChatMessageResponse response(String sessionId, String reply, Instant createdAt, OrderConfirmedCard card) {
        return new ChatMessageResponse(sessionId, MessageRole.ASSISTANT.name(), reply, createdAt, List.of(card.toWidget()));
    }

    private static String describePlaced(OrderConfirmedCard card) {
        String lines = card.items().stream()
                .map(line -> "  * [%s] %s x %d @ $%s".formatted(line.sku(), line.name(), line.quantity(), line.unitPrice()))
                .collect(Collectors.joining("\n"));
        String customer = card.customerName() != null
                ? card.customerName() + " (customer " + card.customerId() + ")"
                : "customer " + card.customerId();
        return "Order " + card.orderNumber() + " was placed for " + customer + " after the shopper clicked 'Submit Order' on draft "
                + card.draftId() + ".\n" + lines + "\n- Total: $" + card.total();
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Step 2 must run with no transaction open; never join a caller's.
            throw new IllegalStateException("OrderDraftConfirmationService must not be called inside an active transaction.");
        }
        return transaction.execute(status -> work.get());
    }
}
