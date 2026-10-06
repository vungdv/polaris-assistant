package vn.danang.polaris.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import vn.danang.polaris.assistant.tools.FakeOrderManagementMcp;

/**
 * Reading {@code place_order} results the way OrderMcpTools publishes them: branch on the problem {@code type},
 * never on status or text.
 */
class PlaceOrderOutcomeTest {

    private static CallToolResult text(String text, boolean error) {
        return new CallToolResult(List.of(TextContent.builder(text).build()), error, null, Map.of());
    }

    @Test
    @DisplayName("Given a new order, then Placed with the order number, not replayed")
    void new_order_is_placed() {
        PlaceOrderOutcome outcome = PlaceOrderOutcome.from(text(
                "Order successfully placed!\n- Order Number: ORD-000123\n- Status: PLACED", false));

        assertThat(outcome).isEqualTo(new PlaceOrderOutcome.Placed("ORD-000123", false));
    }

    @Test
    @DisplayName("Given the replay text for a used key, then Placed with the original order number, replayed")
    void replay_is_success() {
        PlaceOrderOutcome outcome = PlaceOrderOutcome.from(text(
                "Order already placed for this idempotency key; no new order was created.\n- Order Number: ORD-000123", false));

        assertThat(outcome).isEqualTo(new PlaceOrderOutcome.Placed("ORD-000123", true));
    }

    @Test
    @DisplayName("Given success without an order number (e.g. a customer disambiguation list), then Failed")
    void success_without_order_number_is_failed() {
        assertThat(PlaceOrderOutcome.from(text("Multiple customers found for 'Al'.", false)))
                .isInstanceOf(PlaceOrderOutcome.Failed.class);
    }

    @Test
    @DisplayName("Given no result, an error without a problem, or an empty problem, then Failed")
    void errors_without_problem_are_failed() {
        assertThat(PlaceOrderOutcome.from(null)).isInstanceOf(PlaceOrderOutcome.Failed.class);
        assertThat(PlaceOrderOutcome.from(text("Error executing tool place_order: HTTP 502", true)))
                .isInstanceOf(PlaceOrderOutcome.Failed.class);
        assertThat(PlaceOrderOutcome.from(new CallToolResult(List.of(), true, Map.of(), Map.of())))
                .isInstanceOf(PlaceOrderOutcome.Failed.class);
    }

    @Test
    @DisplayName("Given price-changed, out-of-stock or product-inactive, then Rejected and the draft is invalidated")
    void snapshot_problems_invalidate_the_draft() {
        for (String type : List.of("price-changed", "out-of-stock", "product-inactive")) {
            PlaceOrderOutcome outcome = PlaceOrderOutcome.from(
                    FakeOrderManagementMcp.problem(type, 409, "T", "D", Map.of()));

            assertThat(outcome).isInstanceOfSatisfying(PlaceOrderOutcome.Rejected.class,
                    rejected -> assertThat(rejected.invalidatesDraft()).as(type).isTrue());
        }
    }

    @Test
    @DisplayName("Given idempotency-key-reused, not-found, forbidden, validation or internal errors, then Rejected without invalidating")
    void other_problems_do_not_invalidate() {
        for (String type : List.of("idempotency-key-reused", "not-found", "forbidden", "validation-error", "internal-error")) {
            PlaceOrderOutcome outcome = PlaceOrderOutcome.from(FakeOrderManagementMcp.problem(type, 422, "T", "D", Map.of()));

            assertThat(outcome).isInstanceOfSatisfying(PlaceOrderOutcome.Rejected.class,
                    rejected -> assertThat(rejected.invalidatesDraft()).as(type).isFalse());
        }
    }

    @Test
    @DisplayName("Given a problem, then the RFC 7807 view keeps type, title, status, detail and every extension member")
    void problem_is_passed_through() {
        PlaceOrderOutcome.Rejected rejected = (PlaceOrderOutcome.Rejected) PlaceOrderOutcome.from(
                FakeOrderManagementMcp.problem("price-changed", 409, "Price Changed", "The price changed for 1 item.",
                        Map.of("changed_lines", List.of(Map.of("sku", "NG-CHARGER-01")))));

        ProblemDetail problem = rejected.toProblemDetail();

        assertThat(problem.getType()).hasToString("https://polaris.local/errors/price-changed");
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Price Changed");
        assertThat(problem.getDetail()).isEqualTo("The price changed for 1 item.");
        assertThat(problem.getProperties()).containsKey("changed_lines").doesNotContainKeys("type", "status");
    }
}
