package vn.danang.polaris.order.web.controller;

import java.net.URI;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.order.dto.ClaimOrderRequest;
import vn.danang.polaris.order.dto.CreateOrderRequest;
import vn.danang.polaris.order.dto.OrderResponse;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.security.CallerIdentity;
import vn.danang.polaris.order.service.CustomerService;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.validator.PageableValidator;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lifecycle management, placement, history search, and status tracking")
public class OrderController {

    private final OrderService orderService;
    private final CustomerService customerService;

    public OrderController(OrderService orderService, CustomerService customerService) {
        this.orderService = orderService;
        this.customerService = customerService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_order.write')")
    @Operation(
        summary = "Place a new order",
        description = "Create and submit a new multi-item purchase order with live stock deduction and idempotency support. "
            + "Staff (staff, admin, purchase-management roles) must supply customerId. Any other caller always "
            + "orders for the customer linked to their own identity; customerId may be omitted, and a different one is rejected with 403. "
            + "Lines repeating a SKU are merged. A line's optional expectedUnitPrice is checked against the live price under row lock; "
            + "any difference rejects the whole order with 409 price-changed. Retrying with the same Idempotency-Key returns the "
            + "original order with 200, also when two retries race."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "201",
            description = "Order successfully created",
            headers = @Header(name = HttpHeaders.LOCATION, description = "URI of the created order resource", schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))
        ),
        @ApiResponse(
            responseCode = "200",
            description = "Idempotent replay: an order already exists for this Idempotency-Key and customer; it is returned unchanged",
            headers = @Header(name = HttpHeaders.CONTENT_LOCATION, description = "URI of the existing order resource", schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))
        ),
        @ApiResponse(
            responseCode = "409",
            description = "price-changed: the live unit price of at least one line differs from its expectedUnitPrice; "
                + "changed_lines lists them. No order is created and no stock is deducted",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "422",
            description = "idempotency-key-reused: the Idempotency-Key already identifies an order of a different customer",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Validation failure, out-of-stock inventory, or invalid input parameters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(
            responseCode = "403",
            description = "Missing order.write permission, or a shopper tried to order for a customer other than their own",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Customer ID or product SKU does not exist, or no customer is linked to the authenticated shopper",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
        )
    })
    public ResponseEntity<OrderResponse> placeOrder(
            @Parameter(description = "Optional idempotency key header to prevent duplicate orders during retries")
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody CreateOrderRequest request,
            @Parameter(hidden = true) Authentication authentication) {

        String idempotencyKey = (headerIdempotencyKey != null && !headerIdempotencyKey.isBlank())
                ? headerIdempotencyKey.trim()
                : (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                        ? request.idempotencyKey().trim()
                        : null);

        Long customerId = customerService.resolveOrderingCustomerId(CallerIdentity.from(authentication), request.customerId());
        OrderService.Placement placement = orderService.place(customerId, request.items(), idempotencyKey);
        Order order = placement.order();
        URI location = URI.create("/api/v1/orders/" + order.getOrderNumber());
        if (placement.replayed()) {
            // Idempotent replay: nothing was created by this request, so 200 with the original order
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_LOCATION, location.toString()).body(OrderResponse.from(order));
        }
        return ResponseEntity.created(location).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderNumber}")
    @PreAuthorize("hasAuthority('PERM_order.read')")
    @Operation(summary = "Get order details", description = "Retrieve full order details, line items, and fulfillment status by business order number.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order details successfully retrieved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "403", description = "Missing permission, or a shopper tried to access another customer's order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> getOrder(
            @Parameter(description = "Unique business order number (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber,
            @Parameter(hidden = true) Authentication authentication) {
        Order order = orderService.getOrderStatus(orderNumber);
        customerService.assertCustomerAccess(CallerIdentity.from(authentication), order.getCustomer().getId());
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping("/{orderNumber}/status")
    @PreAuthorize("hasAuthority('PERM_order.read')")
    @Operation(summary = "Get order status", description = "Retrieve order details and status by business order number (backwards compatible endpoint).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order status successfully retrieved",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "403", description = "Missing permission, or a shopper tried to access another customer's order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> getStatus(
            @Parameter(description = "Unique business order number (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber,
            @Parameter(hidden = true) Authentication authentication) {
        Order order = orderService.getOrderStatus(orderNumber);
        customerService.assertCustomerAccess(CallerIdentity.from(authentication), order.getCustomer().getId());
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_order.read')")
    @Operation(
        summary = "Search customer orders",
        description = "Query customer order history with optional filtering by customer ID and order status, supporting pagination and sorting. "
            + "Callers without a staff role only see their own linked customer's orders; naming another customerId is rejected with 403."
    )
    @Parameters({
        @Parameter(name = "customerId", description = "Filter by customer ID (must be > 0)", schema = @Schema(type = "integer", minimum = "1")),
        @Parameter(name = "status", description = "Filter by order lifecycle status", schema = @Schema(implementation = OrderStatus.class)),
        @Parameter(name = "page", description = "Zero-based page index (0..10000)", schema = @Schema(type = "integer", defaultValue = "0", minimum = "0", maximum = "10000")),
        @Parameter(name = "size", description = "The size of the page to be returned (1..100)", schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "sort", description = "Sorting criteria in the format: property(,asc|desc). Allowed properties: [id, orderNumber, status, totalAmount, placedAt, updatedAt]", example = "placedAt,desc", schema = @Schema(type = "string", defaultValue = "placedAt,desc"))
    })
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of orders matching search criteria successfully retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination, sorting, or customer ID parameter",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "403", description = "Missing permission, or a shopper named another customer",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Page<OrderResponse>> searchOrders(
            @Parameter(description = "Customer ID filter")
            @RequestParam(required = false) Long customerId,
            @Parameter(description = "Order status filter")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(hidden = true)
            @PageableDefault(page = 0, size = 20, sort = "placedAt", direction = Sort.Direction.DESC) Pageable pageable,
            @Parameter(hidden = true) Authentication authentication) {

        if (customerId != null && customerId <= 0) {
            throw new IllegalArgumentException("Customer ID must be greater than 0. Received: " + customerId);
        }

        customerId = customerService.resolveCustomerScope(CallerIdentity.from(authentication), customerId);
        Pageable sanitized = PageableValidator.validateAndSanitizeOrder(pageable);
        Page<OrderResponse> result = orderService.searchOrders(customerId, status, sanitized);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{orderNumber}/cancel")
    @PreAuthorize("hasAuthority('PERM_order.write')")
    @Operation(summary = "Cancel order", description = "Cancel an order in PLACED or CONFIRMED status, restoring inventory stock.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order successfully cancelled and stock restored",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "403", description = "Missing permission, or a shopper tried to access another customer's order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Order is in a non-cancellable state (e.g. PARCELED, DELIVERING, DELIVERED, CANCELLED)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> cancel(
            @Parameter(description = "Unique business order number to cancel (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber,
            @Parameter(hidden = true) Authentication authentication) {
        CallerIdentity caller = CallerIdentity.from(authentication);
        if (caller == null || !caller.staff()) {
            // Ownership check before any state change; staff may cancel any order
            customerService.assertCustomerAccess(caller, orderService.getOrderStatus(orderNumber).getCustomer().getId());
        }
        Order order = orderService.cancelOrder(orderNumber);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @PostMapping("/{orderNumber}/claim")
    @PreAuthorize("hasAuthority('PERM_order.fulfil')")
    @Operation(summary = "Claim order",
        description = "First-wins claim by a fulfilment partner: only a PLACED order can be claimed. Exactly one concurrent claim "
            + "succeeds (status becomes CONFIRMED, partner recorded); every other claim, including a repeat by the winner, gets 409 "
            + "without revealing the winner. POST because it is a conditional state-transition command.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order claimed; status is CONFIRMED and assignedPartner is set",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "partnerId is missing, blank, or longer than 64 characters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "403", description = "Missing order.fulfil permission",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Order not found with the specified order number",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "order-not-claimable: the order is no longer PLACED (already claimed or cancelled); the body keeps status 409 and carries the order state as orderStatus only",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderResponse> claim(
            @Parameter(description = "Unique business order number to claim (e.g. 'ORD-1001')", required = true)
            @PathVariable String orderNumber,
            @Valid @RequestBody ClaimOrderRequest request) {
        return ResponseEntity.ok(OrderResponse.from(orderService.claimOrder(orderNumber, request.partnerId().trim())));
    }
}
