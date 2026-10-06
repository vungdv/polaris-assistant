package vn.danang.polaris.assistant.tools.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerLookupException;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.OrderDraftCard;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.web.exception.DraftConflictException;
import vn.danang.polaris.assistant.service.OrderDraftService;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * Unit tests for {@link StageOrderDraftTool}.
 * <p>
 * SUT Responsibility: resolve the customer from the caller's identity (staff may name one), re-verify with
 * {@code quote_order}, and stage a draft only when every line is orderable. Order Management (MCP and
 * {@code /customers/me}) and draft persistence are mocked at their seams.
 */
class StageOrderDraftToolTest {

    private static final String SESSION = "sess-1";
    private static final String USER = "shopper-sub";
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final CustomerRef ALICE = new CustomerRef(7L, "Alice Tran");

    private PolarisMcpClient mcpClient;
    private CurrentCustomerClient customerClient;
    private OrderDraftService draftService;
    private StageOrderDraftTool tool;

    @BeforeEach
    void setUp() {
        mcpClient = mock(PolarisMcpClient.class);
        customerClient = mock(CurrentCustomerClient.class);
        draftService = mock(OrderDraftService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        tool = new StageOrderDraftTool(mcpClient, customerClient, draftService, new UserContext(), objectMapper);
        signIn(USER);
        when(draftService.stage(anyString(), anyString(), anyLong(), anyList())).thenAnswer(inv ->
                OrderDraft.stage(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3), OrderDraft.DEFAULT_TTL, NOW));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String subject, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token-" + subject).header("alg", "none").claim("sub", subject).build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
    }

    private static ToolExecutionContext context(String userId) {
        return new ToolExecutionContext(SESSION, userId, 1);
    }

    private static ToolCall stageCall(Map<String, Object> extra) {
        Map<String, Object> args = new java.util.HashMap<>(extra);
        args.put("items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 2)));
        return new ToolCall(StageOrderDraftTool.NAME, args);
    }

    /** structuredContent as HttpPolarisMcpClient hands it over: a generic map with BigDecimal floats. */
    private void quoteReturns(String json) throws Exception {
        Object structured = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(json, Map.class);
        when(mcpClient.callTool(eq("quote_order"), anyMap())).thenReturn(new CallToolResult(
                List.of(TextContent.builder("Quote").build()), false, structured, Map.of()));
    }

    private static final String ORDERABLE_QUOTE = """
        {"orderable": true, "totalAmount": 49.80, "lines": [
          {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "requestedQuantity": 2,
           "unitPrice": 24.90, "availableQuantity": 200, "lineTotal": 49.80, "problem": null}]}
        """;

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given an orderable quote, when a shopper stages, then the draft is staged for their own customer with an ORDER_DRAFT widget")
        void stages_draft_for_shoppers_own_customer() throws Exception {
            when(customerClient.findCurrentCustomer("token-" + USER)).thenReturn(Optional.of(ALICE));
            quoteReturns(ORDERABLE_QUOTE);

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.result()).contains("NOT placed").contains("Submit Order").contains("$49.80");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DraftLine>> lines = ArgumentCaptor.forClass(List.class);
            verify(draftService).stage(eq(SESSION), eq(USER), eq(7L), lines.capture());
            assertThat(lines.getValue()).containsExactly(
                    new DraftLine("NG-CHARGER-01", "Nova 65W Fast Charger", 2, new BigDecimal("24.90"), new BigDecimal("49.80")));

            ChatWidget widget = result.widget();
            assertThat(widget.type()).isEqualTo(ChatWidget.ORDER_DRAFT);
            OrderDraftCard card = (OrderDraftCard) widget.payload();
            assertThat(card.draftId()).startsWith("dft-");
            assertThat(card.customerId()).isEqualTo(7L);
            assertThat(card.customerName()).isEqualTo("Alice Tran");
            assertThat(result.result()).contains("for Alice Tran (customer 7)");
            assertThat(card.total()).isEqualByComparingTo("49.80");
            assertThat(card.expiresAt()).isEqualTo(NOW.plus(OrderDraft.DEFAULT_TTL));
            assertThat(card.items()).hasSize(1);
        }

        @Test
        @DisplayName("Given the quote_order call, then the model's items are forwarded unchanged and nothing but quote_order is called")
        void forwards_items_to_quote_order_only() throws Exception {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            quoteReturns(ORDERABLE_QUOTE);

            tool.execute(stageCall(Map.of()), context(USER));

            verify(mcpClient).callTool("quote_order", Map.of("items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 2))));
            verify(mcpClient, never()).callTool(eq("place_order"), anyMap());
        }

        @Test
        @DisplayName("Given staff passes customer_id, when staging, then that customer is used without a /me lookup")
        void staff_may_name_customer() throws Exception {
            signIn("staff-sub", "ROLE_PURCHASE_MANAGEMENT");
            quoteReturns(ORDERABLE_QUOTE);

            ToolResult result = tool.execute(stageCall(Map.of("customer_id", 42)), context("staff-sub"));

            assertThat(result.isSuccess()).isTrue();
            verify(draftService).stage(eq(SESSION), eq("staff-sub"), eq(42L), anyList());
            verify(customerClient, never()).findCurrentCustomer(anyString());
            OrderDraftCard card = (OrderDraftCard) result.widget().payload();
            assertThat(card.customerId()).isEqualTo(42L);
            assertThat(card.customerName()).isNull();
        }

        @Test
        @DisplayName("Given staff names a customer whose name Order Management returns, when staging, then the card shows it")
        void staff_card_shows_name_when_available() throws Exception {
            signIn("staff-sub", "ROLE_STAFF");
            quoteReturns(ORDERABLE_QUOTE);
            when(customerClient.findCustomerName(42L, "token-staff-sub")).thenReturn(Optional.of("Bob Le"));

            ToolResult result = tool.execute(stageCall(Map.of("customer_id", 42)), context("staff-sub"));

            OrderDraftCard card = (OrderDraftCard) result.widget().payload();
            assertThat(card.customerId()).isEqualTo(42L);
            assertThat(card.customerName()).isEqualTo("Bob Le");
        }

        @Test
        @DisplayName("Given a shopper passes their own customer_id, when staging, then it is accepted")
        void shopper_may_repeat_own_customer_id() throws Exception {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            quoteReturns(ORDERABLE_QUOTE);

            ToolResult result = tool.execute(stageCall(Map.of("customer_id", "7")), context(USER));

            assertThat(result.isSuccess()).isTrue();
            verify(draftService).stage(eq(SESSION), eq(USER), eq(7L), anyList());
        }
    }

    @Nested
    @DisplayName("2. Quote problems")
    class QuoteProblems {

        @Test
        @DisplayName("Given a shortage, when staging, then no draft is staged and the per-line problem is returned to the model")
        void shortage_returns_problems_without_staging() throws Exception {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            quoteReturns("""
                {"orderable": false, "totalAmount": 0, "lines": [
                  {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "requestedQuantity": 2,
                   "unitPrice": 24.90, "availableQuantity": 1, "lineTotal": null, "problem": "insufficient_stock"}]}
                """);

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            assertThat(result.widget()).isNull();
            assertThat(result.result())
                    .contains("NOT staged")
                    .contains("[NG-CHARGER-01] Nova 65W Fast Charger insufficient_stock: requested 2, available 1");
            verify(draftService, never()).stage(anyString(), anyString(), anyLong(), anyList());
        }

        @Test
        @DisplayName("Given an unknown SKU, when staging, then not_found is reported and nothing is staged")
        void unknown_sku_returns_problem() throws Exception {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            quoteReturns("""
                {"orderable": false, "totalAmount": 0, "lines": [
                  {"sku": "NOPE", "name": null, "requestedQuantity": 2, "unitPrice": null,
                   "availableQuantity": null, "lineTotal": null, "problem": "not_found"}]}
                """);

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            assertThat(result.result()).contains("[NOPE] not_found");
            verify(draftService, never()).stage(anyString(), anyString(), anyLong(), anyList());
        }

        @Test
        @DisplayName("Given quote_order fails, when staging, then a tool error is returned and nothing is staged")
        void quote_error_is_tool_error() {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            when(mcpClient.callTool(eq("quote_order"), anyMap())).thenReturn(new CallToolResult(
                    List.of(TextContent.builder("Item 'quantity' must be a whole number").build()), true, null, Map.of()));

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            assertThat(result.result()).contains("must be a whole number");
            verify(draftService, never()).stage(anyString(), anyString(), anyLong(), anyList());
        }

        @Test
        @DisplayName("Given orderable=true but a line without a price, when staging, then it is not staged")
        void incomplete_line_is_not_staged() throws Exception {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            quoteReturns("""
                {"orderable": true, "totalAmount": 0, "lines": [
                  {"sku": "NG-CHARGER-01", "name": "X", "requestedQuantity": 2, "unitPrice": null,
                   "availableQuantity": 5, "lineTotal": null, "problem": null}]}
                """);

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            verify(draftService, never()).stage(anyString(), anyString(), anyLong(), anyList());
        }
    }

    @Nested
    @DisplayName("3. Identity and authorization")
    class Identity {

        @Test
        @DisplayName("Given a shopper names another customer_id, when staging, then denied and neither quoted nor staged")
        void shopper_cannot_override_customer() {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));

            ToolResult result = tool.execute(stageCall(Map.of("customer_id", 99)), context(USER));

            assertThat(result.isDenied()).isTrue();
            assertThat(result.result()).contains("own customer account");
            verifyNoInteractions(mcpClient);
            verify(draftService, never()).stage(anyString(), anyString(), anyLong(), anyList());
        }

        @Test
        @DisplayName("Given an anonymous caller, when staging, then denied before any lookup")
        void anonymous_is_refused() {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
            SecurityContextHolder.setContext(context);

            ToolResult result = tool.execute(stageCall(Map.of()), context("anonymous"));

            assertThat(result.isDenied()).isTrue();
            verifyNoInteractions(customerClient, mcpClient, draftService);
        }

        @Test
        @DisplayName("Given the shared 'anonymous' user id, when staging even with a token, then denied")
        void anonymous_user_id_is_refused() {
            ToolResult result = tool.execute(stageCall(Map.of()), context("anonymous"));

            assertThat(result.isDenied()).isTrue();
            verifyNoInteractions(customerClient, mcpClient, draftService);
        }

        @Test
        @DisplayName("Given no customer is linked to the shopper, when staging, then a tool error and nothing staged")
        void unlinked_shopper_gets_error() {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.empty());

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            assertThat(result.result()).contains("No customer account is linked");
            verifyNoInteractions(mcpClient);
        }

        @Test
        @DisplayName("Given staff without customer_id and no own link, when staging, then asked for customer_id")
        void staff_without_customer_is_asked_for_one() {
            signIn("staff-sub", "ROLE_ADMIN");
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.empty());

            ToolResult result = tool.execute(stageCall(Map.of()), context("staff-sub"));

            assertThat(result.isError()).isTrue();
            assertThat(result.result()).contains("customer_id");
            verifyNoInteractions(mcpClient);
        }

        @Test
        @DisplayName("Given the /me lookup fails, when staging, then a tool error")
        void customer_lookup_failure_is_tool_error() {
            when(customerClient.findCurrentCustomer(anyString()))
                    .thenThrow(new CustomerLookupException("internal detail: HTTP 503 from polaris:8080"));

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            assertThat(result.result()).isEqualTo(StageOrderDraftTool.CUSTOMER_LOOKUP_FAILED).doesNotContain("503");
        }
    }

    @Nested
    @DisplayName("4. Invalid input and conflicts")
    class InvalidInput {

        @Test
        @DisplayName("Given no items, when staging, then a tool error")
        void missing_items() {
            ToolResult result = tool.execute(new ToolCall(StageOrderDraftTool.NAME, Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            verifyNoInteractions(mcpClient, draftService);
        }

        @Test
        @DisplayName("Given a malformed customer_id, when staging, then a tool error")
        void malformed_customer_id() {
            ToolResult result = tool.execute(stageCall(Map.of("customer_id", "abc")), context(USER));

            assertThat(result.isError()).isTrue();
            verifyNoInteractions(mcpClient, draftService);
        }

        @Test
        @DisplayName("Given a concurrent draft change keeps winning, when staging, then a clean tool error")
        void conflict_is_clean_tool_error() throws Exception {
            when(customerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(ALICE));
            quoteReturns(ORDERABLE_QUOTE);
            doThrow(new DraftConflictException(SESSION, new RuntimeException("lost")))
                    .when(draftService).stage(anyString(), anyString(), anyLong(), anyList());

            ToolResult result = tool.execute(stageCall(Map.of()), context(USER));

            assertThat(result.isError()).isTrue();
            assertThat(result.result()).contains("try again");
            assertThat(result.widget()).isNull();
        }
    }

    @Test
    @DisplayName("Definition exposes the stage_order_draft schema with items and staff-only customer_id")
    void definition_schema() {
        assertThat(tool.name()).isEqualTo("stage_order_draft");
        assertThat(tool.definition().inputSchema()).containsKey("properties");
        assertThat(tool.definition().description()).contains("Does NOT place the order");
    }
}
