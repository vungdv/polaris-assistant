package vn.danang.polaris.assistant.tools;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.ProductListCard;

/**
 * Unit tests for {@link RemoteToolWidgets}: which remote tool results become cards, and that a card holds
 * exactly the tool's structured data.
 */
class RemoteToolWidgetsTest {

    private static final ToolCall SEARCH = new ToolCall("search_available_products", Map.of());

    private static Map<String, Object> product(String sku, BigDecimal price) {
        return Map.of("sku", sku, "name", "Name " + sku, "category", "Chargers",
                "price", price, "stockQuantity", 5, "available", true);
    }

    private static CallToolResult mcp(Object structured) {
        return new CallToolResult(List.of(TextContent.builder("text for the model").build()), false, structured, Map.of());
    }

    @Test
    @DisplayName("search with products yields a PRODUCT_LIST card with the exact BigDecimal price; the model text is unchanged")
    void search_withProducts_yieldsProductList() {
        Map<String, Object> structured = Map.of("totalElements", 1,
                "products", List.of(product("NG-CHARGER-01", new BigDecimal("24.90"))));

        ToolResult result = RemoteToolWidgets.attach(ToolResult.success(SEARCH, "text for the model"), mcp(structured));

        assertThat(result.result()).isEqualTo("text for the model");
        assertThat(result.widget().type()).isEqualTo(ChatWidget.PRODUCT_LIST);
        ProductListCard card = (ProductListCard) result.widget().payload();
        assertThat(card.products()).singleElement().satisfies(p -> {
            assertThat(p.sku()).isEqualTo("NG-CHARGER-01");
            assertThat(p.name()).isEqualTo("Name NG-CHARGER-01");
            assertThat(p.category()).isEqualTo("Chargers");
            assertThat(p.price()).isEqualTo(new BigDecimal("24.90"));
            assertThat(p.stockQuantity()).isEqualTo(5);
            assertThat(p.available()).isTrue();
        });
    }

    @Test
    @DisplayName("empty search, missing structuredContent or unreadable structuredContent yield no card")
    void search_withoutProducts_yieldsNoCard() {
        ToolResult success = ToolResult.success(SEARCH, "t");
        assertThat(RemoteToolWidgets.attach(success, mcp(Map.of("totalElements", 0, "products", List.of()))).widget()).isNull();
        assertThat(RemoteToolWidgets.attach(success, mcp(null)).widget()).isNull();
        assertThat(RemoteToolWidgets.attach(success, mcp(Map.of("products", "not-a-list"))).widget()).isNull();
    }

    @Test
    @DisplayName("other tools, including get_product_by_sku, yield no card")
    void otherTools_yieldNoCard() {
        ToolCall bySku = new ToolCall("get_product_by_sku", Map.of("sku", "NG-CHARGER-01"));
        assertThat(RemoteToolWidgets.attach(ToolResult.success(bySku, "t"),
                mcp(product("NG-CHARGER-01", new BigDecimal("24.90")))).widget()).isNull();
    }

    @Test
    @DisplayName("DefaultToolManager attaches the card to a successful remote search and none to a failed one")
    void defaultToolManager_attachesCardToRemoteSearch() {
        PolarisMcpClient client = mock(PolarisMcpClient.class);
        when(client.callTool(eq("search_available_products"),
                        eq(Map.of("query", "charger"))))
                .thenReturn(mcp(Map.of("products", List.of(product("NG-CHARGER-01", new BigDecimal("24.90"))))));
        when(client.callTool(eq("search_available_products"),
                        eq(Map.of("page", -1))))
                .thenReturn(new CallToolResult(List.of(TextContent.builder("Error").build()), true, null, Map.of()));
        DefaultToolManager manager = new DefaultToolManager(client, Runnable::run, List.of());

        List<ToolResult> results = manager.handleToolCalls(List.of(
                new ToolCall("search_available_products", Map.of("query", "charger")),
                new ToolCall("search_available_products", Map.of("page", -1))), null);

        assertThat(results.get(0).widget()).isNotNull().extracting(ChatWidget::type).isEqualTo(ChatWidget.PRODUCT_LIST);
        assertThat(results.get(1).isError()).isTrue();
        assertThat(results.get(1).widget()).isNull();
    }
}
