package vn.danang.polaris.assistant.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.PriceChangedException;
import vn.danang.polaris.web.exception.ProductInactiveException;

/**
 * What Order Management's {@code place_order} MCP tool answered, read the way a REST client reads a response:
 * a failure is classified by the RFC 7807 {@code type} in {@code structuredContent}, never by its status or text.
 * <ul>
 *   <li>{@link Placed}: a new order, or the replay of the order already placed for the key
 *       ({@value #REPLAY_PREFIX}...).</li>
 *   <li>{@link Rejected}: Order Management refused with a problem. {@link Rejected#invalidatesDraft()} is true for
 *       the types that mean the draft's snapshot can no longer be ordered (price changed, out of stock, product
 *       inactive).</li>
 *   <li>{@link Failed}: no usable answer (transport error, or a failure without a problem).</li>
 * </ul>
 */
public sealed interface PlaceOrderOutcome {

    String REPLAY_PREFIX = "Order already placed for this idempotency key";

    /** Problem types that invalidate the draft: its snapshot cannot be ordered as it stands. */
    Set<String> DRAFT_INVALIDATING_TYPES = Set.of(
            PriceChangedException.TYPE, InsufficientStockException.TYPE, ProductInactiveException.TYPE);

    Pattern ORDER_NUMBER = Pattern.compile("Order Number:\\s*(\\S+)");

    record Placed(String orderNumber, boolean replayed) implements PlaceOrderOutcome {}

    /**
     * @param problem Order Management's problem as returned in {@code structuredContent}
     */
    record Rejected(Map<String, Object> problem) implements PlaceOrderOutcome {

        public String type() {
            Object type = problem.get("type");
            return type != null ? type.toString() : null;
        }

        public boolean invalidatesDraft() {
            return DRAFT_INVALIDATING_TYPES.contains(type());
        }

        /** The same problem as an RFC 7807 response: type, title, status, detail and every extension member. */
        public ProblemDetail toProblemDetail() {
            int status = problem.get("status") instanceof Number n ? n.intValue() : HttpStatus.BAD_GATEWAY.value();
            ProblemDetail detail = ProblemDetail.forStatus(status);
            detail.setType(URI.create(type()));
            if (problem.get("title") != null) {
                detail.setTitle(problem.get("title").toString());
            }
            if (problem.get("detail") != null) {
                detail.setDetail(problem.get("detail").toString());
            }
            problem.forEach((key, value) -> {
                if (!List.of("type", "title", "status", "detail", "instance").contains(key)) {
                    detail.setProperty(key, value);
                }
            });
            return detail;
        }
    }

    /**
     * @param reason what went wrong, for logs only (may carry upstream internals)
     */
    record Failed(String reason) implements PlaceOrderOutcome {}

    static PlaceOrderOutcome from(CallToolResult result) {
        if (result == null) {
            return new Failed("place_order returned no result");
        }
        String text = text(result);
        if (!Boolean.TRUE.equals(result.isError())) {
            Matcher matcher = ORDER_NUMBER.matcher(text);
            if (matcher.find()) {
                return new Placed(matcher.group(1), text.startsWith(REPLAY_PREFIX));
            }
            return new Failed("place_order succeeded without an order number: " + text);
        }
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map && map.get("type") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> problem = (Map<String, Object>) map;
            return new Rejected(problem);
        }
        return new Failed("place_order failed without a problem: " + text);
    }

    private static String text(CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
