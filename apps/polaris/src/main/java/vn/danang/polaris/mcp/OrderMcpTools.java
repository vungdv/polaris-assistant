package vn.danang.polaris.mcp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import vn.danang.polaris.config.PolarisPermissions;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderItem;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.security.CallerIdentity;
import vn.danang.polaris.order.service.CustomerService;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.exception.IdempotencyKeyReusedException;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.PriceChangedException;
import vn.danang.polaris.web.exception.ProductInactiveException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

/**
 * Dedicated Presentation Facade exposing order management tools for MCP clients.
 */
@Component
public class OrderMcpTools {

    public static final String TOOL_GET_ORDER_STATUS = "get_order_status";
    public static final String TOOL_GET_ORDER_DETAILS = "get_order_details";
    public static final String TOOL_PLACE_ORDER = "place_order";
    public static final String TOOL_LIST_CUSTOMER_ORDERS = "list_customer_orders";
    public static final String TOOL_CANCEL_ORDER = "cancel_order";
    public static final String TOOL_SEARCH_CUSTOMERS_BY_NAME = "search_customers_by_name";

    private static final String ORDER_WRITE_AUTHORITY = "PERM_" + PolarisPermissions.ORDER_WRITE;

    private static final Logger log = LoggerFactory.getLogger(OrderMcpTools.class);

    // Problem types shared with GlobalExceptionHandler, so place_order errors read like the REST API's
    private static final String TYPE_VALIDATION_ERROR = "https://polaris.local/errors/validation-error";
    private static final String TYPE_NOT_FOUND = "https://polaris.local/errors/not-found";
    private static final String TYPE_FORBIDDEN = "https://polaris.local/errors/forbidden";
    private static final String TYPE_INTERNAL_ERROR = "https://polaris.local/errors/internal-error";

    private static final String GET_ORDER_STATUS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "order_number": {
              "type": "string",
              "description": "Unique business order number (e.g. 'ORD-1001')"
            }
          },
          "required": ["order_number"]
        }
        """;

    private static final String GET_ORDER_DETAILS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "order_number": {
              "type": "string",
              "description": "Unique business order number (e.g. 'ORD-1001')"
            }
          },
          "required": ["order_number"]
        }
        """;

    private static final String PLACE_ORDER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "customer_id": {
              "type": "integer",
              "description": "Unique numeric ID of the customer placing the order (use this or customer_name). Staff only: shoppers always order for their own linked customer"
            },
            "customer_name": {
              "type": "string",
              "description": "Customer name for fuzzy lookup — partial, case-insensitive, typo-tolerant. Used when customer_id is absent. Returns disambiguation list if multiple matches found."
            },
            "items": {
              "type": "array",
              "description": "List of line items to order",
              "items": {
                "type": "object",
                "properties": {
                  "sku": {
                    "type": "string",
                    "description": "Product SKU code (e.g. 'NG-EARBUD-01')"
                  },
                  "quantity": {
                    "type": "integer",
                    "description": "Quantity to order (minimum 1)"
                  },
                  "expected_unit_price": {
                    "type": "number",
                    "minimum": 0,
                    "description": "Optional unit price the shopper confirmed. If the live price differs, nothing is ordered and the error carries the changed lines"
                  }
                },
                "required": ["sku", "quantity"]
              }
            },
            "idempotency_key": {
              "type": "string",
              "maxLength": 100,
              "description": "Optional unique idempotency key (at most 100 characters) to prevent duplicate orders during retries. A retry returns the original order; a key already used for another customer is rejected"
            }
          },
          "required": ["items"]
        }
        """;

    private static final String LIST_CUSTOMER_ORDERS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "customer_id": {
              "type": "integer",
              "description": "Unique numeric customer ID"
            },
            "status": {
              "type": "string",
              "description": "Optional order lifecycle status filter (PLACED, CONFIRMED, PARCELED, DELIVERING, DELIVERED, CANCELLED)"
            },
            "page": {
              "type": "integer",
              "description": "Zero-based page index (default: 0)"
            },
            "size": {
              "type": "integer",
              "description": "Number of records per page (default: 20)"
            }
          },
          "required": ["customer_id"]
        }
        """;

    private static final String CANCEL_ORDER_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "order_number": {
              "type": "string",
              "description": "Unique business order number to cancel (e.g. 'ORD-1001')"
            }
          },
          "required": ["order_number"]
        }
        """;

    private static final String SEARCH_CUSTOMERS_BY_NAME_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Customer name to search — partial or full, case-insensitive, typo-tolerant"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of candidates to return (default: 5, max: 20)"
            }
          },
          "required": ["name"]
        }
        """;

    private final OrderService orderService;
    private final CustomerService customerService;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public OrderMcpTools(
            OrderService orderService,
            CustomerService customerService,
            ObjectProvider<Tracer> tracerProvider) {
        this.orderService = orderService;
        this.customerService = customerService;
        this.tracer = tracerProvider != null ? tracerProvider.getIfAvailable() : null;
    }

    public OrderMcpTools(OrderService orderService, CustomerService customerService) {
        this(orderService, customerService, null);
    }

    public OrderMcpTools(OrderService orderService, ObjectProvider<Tracer> tracerProvider) {
        this(orderService, null, tracerProvider);
    }

    public OrderMcpTools(OrderService orderService) {
        this(orderService, null, null);
    }

    public McpSchema.Tool getOrderStatusTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_GET_ORDER_STATUS, jsonMapper, GET_ORDER_STATUS_SCHEMA)
                .description("Retrieve live order fulfillment status, customer details, line items, and total amount by order number")
                .build();
    }

    public McpSchema.Tool getOrderStatusTool() {
        return getOrderStatusTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getOrderDetailsTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_GET_ORDER_DETAILS, jsonMapper, GET_ORDER_DETAILS_SCHEMA)
                .description("Retrieve full order details, line items, pricing, and fulfillment status by order number")
                .build();
    }

    public McpSchema.Tool getOrderDetailsTool() {
        return getOrderDetailsTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getPlaceOrderTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_PLACE_ORDER, jsonMapper, PLACE_ORDER_SCHEMA)
                .description("Place a new multi-item order for a customer. Staff accept customer_id or customer_name (fuzzy match) "
                        + "and get a disambiguation candidate list if multiple name matches are found. "
                        + "Shoppers always order for the customer linked to their own identity.")
                .build();
    }

    public McpSchema.Tool getPlaceOrderTool() {
        return getPlaceOrderTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getListCustomerOrdersTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_LIST_CUSTOMER_ORDERS, jsonMapper, LIST_CUSTOMER_ORDERS_SCHEMA)
                .description("Search and list order history for a customer with optional status filter and pagination")
                .build();
    }

    public McpSchema.Tool getListCustomerOrdersTool() {
        return getListCustomerOrdersTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getCancelOrderTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_CANCEL_ORDER, jsonMapper, CANCEL_ORDER_SCHEMA)
                .description("Cancel an order in PLACED or CONFIRMED status by order number, restoring inventory stock")
                .build();
    }

    public McpSchema.Tool getCancelOrderTool() {
        return getCancelOrderTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    public McpSchema.Tool getSearchCustomersByNameTool(McpJsonMapper jsonMapper) {
        return McpSchema.Tool.builder(TOOL_SEARCH_CUSTOMERS_BY_NAME, jsonMapper, SEARCH_CUSTOMERS_BY_NAME_SCHEMA)
                .description("Search for customers by partial or fuzzy name match. Returns ranked candidates with id, name, "
                        + "and email for order placement disambiguation.")
                .build();
    }

    public McpSchema.Tool getSearchCustomersByNameTool() {
        return getSearchCustomersByNameTool(new JacksonMcpJsonMapper(new ObjectMapper()));
    }

    private McpSchema.CallToolResult executeWithSpan(String toolName, Supplier<McpSchema.CallToolResult> execution) {
        if (this.tracer == null) {
            return execution.get();
        }

        String spanName = "mcp.server.tool_call %s".formatted(toolName);
        Span span = this.tracer.nextSpan().name(spanName);
        span.tag("gen_ai.tool.name", toolName);
        span.tag("mcp.server", "polaris-mcp");
        span.tag("mcp.category", "order");
        span.start();

        try (Tracer.SpanInScope ws = this.tracer.withSpan(span)) {
            McpSchema.CallToolResult result = execution.get();
            if (result != null && Boolean.TRUE.equals(result.isError())) {
                span.tag("error", "true");
            }
            return result;
        } catch (Exception ex) {
            span.error(ex);
            span.tag("error", "true");
            throw ex;
        } finally {
            span.end();
        }
    }

    public McpSchema.CallToolResult getOrderStatus(Map<String, Object> arguments) {
        String orderNumber = getOrderNumber(arguments);
        return getOrderStatus(orderNumber);
    }

    @Transactional(readOnly = true)
    public McpSchema.CallToolResult getOrderStatus(String orderNumber) {
        return executeWithSpan(TOOL_GET_ORDER_STATUS, () -> {
            if (orderNumber == null || orderNumber.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'order_number' is required.")
                        .isError(true)
                        .build();
            }
            try {
                Order order = orderService.getOrderStatus(orderNumber.trim());
                String formatted = formatOrderStatus(order);
                return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
            } catch (ResourceNotFoundException ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Order not found with order number: " + orderNumber.trim())
                        .isError(true)
                        .build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error retrieving order '" + orderNumber + "': " + ex.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    public McpSchema.CallToolResult getOrderDetails(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_GET_ORDER_DETAILS, () -> {
            String orderNumber = getOrderNumber(arguments);
            if (orderNumber == null || orderNumber.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'order_number' is required.")
                        .isError(true)
                        .build();
            }
            try {
                Order order = orderService.getOrderStatus(orderNumber.trim());
                String formatted = formatOrderStatus(order);
                return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
            } catch (ResourceNotFoundException ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Order not found with order number: " + orderNumber.trim())
                        .isError(true)
                        .build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error retrieving order '" + orderNumber + "': " + ex.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    public McpSchema.CallToolResult placeOrder(Map<String, Object> arguments) {
        return placeOrder(arguments, SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Places an order for the given caller. Staff may name any customer; any other caller always orders
     * for their own linked customer, and naming a different {@code customer_id} is rejected (PRD-003 FR-10).
     *
     * @param arguments      tool arguments
     * @param authentication authenticated MCP caller captured from the transport request
     */
    public McpSchema.CallToolResult placeOrder(Map<String, Object> arguments, @Nullable Authentication authentication) {
        return executeWithSpan(TOOL_PLACE_ORDER, () -> {
            if (arguments == null) {
                return validationError("Arguments are required.", null);
            }

            // Resolve customer: prefer customer_id, fall back to customer_name fuzzy lookup
            Long customerId = parseLong(arguments.get("customer_id") != null ? arguments.get("customer_id") : arguments.get("customerId"));

            CallerIdentity caller = CallerIdentity.from(authentication);
            if (caller == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Authentication is required to place an order.")
                        .isError(true)
                        .build();
            }
            if (authentication.getAuthorities().stream()
                    .noneMatch(authority -> ORDER_WRITE_AUTHORITY.equals(authority.getAuthority()))) {
                String message = "Forbidden: the '" + PolarisPermissions.ORDER_WRITE + "' permission is required to place an order.";
                return problemResult(message, problem(TYPE_FORBIDDEN, "Forbidden", 403, message));
            }
            if (!caller.staff()) {
                // Shoppers: the customer comes from the caller's identity, never from model-supplied arguments
                try {
                    customerId = customerService.resolveOrderingCustomerId(caller, customerId);
                } catch (AccessDeniedException ex) {
                    String message = "Forbidden: " + ex.getMessage();
                    return problemResult(message, problem(TYPE_FORBIDDEN, "Forbidden", 403, message));
                } catch (ResourceNotFoundException ex) {
                    return notFound(ex.getMessage() + " The order cannot be placed.");
                } catch (Exception ex) {
                    return internalError("resolving the customer for the authenticated user", ex);
                }
            }

            if (customerId == null) {
                Object rawCustomerName = arguments.get("customer_name") != null ? arguments.get("customer_name") : arguments.get("customerName");
                String customerName = rawCustomerName != null ? rawCustomerName.toString().trim() : null;

                if (customerName == null || customerName.isBlank()) {
                    return validationError("Either 'customer_id' or 'customer_name' is required to identify the customer.", "customer_id");
                }

                // Fuzzy name lookup
                List<CustomerSummaryResponse> candidates;
                try {
                    candidates = customerService.searchByName(customerName, 10);
                } catch (Exception ex) {
                    return internalError("searching customers by name", ex);
                }

                if (candidates.isEmpty()) {
                    return notFound("No customer found matching '" + customerName + "'. "
                            + "Please check the name spelling and retry, or provide the customer_id directly.");
                }

                if (candidates.size() > 1) {
                    // Disambiguation: return candidate list without placing the order
                    List<String> lines = new ArrayList<>();
                    lines.add("Multiple customers found for '" + customerName + "'. Please confirm by specifying customer_id:");
                    for (int i = 0; i < candidates.size(); i++) {
                        CustomerSummaryResponse c = candidates.get(i);
                        lines.add(String.format("%d. [ID: %d] %s | %s", i + 1, c.id(), c.fullName(), c.email()));
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(String.join("\n", lines))
                            .isError(false)
                            .build();
                }

                // Exactly 1 match — proceed with resolved customer
                customerId = candidates.get(0).id();
            }

            Object rawItems = arguments.get("items");
            if (!(rawItems instanceof List<?> itemsList) || itemsList.isEmpty()) {
                return validationError("Parameter 'items' is required and must not be empty.", "items");
            }

            List<OrderItemRequest> reqItems = new ArrayList<>();
            for (Object itemObj : itemsList) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) {
                    return validationError("Each item must be an object with 'sku' and 'quantity'.", "items");
                }

                Object rawSku = itemMap.get("sku");
                if (rawSku == null || rawSku.toString().isBlank()) {
                    return validationError("Item 'sku' is required.", "items.sku");
                }
                String sku = rawSku.toString().trim();

                Object rawQty = itemMap.get("quantity");
                Integer qty = parseInteger(rawQty);
                if (qty == null || qty < 1) {
                    return validationError("Item 'quantity' must be at least 1 for SKU '" + sku + "'.", "items.quantity");
                }

                Object rawExpected = itemMap.get("expected_unit_price") != null
                        ? itemMap.get("expected_unit_price") : itemMap.get("expectedUnitPrice");
                BigDecimal expectedUnitPrice = parseBigDecimal(rawExpected);
                if (rawExpected != null && (expectedUnitPrice == null || expectedUnitPrice.signum() < 0)) {
                    return validationError("Item 'expected_unit_price' must be a non-negative number for SKU '" + sku + "'.",
                            "items.expected_unit_price");
                }

                reqItems.add(new OrderItemRequest(sku, qty, expectedUnitPrice));
            }

            Object rawKey = arguments.get("idempotency_key") != null ? arguments.get("idempotency_key") : arguments.get("idempotencyKey");
            String idempotencyKey = rawKey != null ? rawKey.toString().trim() : null;
            if (idempotencyKey != null && idempotencyKey.length() > OrderService.MAX_IDEMPOTENCY_KEY_LENGTH) {
                return validationError("Parameter 'idempotency_key' must be at most "
                        + OrderService.MAX_IDEMPOTENCY_KEY_LENGTH + " characters.", "idempotency_key");
            }

            try {
                OrderService.Placement placement = orderService.place(customerId, reqItems, idempotencyKey);
                String confirmation = formatOrderPlaced(placement.order(), placement.replayed());
                return McpSchema.CallToolResult.builder().addTextContent(confirmation).isError(false).build();
            } catch (InsufficientStockException ex) {
                String errorMsg = String.format(
                        "Insufficient stock for product '%s'. Requested: %d, available: %d. Remedy: Reduce order quantity for '%s' to %d or fewer units.",
                        ex.getSku(), ex.getRequestedQuantity(), ex.getAvailableQuantity(),
                        ex.getSku(), ex.getAvailableQuantity()
                );
                Map<String, Object> problem = problem(InsufficientStockException.TYPE, "Insufficient Stock", 400, ex.getMessage());
                problem.put("sku", ex.getSku());
                problem.put("requested_quantity", ex.getRequestedQuantity());
                problem.put("available_quantity", ex.getAvailableQuantity());
                return problemResult(errorMsg, problem);
            } catch (PriceChangedException ex) {
                Map<String, Object> problem = problem(PriceChangedException.TYPE, "Price Changed", 409, ex.getMessage());
                List<Map<String, Object>> changedLines = new ArrayList<>();
                for (PriceChangedException.ChangedLine line : ex.getChangedLines()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sku", line.sku());
                    entry.put("expected_unit_price", line.expectedUnitPrice());
                    entry.put("current_unit_price", line.currentUnitPrice());
                    changedLines.add(entry);
                }
                problem.put("changed_lines", changedLines);
                return problemResult(ex.getMessage() + " No order was placed. Remedy: review the current prices and confirm again.", problem);
            } catch (ProductInactiveException ex) {
                Map<String, Object> problem = problem(ProductInactiveException.TYPE, "Product Inactive", 409, ex.getMessage());
                problem.put("inactive_skus", ex.getSkus());
                return problemResult(ex.getMessage() + " No order was placed. Remedy: remove them or choose alternatives.", problem);
            } catch (IdempotencyKeyReusedException ex) {
                return problemResult(ex.getMessage(),
                        problem(IdempotencyKeyReusedException.TYPE, "Idempotency Key Reused", 422, ex.getMessage()));
            } catch (ResourceNotFoundException ex) {
                return notFound(ex.getMessage());
            } catch (IllegalArgumentException ex) {
                return validationError(ex.getMessage(), null);
            } catch (Exception ex) {
                return internalError("placing the order", ex);
            }
        });
    }

    private static McpSchema.CallToolResult validationError(String message, @Nullable String invalidParam) {
        Map<String, Object> problem = problem(TYPE_VALIDATION_ERROR, "Validation Error", 400, message);
        if (invalidParam != null) {
            problem.put("invalid_param", invalidParam);
        }
        return problemResult(message, problem);
    }

    private static McpSchema.CallToolResult notFound(String message) {
        return problemResult(message, problem(TYPE_NOT_FOUND, "Resource Not Found", 404, message));
    }

    /** Logs the cause server-side and returns a generic problem, never the exception message (it may leak internals). */
    private static McpSchema.CallToolResult internalError(String activity, Exception ex) {
        log.error("place_order failed while {}", activity, ex);
        String message = "An internal error occurred while " + activity + ". No order was placed; please retry later.";
        return problemResult(message, problem(TYPE_INTERNAL_ERROR, "Internal Server Error", 500, message));
    }

    public McpSchema.CallToolResult listCustomerOrders(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_LIST_CUSTOMER_ORDERS, () -> {
            if (arguments == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Arguments are required.")
                        .isError(true)
                        .build();
            }

            Long customerId = parseLong(arguments.get("customer_id") != null ? arguments.get("customer_id") : arguments.get("customerId"));
            if (customerId == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'customer_id' is required.")
                        .isError(true)
                        .build();
            }

            OrderStatus orderStatus = null;
            Object rawStatus = arguments.get("status");
            if (rawStatus != null && !rawStatus.toString().isBlank()) {
                String statusStr = rawStatus.toString().trim().toUpperCase();
                try {
                    orderStatus = OrderStatus.valueOf(statusStr);
                } catch (IllegalArgumentException e) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Invalid status '" + rawStatus + "'. Allowed values: [PLACED, CONFIRMED, PARCELED, DELIVERING, DELIVERED, CANCELLED]")
                            .isError(true)
                            .build();
                }
            }

            int page = parseIntegerOrDefault(arguments.get("page"), 0);
            int size = parseIntegerOrDefault(arguments.get("size"), 20);
            if (page < 0) page = 0;
            if (size < 1) size = 20;

            try {
                Page<OrderResponse> ordersPage = orderService.searchOrders(
                        customerId,
                        orderStatus,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt"))
                );
                String formatted = formatOrderList(customerId, ordersPage);
                return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error searching orders: " + ex.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    public McpSchema.CallToolResult cancelOrder(Map<String, Object> arguments) {
        String orderNumber = getOrderNumber(arguments);
        return cancelOrder(orderNumber);
    }

    public McpSchema.CallToolResult cancelOrder(String orderNumber) {
        return executeWithSpan(TOOL_CANCEL_ORDER, () -> {
            if (orderNumber == null || orderNumber.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'order_number' is required.")
                        .isError(true)
                        .build();
            }
            try {
                Order order = orderService.cancelOrder(orderNumber.trim());
                String formatted = formatOrderCancelled(order);
                return McpSchema.CallToolResult.builder().addTextContent(formatted).isError(false).build();
            } catch (ResourceNotFoundException ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Order not found with order number: " + orderNumber.trim())
                        .isError(true)
                        .build();
            } catch (IllegalStateException ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Cannot cancel order: " + ex.getMessage())
                        .isError(true)
                        .build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error cancelling order '" + orderNumber + "': " + ex.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    public McpSchema.CallToolResult searchCustomersByName(Map<String, Object> arguments) {
        return executeWithSpan(TOOL_SEARCH_CUSTOMERS_BY_NAME, () -> {
            if (arguments == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Arguments are required.")
                        .isError(true)
                        .build();
            }

            Object rawName = arguments.get("name");
            String name = rawName != null ? rawName.toString().trim() : null;
            if (name == null || name.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Parameter 'name' is required and must not be blank.")
                        .isError(true)
                        .build();
            }

            int limit = parseIntegerOrDefault(arguments.get("limit"), 5);
            limit = Math.max(1, Math.min(limit, 20));

            try {
                List<CustomerSummaryResponse> customers = customerService.searchByName(name, limit);
                if (customers.isEmpty()) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("No customers found matching '" + name + "'.")
                            .isError(false)
                            .build();
                }

                List<String> lines = new ArrayList<>();
                lines.add("Found " + customers.size() + " customer(s) matching '" + name + "':");
                for (int i = 0; i < customers.size(); i++) {
                    CustomerSummaryResponse c = customers.get(i);
                    lines.add(String.format("%d. [ID: %d] %s | %s", i + 1, c.id(), c.fullName(), c.email()));
                }
                return McpSchema.CallToolResult.builder()
                        .addTextContent(String.join("\n", lines))
                        .isError(false)
                        .build();
            } catch (Exception ex) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error searching customers: " + ex.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    private String formatOrderStatus(Order order) {
        String customerName = order.getCustomer() != null ? order.getCustomer().getFullName() : "N/A";
        List<String> lines = new ArrayList<>();
        lines.add("Order Status for " + order.getOrderNumber() + ":");
        lines.add("- Status: " + order.getStatus());
        lines.add("- Customer: " + customerName);
        if (order.getAssignedPartner() != null) {
            lines.add("- Assigned Partner: " + order.getAssignedPartner());
        }
        lines.add("- Placed At: " + order.getPlacedAt());
        lines.add("- Total Amount: $" + order.getTotalAmount());

        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            lines.add("- Items: None");
        } else {
            lines.add(String.format("- Items (%d):", items.size()));
            for (OrderItem item : items) {
                String sku = item.getProduct() != null ? item.getProduct().getSku() : "N/A";
                String name = item.getProduct() != null ? item.getProduct().getName() : "Item";
                lines.add(String.format("  * [%s] %s x %d @ $%s", sku, name, item.getQuantity(), item.getUnitPrice()));
            }
        }

        return String.join("\n", lines);
    }

    /**
     * RFC 7807-shaped map carried as {@code structuredContent} of a failed {@code place_order} call, so an
     * orchestrator can branch on {@code type} (price-changed, out-of-stock, idempotency-key-reused) like a REST client.
     */
    private static Map<String, Object> problem(String type, String title, int status, String detail) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        return problem;
    }

    private static McpSchema.CallToolResult problemResult(String text, Map<String, Object> problem) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(problem)
                .isError(true)
                .build();
    }

    private String formatOrderPlaced(Order order, boolean replayed) {
        String customerName = order.getCustomer() != null ? order.getCustomer().getFullName() : "Customer";
        List<String> lines = new ArrayList<>();
        lines.add(replayed
                ? "Order already placed for this idempotency key; no new order was created."
                : "Order successfully placed!");
        lines.add("- Order Number: " + order.getOrderNumber());
        lines.add("- Status: " + order.getStatus());
        lines.add("- Customer: " + customerName);
        lines.add("- Total Amount: $" + order.getTotalAmount());
        lines.add("- Placed At: " + order.getPlacedAt());

        List<OrderItem> items = order.getItems();
        if (items != null && !items.isEmpty()) {
            lines.add(String.format("- Items (%d):", items.size()));
            for (OrderItem item : items) {
                String sku = item.getProduct() != null ? item.getProduct().getSku() : "N/A";
                String name = item.getProduct() != null ? item.getProduct().getName() : "Item";
                lines.add(String.format("  * [%s] %s x %d @ $%s", sku, name, item.getQuantity(), item.getUnitPrice()));
            }
        }
        return String.join("\n", lines);
    }

    private String formatOrderList(Long customerId, Page<OrderResponse> page) {
        if (page.isEmpty()) {
            return "No orders found for customer ID: " + customerId;
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Found %d order(s) for customer ID %d (showing page %d of %d):",
                page.getTotalElements(), customerId, page.getNumber(), page.getTotalPages()));

        for (OrderResponse o : page.getContent()) {
            int itemCount = o.items() != null ? o.items().size() : 0;
            lines.add(String.format("- [%s] Status: %s, Total: $%s, Items: %d, Placed: %s",
                    o.orderNumber(), o.status(), o.totalAmount(), itemCount, o.placedAt()));
        }
        return String.join("\n", lines);
    }

    private String formatOrderCancelled(Order order) {
        return String.format(
                "Order %s has been successfully cancelled.\n- Status: %s\n- Updated At: %s",
                order.getOrderNumber(),
                order.getStatus(),
                order.getUpdatedAt()
        );
    }

    private String getOrderNumber(Map<String, Object> arguments) {
        if (arguments == null) return null;
        Object val = arguments.get("order_number");
        if (val == null) {
            val = arguments.get("orderNumber");
        }
        return val != null ? val.toString().trim() : null;
    }

    private Long parseLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(Object val) {
        if (val == null) return null;
        try {
            return new BigDecimal(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntegerOrDefault(Object val, int defaultVal) {
        Integer parsed = parseInteger(val);
        return parsed != null ? parsed : defaultVal;
    }
}
