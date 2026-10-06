package vn.danang.polaris.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.OrderConfirmedCard;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.web.exception.DraftProblemException;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.FakeOrderManagementMcp;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.web.exception.DraftExpiredException;

/**
 * {@link OrderDraftConfirmationService} against real PostgreSQL, with Order Management's {@code place_order}
 * faked at the MCP client boundary ({@link FakeOrderManagementMcp}): every branch of the confirmation gate and
 * of cancel, the transaction boundaries (expiry persisted although refused), and exactly one order under
 * repeated and concurrent confirmation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderDraftConfirmationServiceIntegrationTest {

    private static final Instant STAGED_AT = Instant.parse("2026-09-28T10:00:00Z");
    private static final List<DraftLine> LINES = List.of(
            DraftLine.of("NG-CHARGER-01", "Nova 65W Fast Charger", 2, new BigDecimal("24.90")));

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @MockitoBean
    private CurrentCustomerClient currentCustomerClient;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private AssistantMessageRepository messageRepository;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private UserContext userContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private FakeOrderManagementMcp orderManagement;

    @BeforeEach
    void setUp() {
        orderManagement = new FakeOrderManagementMcp();
        signIn("alice", false);
        when(currentCustomerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(new CustomerRef(7L, "Alice Tran")));
        when(currentCustomerClient.findCustomerName(eq(7L), anyString())).thenReturn(Optional.of("Alice Tran"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String subject, boolean staff) {
        Jwt jwt = Jwt.withTokenValue("token-" + subject).header("alg", "none").subject(subject).build();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("PERM_order.write")));
        if (staff) {
            authorities.add(new SimpleGrantedAuthority("ROLE_STAFF"));
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OrderDraftConfirmationService serviceAt(Instant now) {
        return new OrderDraftConfirmationService(sessionRepository, draftRepository, sessionStore, orderManagement.asClient(),
                currentCustomerClient, userContext, objectMapper, transactionManager, new AssistantOutcomeMetrics(meters),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private OrderDraftConfirmationService service() {
        return serviceAt(STAGED_AT.plusSeconds(60));
    }

    private String newSession(String userId) {
        String id = "sess-" + UUID.randomUUID();
        sessionRepository.saveAndFlush(AssistantSession.open(id, userId, STAGED_AT));
        return id;
    }

    private OrderDraft stagedDraft(String sessionId) {
        return draftRepository.saveAndFlush(OrderDraft.stage(sessionId, 7L, LINES, OrderDraft.DEFAULT_TTL, STAGED_AT));
    }

    private DraftStatus statusOf(OrderDraft draft) {
        return draftRepository.findById(draft.getId()).orElseThrow().getStatus();
    }

    private List<AssistantMessage> messagesOf(String sessionId) {
        return messageRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    private static ProblemDetail problemOf(Throwable thrown) {
        return ((DraftProblemException) thrown).getProblem();
    }

    @Nested
    @DisplayName("1. Confirm — happy path")
    class HappyPath {

        @Test
        @DisplayName("Given an open draft, when a shopper confirms, then one order is placed from the snapshot with key = draftId, the draft is CONFIRMED and the model is told")
        void confirms_and_records_order() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            OrderDraftConfirmationService.Confirmation confirmation = service().confirm(sessionId, "alice", draft.getId(), "click-1");

            assertThat(confirmation.created()).isTrue();
            assertThat(confirmation.orderNumber()).isEqualTo(orderManagement.orderNumberFor(draft.getId()));
            // place_order from the orchestrator: snapshot prices, key = draftId, no customer for a shopper
            assertThat(orderManagement.placeOrderCalls()).singleElement().satisfies(args -> {
                assertThat(args).containsEntry("idempotency_key", draft.getId()).doesNotContainKey("customer_id");
                assertThat(args.get("items")).isEqualTo(List.of(Map.of(
                        "sku", "NG-CHARGER-01", "quantity", 2, "expected_unit_price", new BigDecimal("24.90"))));
            });
            OrderDraft stored = draftRepository.findById(draft.getId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(DraftStatus.CONFIRMED);
            assertThat(stored.getConfirmedOrderNumber()).isEqualTo(confirmation.orderNumber());
            assertThat(stored.getIdempotencyKey()).isEqualTo("click-1");
            // ORDER_CONFIRMED card
            ChatWidget widget = confirmation.response().widgets().getFirst();
            assertThat(widget.type()).isEqualTo(ChatWidget.ORDER_CONFIRMED);
            OrderConfirmedCard card = (OrderConfirmedCard) widget.payload();
            assertThat(card.orderNumber()).isEqualTo(confirmation.orderNumber());
            assertThat(card.draftId()).isEqualTo(draft.getId());
            assertThat(card.customerId()).isEqualTo(7L);
            assertThat(card.customerName()).isEqualTo("Alice Tran");
            assertThat(card.total()).isEqualByComparingTo("49.80");
            assertThat(card.items()).containsExactlyElementsOf(LINES);
            // the session learns about it
            AssistantMessage note = messagesOf(sessionId).getLast();
            assertThat(note.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(note.getContent()).contains(confirmation.orderNumber()).contains(draft.getId());
            assertThat(note.getWidgetType()).isEqualTo(ChatWidget.ORDER_CONFIRMED);
            assertThat(note.getWidgetPayload()).contains(confirmation.orderNumber());
        }

        @Test
        @DisplayName("Given a staff caller, when confirming, then place_order names the draft's customer")
        void staff_passes_the_drafts_customer() {
            signIn("sam", true);
            String sessionId = newSession("sam");
            OrderDraft draft = stagedDraft(sessionId);

            OrderDraftConfirmationService.Confirmation confirmation = service().confirm(sessionId, "sam", draft.getId(), "click-1");

            assertThat(orderManagement.placeOrderCalls().getFirst()).containsEntry("customer_id", 7L);
            assertThat(((OrderConfirmedCard) confirmation.response().widgets().getFirst().payload()).customerName()).isEqualTo("Alice Tran");
        }

        @Test
        @DisplayName("Given Order Management answers with its replay text (a prior attempt was placed), then it is recorded as success")
        void replay_text_from_order_management_is_success() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            // a first attempt placed the order but its answer was lost
            orderManagement.placeOrder(Map.of("idempotency_key", draft.getId()));

            OrderDraftConfirmationService.Confirmation confirmation = service().confirm(sessionId, "alice", draft.getId(), "click-2");

            assertThat(confirmation.created()).isTrue();
            assertThat(orderManagement.ordersCreated()).isEqualTo(1);
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.CONFIRMED);
        }

        @Test
        @DisplayName("Given the customer name lookup fails, then the order is still confirmed and the card has no name")
        void name_lookup_is_best_effort() {
            when(currentCustomerClient.findCurrentCustomer(anyString())).thenThrow(new vn.danang.polaris.assistant.customer.CustomerLookupException("down"));
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            OrderDraftConfirmationService.Confirmation confirmation = service().confirm(sessionId, "alice", draft.getId(), "click-1");

            assertThat(((OrderConfirmedCard) confirmation.response().widgets().getFirst().payload()).customerName()).isNull();
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.CONFIRMED);
        }
    }

    @Nested
    @DisplayName("2. Confirm — idempotency and concurrency")
    class Idempotency {

        @Test
        @DisplayName("Given a confirmed draft, when confirmed again with the same or another Idempotency-Key, then the stored order is replayed and nothing is placed")
        void repeated_confirm_replays() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            String orderNumber = service().confirm(sessionId, "alice", draft.getId(), "click-1").orderNumber();
            int messages = messagesOf(sessionId).size();

            for (String key : List.of("click-1", "click-2")) {
                OrderDraftConfirmationService.Confirmation again = service().confirm(sessionId, "alice", draft.getId(), key);
                assertThat(again.created()).isFalse();
                assertThat(again.orderNumber()).isEqualTo(orderNumber);
                assertThat(again.response().widgets()).extracting(ChatWidget::type).containsExactly(ChatWidget.ORDER_CONFIRMED);
            }

            assertThat(orderManagement.placeOrderCalls()).hasSize(1);
            assertThat(orderManagement.ordersCreated()).isEqualTo(1);
            assertThat(messagesOf(sessionId)).hasSize(messages);
        }

        @Test
        @DisplayName("Given two concurrent confirms with the same Idempotency-Key, then exactly one order: one 201, one replay")
        void concurrent_confirms_same_key_place_one_order() throws Exception {
            assertConcurrentConfirmsPlaceOneOrder("click-1", "click-1");
        }

        @Test
        @DisplayName("Given two concurrent confirms with different Idempotency-Keys, then exactly one order: one 201, one replay")
        void concurrent_confirms_different_keys_place_one_order() throws Exception {
            assertConcurrentConfirmsPlaceOneOrder("click-1", "click-2");
        }

        private void assertConcurrentConfirmsPlaceOneOrder(String key1, String key2) throws Exception {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            // both requests pass the gate and are inside place_order at the same time
            orderManagement.holdConcurrentCalls(2);
            OrderDraftConfirmationService service = service();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<OrderDraftConfirmationService.Confirmation>> futures = new ArrayList<>();
                for (String key : List.of(key1, key2)) {
                    Callable<OrderDraftConfirmationService.Confirmation> confirm = () -> {
                        signIn("alice", false);
                        try {
                            return service.confirm(sessionId, "alice", draft.getId(), key);
                        } finally {
                            SecurityContextHolder.clearContext();
                        }
                    };
                    futures.add(pool.submit(confirm));
                }
                List<OrderDraftConfirmationService.Confirmation> results = new ArrayList<>();
                for (Future<OrderDraftConfirmationService.Confirmation> future : futures) {
                    results.add(future.get());
                }

                assertThat(orderManagement.placeOrderCalls()).hasSize(2)
                        .allSatisfy(args -> assertThat(args).containsEntry("idempotency_key", draft.getId()));
                assertThat(orderManagement.ordersCreated()).isEqualTo(1);
                assertThat(results).extracting(OrderDraftConfirmationService.Confirmation::orderNumber).containsOnly(
                        orderManagement.orderNumberFor(draft.getId()));
                assertThat(results).filteredOn(OrderDraftConfirmationService.Confirmation::created).hasSize(1);
                OrderDraft stored = draftRepository.findById(draft.getId()).orElseThrow();
                assertThat(stored.getStatus()).isEqualTo(DraftStatus.CONFIRMED);
                assertThat(stored.getConfirmedOrderNumber()).isEqualTo(orderManagement.orderNumberFor(draft.getId()));
                assertThat(messagesOf(sessionId)).filteredOn(m -> ChatWidget.ORDER_CONFIRMED.equals(m.getWidgetType())).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("3. Confirm — the gate refuses")
    class Gate {

        @Test
        @DisplayName("Given a draft staged 20 minutes ago, when confirmed, then 409 draft-expired, EXPIRED is persisted and nothing is placed")
        void expired_draft_is_persisted_as_expired() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            assertThatThrownBy(() -> serviceAt(STAGED_AT.plusSeconds(20 * 60)).confirm(sessionId, "alice", draft.getId(), "click-1"))
                    .isInstanceOfSatisfying(DraftExpiredException.class, e -> assertThat(e.getDraftId()).isEqualTo(draft.getId()));

            assertThat(statusOf(draft)).isEqualTo(DraftStatus.EXPIRED);
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
            assertThat(messagesOf(sessionId).getLast().getContent()).contains("expired");
            // and stays refused
            assertThatThrownBy(() -> service().confirm(sessionId, "alice", draft.getId(), "click-2"))
                    .isInstanceOf(DraftExpiredException.class);
        }

        @Test
        @DisplayName("Given exactly 15 minutes after staging, when confirmed, then the draft has expired")
        void ttl_boundary_is_expired() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            assertThatThrownBy(() -> serviceAt(STAGED_AT.plus(OrderDraft.DEFAULT_TTL)).confirm(sessionId, "alice", draft.getId(), "k"))
                    .isInstanceOf(DraftExpiredException.class);
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.EXPIRED);
        }

        @Test
        @DisplayName("Given a cancelled draft, when confirmed, then 409 draft-cancelled and nothing is placed")
        void cancelled_draft_is_refused() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            service().cancel(sessionId, "alice", draft.getId());

            assertThatThrownBy(() -> service().confirm(sessionId, "alice", draft.getId(), "k"))
                    .isInstanceOfSatisfying(DraftProblemException.class, e -> {
                        assertThat(e.getProblem().getStatus()).isEqualTo(409);
                        assertThat(e.getProblem().getType()).hasToString(DraftProblemException.TYPE_DRAFT_CANCELLED);
                    });
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }

        @Test
        @DisplayName("Given an invalidated draft, when confirmed again, then 409 draft-invalidated and nothing is placed")
        void invalidated_draft_is_refused() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("price-changed", 409, "Price Changed", "changed", Map.of()));
            assertThatThrownBy(() -> service().confirm(sessionId, "alice", draft.getId(), "k")).isInstanceOf(DraftProblemException.class);
            orderManagement.acceptOrders();

            assertThatThrownBy(() -> service().confirm(sessionId, "alice", draft.getId(), "k2"))
                    .satisfies(e -> assertThat(problemOf(e).getType()).hasToString(DraftProblemException.TYPE_DRAFT_INVALIDATED));
            assertThat(orderManagement.placeOrderCalls()).hasSize(1);
        }

        @Test
        @DisplayName("Given another user's session (IDOR), when confirming, then 403 and nothing is placed or changed")
        void other_user_is_denied() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            signIn("bob", false);

            assertThatThrownBy(() -> service().confirm(sessionId, "bob", draft.getId(), "k"))
                    .isInstanceOf(SessionAccessDeniedException.class);
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }

        @Test
        @DisplayName("Given a draft id of another session, when confirmed in the caller's own session, then 404 draft-not-found")
        void draft_of_another_session_is_not_found() {
            OrderDraft alicesDraft = stagedDraft(newSession("alice"));
            signIn("bob", false);
            String bobsSession = newSession("bob");

            assertThatThrownBy(() -> service().confirm(bobsSession, "bob", alicesDraft.getId(), "k"))
                    .satisfies(e -> {
                        assertThat(problemOf(e).getStatus()).isEqualTo(404);
                        assertThat(problemOf(e).getType()).hasToString(DraftProblemException.TYPE_DRAFT_NOT_FOUND);
                    });
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }

        @Test
        @DisplayName("Given an unknown session, when confirming, then 404 draft-not-found")
        void unknown_session_is_not_found() {
            assertThatThrownBy(() -> service().confirm("sess-missing", "alice", "dft-x", "k"))
                    .satisfies(e -> assertThat(problemOf(e).getStatus()).isEqualTo(404));
        }

        @Test
        @DisplayName("Given an anonymous caller or no bearer token, when confirming, then 401 and nothing is placed")
        void anonymous_is_refused() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            assertThatThrownBy(() -> service().confirm(sessionId, "anonymous", draft.getId(), "k"))
                    .satisfies(e -> assertThat(problemOf(e).getStatus()).isEqualTo(401));
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> service().confirm(sessionId, "alice", draft.getId(), "k"))
                    .satisfies(e -> assertThat(problemOf(e).getStatus()).isEqualTo(401));
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. Confirm — Order Management rejects")
    class Rejections {

        private ProblemDetail confirmRejected(String sessionId, OrderDraft draft) {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service().confirm(sessionId, "alice", draft.getId(), "k"));
            assertThat(thrown).isInstanceOf(DraftProblemException.class);
            return problemOf(thrown);
        }

        @Test
        @DisplayName("Given the price changed, then the draft is INVALIDATED and 409 price-changed passes through with changed_lines")
        void price_changed_invalidates() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("price-changed", 409, "Price Changed",
                    "The price changed for 1 item: 'NG-CHARGER-01' expected 24.90, now 29.90.",
                    Map.of("changed_lines", List.of(Map.of("sku", "NG-CHARGER-01", "expected_unit_price", 24.90, "current_unit_price", 29.90)))));

            ProblemDetail problem = confirmRejected(sessionId, draft);

            assertThat(problem.getStatus()).isEqualTo(409);
            assertThat(problem.getType()).hasToString("https://polaris.local/errors/price-changed");
            assertThat(problem.getProperties()).containsKeys("changed_lines", "remedy", "actions")
                    .containsEntry("draftId", draft.getId()).containsEntry("draftStatus", "INVALIDATED");
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.INVALIDATED);
            assertThat(messagesOf(sessionId).getLast().getContent()).contains("price-changed");
        }

        @Test
        @DisplayName("Given the last unit sold meanwhile (Scenario 11), then the draft is INVALIDATED and 400 out-of-stock offers alternatives")
        void out_of_stock_invalidates() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("out-of-stock", 400, "Insufficient Stock",
                    "Insufficient stock for product: NG-CHARGER-01",
                    Map.of("sku", "NG-CHARGER-01", "requested_quantity", 2, "available_quantity", 0)));

            ProblemDetail problem = confirmRejected(sessionId, draft);

            assertThat(problem.getStatus()).isEqualTo(400);
            assertThat(problem.getType()).hasToString("https://polaris.local/errors/out-of-stock");
            assertThat(problem.getProperties()).containsEntry("available_quantity", 0).containsKey("actions");
            assertThat(problem.getProperties().get("actions").toString()).contains("search_alternatives");
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.INVALIDATED);
            assertThat(orderManagement.ordersCreated()).isZero();
        }

        @Test
        @DisplayName("Given a product became inactive, then the draft is INVALIDATED and 409 product-inactive passes through")
        void product_inactive_invalidates() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("product-inactive", 409, "Product Inactive", "inactive",
                    Map.of("inactive_skus", List.of("NG-CHARGER-01"))));

            ProblemDetail problem = confirmRejected(sessionId, draft);

            assertThat(problem.getStatus()).isEqualTo(409);
            assertThat(problem.getType()).hasToString("https://polaris.local/errors/product-inactive");
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.INVALIDATED);
        }

        @Test
        @DisplayName("Given idempotency-key-reused (422), not-found (404) or forbidden (403), then the problem passes through and the draft stays open")
        void caller_problems_pass_through_and_keep_draft_open() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            for (var type : Map.of("idempotency-key-reused", 422, "not-found", 404, "forbidden", 403).entrySet()) {
                orderManagement.rejectWith(FakeOrderManagementMcp.problem(type.getKey(), type.getValue(), "Refused", "refused", Map.of()));

                ProblemDetail problem = confirmRejected(sessionId, draft);

                assertThat(problem.getStatus()).isEqualTo(type.getValue());
                assertThat(problem.getType()).hasToString("https://polaris.local/errors/" + type.getKey());
                assertThat(statusOf(draft)).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            }
        }

        @Test
        @DisplayName("Given validation-error (400) or internal-error (500), then 502 order-placement-failed and the draft stays open")
        void upstream_failures_map_to_bad_gateway() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            for (var rejection : List.of(
                    FakeOrderManagementMcp.problem("validation-error", 400, "Validation Error", "bad", Map.of()),
                    FakeOrderManagementMcp.problem("internal-error", 500, "Internal Server Error", "boom", Map.of()))) {
                orderManagement.rejectWith(rejection);

                ProblemDetail problem = confirmRejected(sessionId, draft);

                assertThat(problem.getStatus()).isEqualTo(502);
                assertThat(problem.getType()).hasToString(DraftProblemException.TYPE_ORDER_PLACEMENT_FAILED);
                assertThat(statusOf(draft)).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            }
        }

        @Test
        @DisplayName("Given a transport failure, then 502 and the draft stays open; a retry then records the one order")
        void transport_failure_then_retry() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(new io.modelcontextprotocol.spec.McpSchema.CallToolResult(
                    List.of(io.modelcontextprotocol.spec.McpSchema.TextContent.builder("Error executing tool place_order: HTTP 503").build()),
                    true, Map.of(), Map.of()));

            assertThat(confirmRejected(sessionId, draft).getStatus()).isEqualTo(502);
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.WAITING_CONFIRMATION);

            orderManagement.acceptOrders();
            assertThat(service().confirm(sessionId, "alice", draft.getId(), "k").created()).isTrue();
            assertThat(orderManagement.ordersCreated()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("5. Cancel")
    class Cancel {

        @Test
        @DisplayName("Given an open draft, when cancelled, then CANCELLED and the model is told; cancelling again is idempotent")
        void cancels_open_draft_idempotently() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            assertThat(service().cancel(sessionId, "alice", draft.getId()).getStatus()).isEqualTo(DraftStatus.CANCELLED);
            assertThat(messagesOf(sessionId).getLast().getContent()).contains("cancelled");
            assertThat(service().cancel(sessionId, "alice", draft.getId()).getStatus()).isEqualTo(DraftStatus.CANCELLED);

            assertThat(statusOf(draft)).isEqualTo(DraftStatus.CANCELLED);
            assertThat(draftRepository.findOpenDraft(sessionId)).isEmpty();
        }

        @Test
        @DisplayName("Given a lapsed open draft, when cancelled, then EXPIRED")
        void cancelling_lapsed_draft_expires_it() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            assertThat(serviceAt(STAGED_AT.plusSeconds(20 * 60)).cancel(sessionId, "alice", draft.getId()).getStatus())
                    .isEqualTo(DraftStatus.EXPIRED);
        }

        @Test
        @DisplayName("Given a confirmed draft, when cancelled, then 409 draft-already-confirmed and it stays CONFIRMED")
        void confirmed_draft_cannot_be_cancelled() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            service().confirm(sessionId, "alice", draft.getId(), "k");

            assertThatThrownBy(() -> service().cancel(sessionId, "alice", draft.getId()))
                    .satisfies(e -> assertThat(problemOf(e).getType()).hasToString(DraftProblemException.TYPE_DRAFT_ALREADY_CONFIRMED));
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.CONFIRMED);
        }

        @Test
        @DisplayName("Given another user's session, when cancelling, then 403 and the draft stays open")
        void other_user_cannot_cancel() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            signIn("bob", false);

            assertThatThrownBy(() -> service().cancel(sessionId, "bob", draft.getId())).isInstanceOf(SessionAccessDeniedException.class);
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
        }

        @Test
        @DisplayName("Given a cancel lands while the order is being placed, then the placed order is still reported and never duplicated")
        void cancel_racing_placement_still_reports_the_order() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.duringNextPlacement(() -> service().cancel(sessionId, "alice", draft.getId()));

            OrderDraftConfirmationService.Confirmation confirmation = service().confirm(sessionId, "alice", draft.getId(), "k");

            assertThat(confirmation.created()).isTrue();
            assertThat(confirmation.orderNumber()).isEqualTo(orderManagement.orderNumberFor(draft.getId()));
            assertThat(orderManagement.ordersCreated()).isEqualTo(1);
            assertThat(statusOf(draft)).isEqualTo(DraftStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("6. Outcome metrics")
    class OutcomeMetrics {

        private io.micrometer.core.instrument.Timer closed(String outcome, String cause) {
            return meters.find("polaris.assistant.draft.lifetime").tags("outcome", outcome, "cause", cause).timer();
        }

        @Test
        @DisplayName("Given a confirmed draft, then one 'placed' decision timed from staging; a replay does not count again")
        void placed_is_counted_once() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);

            service().confirm(sessionId, "alice", draft.getId(), "click-1");
            service().confirm(sessionId, "alice", draft.getId(), "click-2");

            assertThat(closed("placed", "none").count()).isEqualTo(1);
            assertThat(closed("placed", "none").totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(60);
        }

        @Test
        @DisplayName("Given Order Management rejects with price-changed, then 'invalidated' with cause price-changed")
        void invalidated_carries_the_problem_type() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("price-changed", 409, "Price Changed", "changed", Map.of()));

            org.assertj.core.api.Assertions.catchThrowable(() -> service().confirm(sessionId, "alice", draft.getId(), "k"));

            assertThat(closed("invalidated", "price-changed").count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Given a transport failure, then no decision is counted: the draft stays open")
        void placement_failure_closes_nothing() {
            String sessionId = newSession("alice");
            OrderDraft draft = stagedDraft(sessionId);
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("internal-error", 500, "Internal Server Error", "boom", Map.of()));

            org.assertj.core.api.Assertions.catchThrowable(() -> service().confirm(sessionId, "alice", draft.getId(), "k"));

            assertThat(meters.find("polaris.assistant.draft.lifetime").timers()).isEmpty();
        }

        @Test
        @DisplayName("Given the shopper cancels twice, then one 'cancelled'; a lapsed draft confirmed or cancelled counts as 'expired'")
        void cancelled_and_expired() {
            String sessionId = newSession("alice");
            OrderDraft cancelled = stagedDraft(sessionId);
            service().cancel(sessionId, "alice", cancelled.getId());
            service().cancel(sessionId, "alice", cancelled.getId());

            String lapsedSession = newSession("alice");
            OrderDraft lapsed = stagedDraft(lapsedSession);
            org.assertj.core.api.Assertions.catchThrowable(
                    () -> serviceAt(STAGED_AT.plusSeconds(20 * 60)).confirm(lapsedSession, "alice", lapsed.getId(), "k"));

            assertThat(closed("cancelled", "none").count()).isEqualTo(1);
            assertThat(closed("expired", "none").count()).isEqualTo(1);
            assertThat(closed("expired", "none").totalTime(java.util.concurrent.TimeUnit.MINUTES)).isEqualTo(20);
        }
    }
}
