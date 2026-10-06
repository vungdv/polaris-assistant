package vn.danang.polaris.web.exception;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A confirm or cancel request on an order draft was refused; carries the RFC 7807 problem to return as is.
 * Either the Assistant's own draft gate refused it (draft not found, cancelled, invalidated, already
 * confirmed), or Order Management rejected {@code place_order} and its problem is passed through.
 */
public class DraftProblemException extends RuntimeException {

    static final String TYPE_BASE = "https://polaris.local/errors/";
    public static final String TYPE_DRAFT_NOT_FOUND = TYPE_BASE + "draft-not-found";
    public static final String TYPE_DRAFT_CANCELLED = TYPE_BASE + "draft-cancelled";
    public static final String TYPE_DRAFT_INVALIDATED = TYPE_BASE + "draft-invalidated";
    public static final String TYPE_DRAFT_ALREADY_CONFIRMED = TYPE_BASE + "draft-already-confirmed";
    public static final String TYPE_ORDER_PLACEMENT_FAILED = TYPE_BASE + "order-placement-failed";
    public static final String TYPE_UNAUTHORIZED = TYPE_BASE + "unauthorized";
    public static final String TYPE_VALIDATION_ERROR = TYPE_BASE + "validation-error";

    private final transient ProblemDetail problem;

    public DraftProblemException(ProblemDetail problem) {
        super(Objects.requireNonNull(problem, "problem must not be null").getDetail());
        this.problem = problem;
    }

    public ProblemDetail getProblem() {
        return problem;
    }

    public static DraftProblemException draftNotFound(String draftId) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, TYPE_DRAFT_NOT_FOUND, "Draft Not Found",
                "Order draft " + draftId + " does not exist in this session.");
        problem.setProperty("remedy", "Stage a new order draft in this chat session.");
        return new DraftProblemException(problem);
    }

    public static DraftProblemException draftCancelled(String draftId) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, TYPE_DRAFT_CANCELLED, "Draft Cancelled",
                "Order draft " + draftId + " was cancelled or replaced by a newer draft and cannot be confirmed.");
        problem.setProperty("draftId", draftId);
        problem.setProperty("remedy", "Confirm the latest draft card, or ask the assistant to stage the order again.");
        problem.setProperty("actions", List.of(Map.of("label", "Refresh Draft", "action", "refresh_draft")));
        return new DraftProblemException(problem);
    }

    public static DraftProblemException draftInvalidated(String draftId) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, TYPE_DRAFT_INVALIDATED, "Draft Invalidated",
                "Order draft " + draftId + " was rejected by Order Management (price or stock changed) and cannot be confirmed.");
        problem.setProperty("draftId", draftId);
        problem.setProperty("remedy", "Ask the assistant to stage the order again at the current prices and stock.");
        problem.setProperty("actions", List.of(Map.of("label", "Refresh Draft", "action", "refresh_draft")));
        return new DraftProblemException(problem);
    }

    public static DraftProblemException draftAlreadyConfirmed(String draftId, String orderNumber) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, TYPE_DRAFT_ALREADY_CONFIRMED, "Draft Already Confirmed",
                "Order draft " + draftId + " was already confirmed as order " + orderNumber + "; a placed order is not cancelled here.");
        problem.setProperty("draftId", draftId);
        problem.setProperty("orderNumber", orderNumber);
        problem.setProperty("remedy", "Placed orders are cancelled through the order cancellation process.");
        return new DraftProblemException(problem);
    }

    public static DraftProblemException unauthenticated() {
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, TYPE_UNAUTHORIZED, "Unauthorized",
                "Sign in to confirm or cancel an order draft.");
        problem.setProperty("remedy", "Send the request with a valid Bearer access token.");
        return new DraftProblemException(problem);
    }

    public static DraftProblemException invalidIdempotencyKey(String detail) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION_ERROR, "Validation Error", detail);
        problem.setProperty("invalid_param", "Idempotency-Key");
        problem.setProperty("location", "header");
        problem.setProperty("remedy", "Send a unique Idempotency-Key header (e.g. a UUIDv4) of at most 100 characters.");
        return new DraftProblemException(problem);
    }

    /** Order Management could not be reached or failed; nothing is known to be ordered and the draft stays open. */
    public static DraftProblemException placementFailed(String draftId, String detail) {
        ProblemDetail problem = problem(HttpStatus.BAD_GATEWAY, TYPE_ORDER_PLACEMENT_FAILED, "Order Placement Failed", detail);
        problem.setProperty("draftId", draftId);
        problem.setProperty("remedy", "Retry the confirmation: it is idempotent, so a retry never places a second order.");
        return new DraftProblemException(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        return problem;
    }
}
