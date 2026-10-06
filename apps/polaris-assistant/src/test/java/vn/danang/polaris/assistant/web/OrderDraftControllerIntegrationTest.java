package vn.danang.polaris.assistant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentClassifier;
import vn.danang.polaris.assistant.intent.RedisIntentManager;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.tools.FakeOrderManagementMcp;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * The confirm and cancel endpoints end to end through HTTP, security, the real services and PostgreSQL, with
 * the external seams mocked (model, intent classifier, {@code /customers/me}, and Polaris Core MCP, whose
 * {@code place_order} is {@link FakeOrderManagementMcp}). Covers PRD-003 Scenarios 4, 9, 11 and 12.
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderDraftControllerIntegrationTest {

    private static final String QUOTE = """
        {"orderable": true, "totalAmount": 99.70, "lines": [
          {"sku": "NG-EARBUD-01", "name": "Nova Wireless Earbuds", "requestedQuantity": 1,
           "unitPrice": 99.70, "availableQuantity": 1, "lineTotal": 99.70, "problem": null}]}
        """;
    private static final List<DraftLine> LINES = List.of(
            DraftLine.of("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("99.70")));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @MockitoBean
    private CurrentCustomerClient currentCustomerClient;

    @MockitoBean
    private IntentClassifier intentClassifier;

    @MockitoBean
    private RedisIntentManager redisIntentManager;

    private FakeOrderManagementMcp orderManagement;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        orderManagement = new FakeOrderManagementMcp();
        DefaultIntentManager taxonomy = new DefaultIntentManager();
        when(redisIntentManager.listIntents()).thenReturn(taxonomy.listIntents());
        when(redisIntentManager.getIntent(anyString())).thenAnswer(inv -> taxonomy.getIntent(inv.getArgument(0)));
        when(intentClassifier.classify(anyString(), anyList(), any())).thenReturn(new IntentClassification("commerce.order.place", 0.99));

        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(
                Tool.builder("search_available_products", Map.of()).build(),
                Tool.builder("quote_order", Map.of()).build(),
                Tool.builder("place_order", Map.of()).build(),
                Tool.builder("cancel_order", Map.of()).build()));
        Map<String, Object> quote = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(QUOTE, Map.class);
        when(polarisMcpClient.callTool(eq("quote_order"), anyMap())).thenReturn(new CallToolResult(
                List.of(TextContent.builder("Quote").build()), false, quote, Map.of()));
        when(polarisMcpClient.callTool(eq("place_order"), anyMap()))
                .thenAnswer(inv -> orderManagement.placeOrder(inv.getArgument(1)));
        when(currentCustomerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(new CustomerRef(1L, "Alice Tran")));
    }

    private static RequestPostProcessor shopper(String subject) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(new SimpleGrantedAuthority("PERM_order.write"), new SimpleGrantedAuthority("PERM_catalog.read"));
    }

    private static MockHttpServletRequestBuilder confirmRequest(String sessionId, String draftId) {
        return post("/api/v1/assistant/sessions/{sessionId}/drafts/{draftId}/confirm", sessionId, draftId);
    }

    private ResultActions confirm(String subject, String sessionId, String draftId, String key) throws Exception {
        return mockMvc.perform(confirmRequest(sessionId, draftId).with(shopper(subject)).header("Idempotency-Key", key));
    }

    private ResultActions cancel(String subject, String sessionId, String draftId) throws Exception {
        return mockMvc.perform(post("/api/v1/assistant/sessions/{sessionId}/drafts/{draftId}/cancel", sessionId, draftId)
                .with(shopper(subject)));
    }

    private String session(String userId) {
        String id = UUID.randomUUID().toString();
        sessionRepository.saveAndFlush(AssistantSession.open(id, userId, Instant.now()));
        return id;
    }

    private OrderDraft draftStagedAt(String sessionId, Instant stagedAt) {
        return draftRepository.saveAndFlush(OrderDraft.stage(sessionId, 1L, LINES, OrderDraft.DEFAULT_TTL, stagedAt));
    }

    private DraftStatus statusOf(String draftId) {
        return draftRepository.findById(draftId).orElseThrow().getStatus();
    }

    @Nested
    @DisplayName("PRD-003 Scenario 4: human-in-the-loop submission with idempotency")
    class Scenario4 {

        @Test
        @DisplayName("Given a draft staged in chat, when the shopper clicks Submit Order (and double-clicks), then one order is placed, 201 then 200 replay, and the model was never offered place_order")
        @SuppressWarnings("unchecked")
        void stage_in_chat_then_confirm_once() throws Exception {
            String sessionId = UUID.randomUUID().toString();
            when(assistantModelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(new ToolCall("stage_order_draft",
                            Map.of("items", List.of(Map.of("sku", "NG-EARBUD-01", "quantity", 1)))))))
                    .thenReturn(new ModelResponse("Here is your draft. Click Submit Order to place it.", List.of()));

            String chat = mockMvc.perform(post("/api/v1/assistant/chat").with(shopper("alice"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"order 1 NG-EARBUD-01\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.widgets[0].type").value("ORDER_DRAFT"))
                    .andExpect(jsonPath("$.widgets[0].payload.total").value(99.70))
                    .andReturn().getResponse().getContentAsString();
            String draftId = JsonPath.read(chat, "$.widgets[0].payload.draftId");
            // staging never places an order, and the order intent never offers place_order or cancel_order
            verify(polarisMcpClient, never()).callTool(eq("place_order"), anyMap());
            ArgumentCaptor<List<Tool>> offered = ArgumentCaptor.forClass(List.class);
            verify(assistantModelClient, org.mockito.Mockito.atLeastOnce())
                    .generateResponse(anyList(), offered.capture(), any(ModelRequestContext.class));
            assertThat(offered.getAllValues()).allSatisfy(tools ->
                    assertThat(tools).extracting(Tool::name).doesNotContain("place_order", "cancel_order"));

            String key = UUID.randomUUID().toString();
            String body = confirm("alice", sessionId, draftId, key)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/orders/ORD-")))
                    .andExpect(jsonPath("$.sessionId").value(sessionId))
                    .andExpect(jsonPath("$.role").value("ASSISTANT"))
                    .andExpect(jsonPath("$.widgets.length()").value(1))
                    .andExpect(jsonPath("$.widgets[0].type").value("ORDER_CONFIRMED"))
                    .andExpect(jsonPath("$.widgets[0].payload.draftId").value(draftId))
                    .andExpect(jsonPath("$.widgets[0].payload.customerId").value(1))
                    .andExpect(jsonPath("$.widgets[0].payload.customerName").value("Alice Tran"))
                    .andExpect(jsonPath("$.widgets[0].payload.total").value(99.70))
                    .andExpect(jsonPath("$.widgets[0].payload.items[0].sku").value("NG-EARBUD-01"))
                    .andReturn().getResponse().getContentAsString();
            String orderNumber = JsonPath.read(body, "$.widgets[0].payload.orderNumber");

            // double-click: same key, and a resend with a fresh key
            for (String again : List.of(key, UUID.randomUUID().toString())) {
                confirm("alice", sessionId, draftId, again)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Location", "/api/v1/orders/" + orderNumber))
                        .andExpect(jsonPath("$.widgets[0].payload.orderNumber").value(orderNumber));
            }

            assertThat(orderManagement.ordersCreated()).isEqualTo(1);
            assertThat(orderManagement.placeOrderCalls()).singleElement().satisfies(args -> {
                assertThat(args).containsEntry("idempotency_key", draftId).doesNotContainKey("customer_id");
                assertThat(args.get("items").toString()).contains("expected_unit_price=99.7");
            });
            OrderDraft draft = draftRepository.findById(draftId).orElseThrow();
            assertThat(draft.getStatus()).isEqualTo(DraftStatus.CONFIRMED);
            assertThat(draft.getConfirmedOrderNumber()).isEqualTo(orderNumber);
            assertThat(draft.getIdempotencyKey()).isEqualTo(key);
        }

        @Test
        @DisplayName("Given no Idempotency-Key header, when confirming, then 400 problem and nothing is placed")
        void idempotency_key_is_required() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());

            mockMvc.perform(confirmRequest(sessionId, draft.getId()).with(shopper("alice")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"))
                    .andExpect(jsonPath("$.invalid_param").value("Idempotency-Key"));
            confirm("alice", sessionId, draft.getId(), "x".repeat(101)).andExpect(status().isBadRequest());

            assertThat(orderManagement.placeOrderCalls()).isEmpty();
            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
        }

        @Test
        @DisplayName("Given an unauthenticated caller, when confirming or cancelling, then 401 problem and nothing changes")
        void anonymous_is_unauthorized() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());

            mockMvc.perform(confirmRequest(sessionId, draft.getId()).header("Idempotency-Key", "k"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/unauthorized"));
            mockMvc.perform(post("/api/v1/assistant/sessions/{s}/drafts/{d}/cancel", sessionId, draft.getId()))
                    .andExpect(status().isUnauthorized());

            assertThat(orderManagement.placeOrderCalls()).isEmpty();
            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
        }
    }

    @Nested
    @DisplayName("PRD-003 Scenario 9: staged draft expiration and invalidation")
    class Scenario9 {

        @Test
        @DisplayName("Given a draft staged 20 minutes ago, when confirmed, then 409 draft-expired, the draft is EXPIRED and nothing is placed")
        void expired_draft_is_refused_and_persisted() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now().minusSeconds(20 * 60));

            confirm("alice", sessionId, draft.getId(), "k")
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-expired"))
                    .andExpect(jsonPath("$.draftId").value(draft.getId()))
                    .andExpect(jsonPath("$.actions[0].action").value("refresh_draft"));

            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.EXPIRED);
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }

        @Test
        @DisplayName("Given the price changed since staging, when confirmed, then 409 price-changed with changed_lines and the draft is INVALIDATED")
        void price_change_invalidates() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("price-changed", 409, "Price Changed",
                    "The price changed for 1 item: 'NG-EARBUD-01' expected 99.70, now 109.00.",
                    Map.of("changed_lines", List.of(Map.of("sku", "NG-EARBUD-01", "expected_unit_price", 99.70, "current_unit_price", 109.00)))));

            confirm("alice", sessionId, draft.getId(), "k")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/price-changed"))
                    .andExpect(jsonPath("$.changed_lines[0].sku").value("NG-EARBUD-01"))
                    .andExpect(jsonPath("$.draftStatus").value("INVALIDATED"));

            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.INVALIDATED);
            // and the stale draft can't be confirmed afterwards
            orderManagement.acceptOrders();
            confirm("alice", sessionId, draft.getId(), "k2")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-invalidated"));
            assertThat(orderManagement.ordersCreated()).isZero();
        }

        @Test
        @DisplayName("Given the shopper clicks Cancel, then 200 CANCELLED (idempotent) and a later confirm is refused with 409 draft-cancelled")
        void cancel_then_confirm_is_refused() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());

            for (int i = 0; i < 2; i++) {
                cancel("alice", sessionId, draft.getId())
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.draftId").value(draft.getId()))
                        .andExpect(jsonPath("$.status").value("CANCELLED"));
            }
            confirm("alice", sessionId, draft.getId(), "k")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-cancelled"));

            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }

        @Test
        @DisplayName("Given a confirmed draft, when cancel is clicked, then 409 draft-already-confirmed")
        void confirmed_draft_cannot_be_cancelled() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());
            confirm("alice", sessionId, draft.getId(), "k").andExpect(status().isCreated());

            cancel("alice", sessionId, draft.getId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-already-confirmed"));
            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.CONFIRMED);
        }
    }

    @Nested
    @DisplayName("PRD-003 Scenario 11: stock depleted by a concurrent buyer before confirmation")
    class Scenario11 {

        @Test
        @DisplayName("Given the last unit was bought by someone else, when Submit Order is clicked, then 400 out-of-stock with alternatives and the draft is INVALIDATED")
        void sold_out_invalidates_draft() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());
            orderManagement.rejectWith(FakeOrderManagementMcp.problem("out-of-stock", 400, "Insufficient Stock",
                    "Insufficient stock for product: NG-EARBUD-01",
                    Map.of("sku", "NG-EARBUD-01", "requested_quantity", 1, "available_quantity", 0)));

            confirm("alice", sessionId, draft.getId(), "k")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/out-of-stock"))
                    .andExpect(jsonPath("$.available_quantity").value(0))
                    .andExpect(jsonPath("$.draftStatus").value("INVALIDATED"))
                    .andExpect(jsonPath("$.actions[0].action").value("search_alternatives"));

            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.INVALIDATED);
            assertThat(orderManagement.ordersCreated()).isZero();
        }
    }

    @Nested
    @DisplayName("PRD-003 Scenario 12: IDOR access guard")
    class Scenario12 {

        @Test
        @DisplayName("Given Alice's draft, when Bob confirms or cancels it in Alice's session, then 403 with no draft details and nothing changes")
        void other_shopper_is_forbidden() throws Exception {
            String sessionId = session("alice");
            OrderDraft draft = draftStagedAt(sessionId, Instant.now());

            confirm("bob", sessionId, draft.getId(), "k")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"))
                    .andExpect(content().string(not(containsString("NG-EARBUD-01"))))
                    .andExpect(content().string(not(containsString("99.7"))));
            cancel("bob", sessionId, draft.getId()).andExpect(status().isForbidden());

            assertThat(statusOf(draft.getId())).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }

        @Test
        @DisplayName("Given Alice's draft id, when Bob uses it in his own session, then 404 draft-not-found and nothing is placed")
        void draft_id_from_another_session_is_not_found() throws Exception {
            OrderDraft alicesDraft = draftStagedAt(session("alice"), Instant.now());
            String bobsSession = session("bob");

            confirm("bob", bobsSession, alicesDraft.getId(), "k")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/draft-not-found"));

            assertThat(statusOf(alicesDraft.getId())).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
            assertThat(orderManagement.placeOrderCalls()).isEmpty();
        }
    }
}
