package vn.danang.polaris.mcp;

import java.math.BigDecimal;
import java.util.List;

import vn.danang.polaris.catalog.dto.ProductResponse;

/**
 * {@code structuredContent} of the catalog MCP tools (BPMN {@code C_Search} → {@code A_Present}).
 * <p>
 * Field names are a published contract (the tools' {@code outputSchema}) consumed by the AI Assistant to
 * render product cards, so a card shows exactly what the tool returned (FR-1). Money stays {@link BigDecimal}.
 */
public final class ProductToolContent {

    private ProductToolContent() {
    }

    /**
     * One product as returned by {@code search_available_products} and {@code get_product_by_sku}.
     *
     * @param category      category name, or null when the product has none
     * @param stockQuantity live stock on hand (0 when unknown)
     * @param available     true when the product is active and has stock; same rule as the text result
     */
    public record Product(
            String sku,
            String name,
            String category,
            BigDecimal price,
            int stockQuantity,
            boolean available
    ) {

        public static Product from(ProductResponse p) {
            int stock = p.stockQuantity() != null ? p.stockQuantity() : 0;
            boolean available = Boolean.TRUE.equals(p.isAvailable()) && stock > 0;
            return new Product(p.sku(), p.name(), p.category(), p.price(), stock, available);
        }
    }

    /**
     * One page of {@code search_available_products} results.
     *
     * @param products      the products on this page, in result order; empty when nothing matched
     * @param totalElements total number of matching products across all pages
     */
    public record SearchResult(List<Product> products, long totalElements) {

        public SearchResult {
            products = products != null ? List.copyOf(products) : List.of();
        }
    }
}
