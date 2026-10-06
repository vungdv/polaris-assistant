package vn.danang.polaris.mcp;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;

import io.modelcontextprotocol.spec.McpSchema;
import vn.danang.polaris.order.service.CustomerService;
import vn.danang.polaris.order.service.OrderService;

/** Unexpected failures of {@code place_order} surface as a generic, structured internal-error problem. */
@ExtendWith(MockitoExtension.class)
class OrderMcpToolsPlaceOrderErrorTest {

    @Mock
    private OrderService orderService;
    @Mock
    private CustomerService customerService;

    private final Map<String, Object> args = Map.of(
            "customer_id", 1, "items", List.of(Map.of("sku", "SKU-01", "quantity", 1)));
    private final TestingAuthenticationToken staff =
            new TestingAuthenticationToken("staff", null, "ROLE_ADMIN", "PERM_order.write");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    @DisplayName("unexpected exception returns internal-error without leaking the exception message")
    void placeOrder_unexpectedException_genericInternalError() {
        when(orderService.place(anyLong(), anyList(), any()))
                .thenThrow(new RuntimeException("JDBC url=jdbc:postgresql://db password=s3cret"));

        McpSchema.CallToolResult result = new OrderMcpTools(orderService, customerService).placeOrder(args, staff);

        assertThat(result.isError()).isTrue();
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).doesNotContain("s3cret").doesNotContain("jdbc");
        Map<String, Object> problem = structured(result);
        assertThat(problem).containsEntry("type", "https://polaris.local/errors/internal-error")
                .containsEntry("status", 500);
        assertThat(problem.get("detail").toString()).doesNotContain("s3cret");
    }

    @Test
    @DisplayName("IllegalArgumentException from the service is a structured validation problem")
    void placeOrder_illegalArgument_validationProblem() {
        when(orderService.place(anyLong(), anyList(), any()))
                .thenThrow(new IllegalArgumentException("Total quantity for SKU 'SKU-01' is too large"));

        McpSchema.CallToolResult result = new OrderMcpTools(orderService, customerService).placeOrder(args, staff);

        assertThat(structured(result)).containsEntry("type", "https://polaris.local/errors/validation-error")
                .containsEntry("status", 400)
                .containsEntry("detail", "Total quantity for SKU 'SKU-01' is too large");
    }
}
