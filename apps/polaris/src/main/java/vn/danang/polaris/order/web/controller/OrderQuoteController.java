package vn.danang.polaris.order.web.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.dto.QuoteResponse;
import vn.danang.polaris.order.service.OrderQuoteService;

/**
 * Read-only order quote ({@code O_Verify}). Kept apart from {@link OrderController} because it
 * never changes state and needs no customer.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order lifecycle management, placement, history search, and status tracking")
public class OrderQuoteController {

    private final OrderQuoteService orderQuoteService;

    public OrderQuoteController(OrderQuoteService orderQuoteService) {
        this.orderQuoteService = orderQuoteService;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('PERM_order.read')")
    @Operation(
        summary = "Quote an order (read-only)",
        description = "Verify live stock and price for each line of a prospective order. Reads the database directly, "
                + "takes no locks, reserves nothing and writes nothing, so it is safe to repeat. Lines that cannot be "
                + "ordered carry a problem code (not_found, inactive, insufficient_stock) instead of failing the request."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Quote computed (check 'orderable' and per-line 'problem')",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QuoteResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failure (empty items, blank SKU, quantity < 1)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 Bearer token"),
        @ApiResponse(responseCode = "403", description = "Caller lacks the order.read permission",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<QuoteResponse> quote(@Valid @RequestBody QuoteRequest request) {
        return ResponseEntity.ok(orderQuoteService.quote(request.items()));
    }
}
