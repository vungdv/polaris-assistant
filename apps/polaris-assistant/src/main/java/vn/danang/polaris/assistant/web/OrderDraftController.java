package vn.danang.polaris.assistant.web;

import java.net.URI;
import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.dto.OrderDraftStatusResponse;
import vn.danang.polaris.web.exception.DraftProblemException;
import vn.danang.polaris.assistant.service.OrderDraftConfirmationService;

/**
 * The shopper's buttons on an {@code ORDER_DRAFT} card (ADR-0004 §1.B): "Submit Order" and "Cancel". These are
 * the only way a draft becomes an order; the model cannot call them.
 */
@RestController
@RequestMapping("/api/v1/assistant/sessions/{sessionId}/drafts/{draftId}")
@Tag(name = "Assistant Order Drafts", description = "Confirm or cancel an order draft staged in a chat session")
@SecurityRequirement(name = "keycloak-auth2-codeflow")
public class OrderDraftController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final OrderDraftConfirmationService confirmationService;

    public OrderDraftController(OrderDraftConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm an order draft (Submit Order)",
            description = "Places the draft's order in Order Management at the staged prices. Idempotent: confirming a "
                    + "confirmed draft again returns the same order (200) and never places a second one.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order placed; Location points to the order; body carries the ORDER_CONFIRMED card",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatMessageResponse.class))),
        @ApiResponse(responseCode = "200", description = "Draft was already confirmed; the original order is returned",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ChatMessageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Missing or invalid Idempotency-Key, or out of stock (draft invalidated)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Not signed in",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Session belongs to another user",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Draft not found in this session",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Draft expired, cancelled or invalidated; or price changed / product inactive (draft invalidated)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "502", description = "Order Management did not confirm the order; the draft is still open and a retry is safe",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ChatMessageResponse> confirm(
            @PathVariable String sessionId,
            @PathVariable String draftId,
            @Parameter(in = ParameterIn.HEADER, name = IDEMPOTENCY_KEY_HEADER, required = true,
                    description = "Unique key of this confirmation request (e.g. a UUIDv4), at most 100 characters")
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Principal principal) {
        String userId = callerId(principal);
        String key = requireIdempotencyKey(idempotencyKey);
        OrderDraftConfirmationService.Confirmation confirmation = confirmationService.confirm(sessionId, userId, draftId, key);
        URI location = URI.create("/api/v1/orders/" + confirmation.orderNumber());
        return ResponseEntity.status(confirmation.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(location)
                .body(confirmation.response());
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel an order draft",
            description = "Declines the draft so it can never become an order. Idempotent on a draft that is already cancelled, expired or invalidated.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Draft cancelled (or already unable to become an order)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderDraftStatusResponse.class))),
        @ApiResponse(responseCode = "401", description = "Not signed in",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Session belongs to another user",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Draft not found in this session",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "409", description = "Draft was already confirmed as an order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<OrderDraftStatusResponse> cancel(
            @PathVariable String sessionId,
            @PathVariable String draftId,
            Principal principal) {
        return ResponseEntity.ok(OrderDraftStatusResponse.from(confirmationService.cancel(sessionId, callerId(principal), draftId)));
    }

    /** {@code /api/v1/assistant/**} admits anonymous callers (chat), so these endpoints refuse them here. */
    private static String callerId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw DraftProblemException.unauthenticated();
        }
        return principal.getName();
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw DraftProblemException.invalidIdempotencyKey("The Idempotency-Key header is required to confirm an order draft.");
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw DraftProblemException.invalidIdempotencyKey(
                    "The Idempotency-Key header must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
        }
        return key;
    }
}
