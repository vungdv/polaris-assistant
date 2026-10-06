package vn.danang.polaris.assistant.tools.local;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerLookupException;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.dto.OrderDraftCard;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.web.exception.DraftConflictException;
import vn.danang.polaris.assistant.service.OrderDraftService;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.LocalTool;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;

/**
 * {@code stage_order_draft} (BPMN {@code A_Stage} → {@code A_Draft}): turns the shopper's request into a
 * priced draft awaiting confirmation. It never places an order: the draft only becomes an order when the
 * shopper clicks "Submit Order" on the {@code ORDER_DRAFT} card.
 * <ol>
 *   <li>Refuse anonymous callers.</li>
 *   <li>Resolve the customer from the caller's identity via Order Management ({@code GET /customers/me}).
 *       Only staff may name another customer with {@code customer_id}; a shopper naming a different one is
 *       denied.</li>
 *   <li>Re-verify live price and stock with the read-only {@code quote_order} MCP tool.</li>
 *   <li>Only if every line is orderable, stage the draft (price snapshot, 15-minute TTL); otherwise hand the
 *       per-line problems back to the model.</li>
 * </ol>
 */
@Component
public class StageOrderDraftTool implements LocalTool {

    public static final String NAME = "stage_order_draft";
    static final String QUOTE_ORDER_TOOL = "quote_order";
    static final String CUSTOMER_LOOKUP_FAILED =
            "Could not look up the customer account right now, so nothing was staged. Please try again later.";

    private static final Logger log = LoggerFactory.getLogger(StageOrderDraftTool.class);

    private static final String INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "items": {
              "type": "array",
              "description": "Order lines to stage (lines repeating a SKU are merged)",
              "minItems": 1,
              "maxItems": 50,
              "items": {
                "type": "object",
                "properties": {
                  "sku": {
                    "type": "string",
                    "description": "Product SKU code (e.g. 'NG-CHARGER-01')"
                  },
                  "quantity": {
                    "type": "integer",
                    "minimum": 1,
                    "description": "Requested quantity (whole number, minimum 1)"
                  }
                },
                "required": ["sku", "quantity"]
              }
            },
            "customer_id": {
              "type": "integer",
              "description": "Staff only: the customer to stage the order for. Shoppers always stage for their own account; omit it for them."
            }
          },
          "required": ["items"]
        }
        """;

    private final PolarisMcpClient polarisMcpClient;
    private final CurrentCustomerClient currentCustomerClient;
    private final OrderDraftService orderDraftService;
    private final UserContext userContext;
    private final ObjectMapper objectMapper;
    private final Tool definition;

    @Autowired
    public StageOrderDraftTool(
            PolarisMcpClient polarisMcpClient,
            CurrentCustomerClient currentCustomerClient,
            OrderDraftService orderDraftService,
            UserContext userContext,
            ObjectMapper objectMapper) {
        this.polarisMcpClient = Objects.requireNonNull(polarisMcpClient, "polarisMcpClient must not be null");
        this.currentCustomerClient = Objects.requireNonNull(currentCustomerClient, "currentCustomerClient must not be null");
        this.orderDraftService = Objects.requireNonNull(orderDraftService, "orderDraftService must not be null");
        this.userContext = Objects.requireNonNull(userContext, "userContext must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.definition = Tool.builder(NAME, new JacksonMcpJsonMapper(objectMapper), INPUT_SCHEMA)
                .description("Stage an order draft for the shopper to review: re-checks live price and stock, then holds the "
                        + "price for 15 minutes on a draft card. Does NOT place the order; the shopper must click 'Submit Order' "
                        + "on the card. Staging again replaces the previous draft.")
                .build();
    }

    @Override
    public Tool definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ToolExecutionContext context) {
        Optional<SignedInCaller> signedIn = SignedInCaller.resolve(context, userContext);
        if (signedIn.isEmpty()) {
            return ToolResult.denied(toolCall, "Sign in to stage an order: anonymous callers cannot create order drafts.");
        }
        SignedInCaller caller = signedIn.get();
        String sessionId = context.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResult.error(toolCall, "An order draft needs a chat session.");
        }

        Object items = toolCall.arguments().get("items");
        if (!(items instanceof List<?> itemList) || itemList.isEmpty()) {
            return ToolResult.error(toolCall, "Parameter 'items' is required and must list at least one {sku, quantity}.");
        }

        // 1. Customer: from the caller's identity; only staff may name another customer
        CustomerRef customer;
        try {
            CustomerChoice choice = resolveCustomer(toolCall.arguments().get("customer_id"), caller);
            if (choice.rejection() != null) {
                return choice.rejection().apply(toolCall);
            }
            customer = choice.customer();
        } catch (CustomerLookupException e) {
            // details are logged by the client; the model only gets a fixed message
            log.warn("Could not resolve the caller's customer sessionId={}", sessionId);
            return ToolResult.error(toolCall, CUSTOMER_LOOKUP_FAILED);
        }

        // 2. Re-verify live price and stock (read-only)
        OrderQuote quote;
        try {
            CallToolResult result = polarisMcpClient.callTool(QUOTE_ORDER_TOOL, Map.of("items", items));
            if (result == null || Boolean.TRUE.equals(result.isError())) {
                return ToolResult.error(toolCall, "Could not check price and stock: " + text(result));
            }
            if (result.structuredContent() == null) {
                return ToolResult.error(toolCall, "Could not check price and stock: quote_order returned no structured result.");
            }
            quote = objectMapper.convertValue(result.structuredContent(), OrderQuote.class);
        } catch (IllegalArgumentException e) {
            log.error("Unreadable quote_order result sessionId={} error={}", sessionId, e.getMessage());
            return ToolResult.error(toolCall, "Could not check price and stock: unreadable quote result.");
        }

        if (!quote.isStageable()) {
            return ToolResult.error(toolCall, describeProblems(quote), "Order draft not staged: some lines cannot be ordered as requested.");
        }

        // 3. Stage the snapshot (supersedes any open draft of the session)
        OrderDraft draft;
        try {
            draft = orderDraftService.stage(sessionId, caller.userId(), customer.id(), toDraftLines(quote));
        } catch (SessionAccessDeniedException e) {
            return ToolResult.denied(toolCall, e.getMessage());
        } catch (DraftConflictException e) {
            return ToolResult.error(toolCall, "The order draft was changed at the same time by another request; nothing was staged. Please try again.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ToolResult.error(toolCall, "Could not stage the order draft: " + e.getMessage());
        }

        String customerName = customer.fullName() != null
                ? customer.fullName()
                : currentCustomerClient.findCustomerName(customer.id(), caller.bearerToken()).orElse(null);
        OrderDraftCard card = OrderDraftCard.from(draft, customerName);
        return ToolResult.success(toolCall, describeDraft(card)).withWidget(card.toWidget());
    }

    private CustomerChoice resolveCustomer(Object rawCustomerId, SignedInCaller caller) {
        Long requested = null;
        if (rawCustomerId != null) {
            requested = parseCustomerId(rawCustomerId);
            if (requested == null) {
                return CustomerChoice.reject(call -> ToolResult.error(call, "Parameter 'customer_id' must be a positive whole number."));
            }
        }

        if (userContext.isStaff() && requested != null) {
            // name resolved best-effort after staging
            return CustomerChoice.of(new CustomerRef(requested, null));
        }

        Optional<CustomerRef> own = currentCustomerClient.findCurrentCustomer(caller.bearerToken());
        if (userContext.isStaff()) {
            return own.map(CustomerChoice::of).orElseGet(() -> CustomerChoice.reject(call -> ToolResult.error(call,
                    "Pass 'customer_id' for the customer this order is for (look it up with search_customers_by_name).")));
        }
        if (own.isEmpty()) {
            return CustomerChoice.reject(call -> ToolResult.error(call,
                    "No customer account is linked to your sign-in, so an order cannot be staged."));
        }
        if (requested != null && !requested.equals(own.get().id())) {
            log.warn("Shopper tried to stage a draft for another customer userId={} requestedCustomerId={}", caller.userId(), requested);
            return CustomerChoice.reject(call -> ToolResult.denied(call,
                    "Forbidden: shoppers can only stage orders for their own customer account."));
        }
        return CustomerChoice.of(own.get());
    }

    private static Long parseCustomerId(Object raw) {
        try {
            BigDecimal number = raw instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(raw.toString().trim());
            if (number.stripTrailingZeros().scale() > 0 || number.signum() <= 0) {
                return null;
            }
            return number.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static List<DraftLine> toDraftLines(OrderQuote quote) {
        List<DraftLine> lines = new ArrayList<>();
        for (OrderQuote.Line line : quote.lines()) {
            lines.add(new DraftLine(line.sku(), line.name(), line.requestedQuantity(), line.unitPrice(), line.lineTotal()));
        }
        return lines;
    }

    private static String describeProblems(OrderQuote quote) {
        String problems = quote.lines().stream()
                .filter(line -> !line.isOrderable())
                .map(line -> {
                    String problem = line.problem() != null ? line.problem() : "not_orderable";
                    if ("insufficient_stock".equals(problem)) {
                        return "  * [%s] %s insufficient_stock: requested %d, available %d".formatted(
                                line.sku(), line.name(), line.requestedQuantity(), line.availableQuantity());
                    }
                    return "  * [%s] %s".formatted(line.sku(), problem);
                })
                .collect(Collectors.joining("\n"));
        return "Order draft NOT staged: some lines cannot be ordered as requested. Nothing was reserved or ordered.\n"
                + problems + "\nTell the shopper which lines are affected and ask how they want to proceed.";
    }

    private static String describeDraft(OrderDraftCard card) {
        String lines = card.items().stream()
                .map(line -> "  * [%s] %s x %d @ $%s = $%s".formatted(
                        line.sku(), line.name(), line.quantity(), line.unitPrice(), line.lineTotal()))
                .collect(Collectors.joining("\n"));
        String customer = card.customerName() != null
                ? card.customerName() + " (customer " + card.customerId() + ")"
                : "customer " + card.customerId();
        return "Order draft " + card.draftId() + " for " + customer + " staged for the shopper's confirmation. The order is NOT placed.\n"
                + lines + "\n- Total: $" + card.total()
                + "\n- Price held until " + card.expiresAt() + " (15 minutes)."
                + "\nThe shopper must review the draft card and click 'Submit Order' to place it. Never say the order is placed.";
    }

    private static String text(CallToolResult result) {
        if (result == null || result.content() == null) {
            return "no result";
        }
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("no result");
    }

    /** Either the customer to stage for, or how to reject the call. */
    private record CustomerChoice(CustomerRef customer, Function<ToolCall, ToolResult> rejection) {

        static CustomerChoice of(CustomerRef customer) {
            return new CustomerChoice(customer, null);
        }

        static CustomerChoice reject(Function<ToolCall, ToolResult> rejection) {
            return new CustomerChoice(null, rejection);
        }
    }
}
