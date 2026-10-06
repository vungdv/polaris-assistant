package vn.danang.polaris.assistant.tools;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.ProductListCard;

/**
 * Turns the {@code structuredContent} of a successful remote (Polaris MCP) tool call into a card for the
 * chat client. The card is built only from the tool's structured data, never from its text or the model's
 * reply (FR-1); the text result the model reads is left unchanged.
 * <p>
 * Cards: {@code search_available_products} → {@value ChatWidget#PRODUCT_LIST} when it found at least one
 * product. An empty search, a tool error or a result without {@code structuredContent} yields no card (the
 * reply alone tells the shopper nothing matched). {@code get_product_by_sku} gets no card: it answers a
 * question about one known product, and its result is fed to the model only.
 */
final class RemoteToolWidgets {

    static final String SEARCH_AVAILABLE_PRODUCTS = "search_available_products";

    private static final Logger log = LoggerFactory.getLogger(RemoteToolWidgets.class);

    // Money stays BigDecimal end to end (the MCP client already parses floats as BigDecimal).
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private RemoteToolWidgets() {
    }

    /**
     * @param success   the successful result built from {@code mcpResult}
     * @param mcpResult the raw MCP result carrying {@code structuredContent}
     * @return {@code success}, with a card attached when the tool's structured result has one
     */
    static ToolResult attach(ToolResult success, CallToolResult mcpResult) {
        if (!success.isSuccess() || mcpResult == null || mcpResult.structuredContent() == null) {
            return success;
        }
        if (SEARCH_AVAILABLE_PRODUCTS.equals(success.toolCall().name())) {
            ChatWidget card = productList(mcpResult.structuredContent());
            return card != null ? success.withWidget(card) : success;
        }
        return success;
    }

    private static ChatWidget productList(Object structuredContent) {
        SearchContent content;
        try {
            content = MAPPER.convertValue(structuredContent, SearchContent.class);
        } catch (IllegalArgumentException ex) {
            // The model still gets the text result; only the card is dropped.
            log.warn("Unreadable structuredContent from tool '{}', no PRODUCT_LIST card: {}",
                    SEARCH_AVAILABLE_PRODUCTS, ex.getMessage());
            return null;
        }
        List<ProductListCard.Product> products = content == null || content.products() == null
                ? List.of()
                : content.products().stream().filter(Objects::nonNull).filter(p -> p.sku() != null).toList();
        return products.isEmpty() ? null : new ProductListCard(products).toWidget();
    }

    /** The part of the {@code search_available_products} outputSchema a card needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchContent(List<ProductListCard.Product> products) {}
}
