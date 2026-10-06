package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Payload of the {@value ChatWidget#PRODUCT_LIST} card (BPMN {@code A_Present}). Every field is copied from
 * the {@code structuredContent} of {@code search_available_products} (Polaris MCP {@code outputSchema}), so
 * the card shows only what the catalog returned (FR-1), never model text.
 * <p>
 * One card per turn: when the model searches several times in a turn, the results are merged
 * ({@link #mergedWith}) rather than the later search replacing the earlier one, because the reply may talk
 * about the products of every search ("chargers and cases"). The trade-off: when the model <em>refines</em> a
 * search (e.g. "chargers" then "chargers under $20"), the broader results stay on the card next to the
 * refined ones. The merged card is capped at {@link #MAX_PRODUCTS}, keeping first-seen order.
 *
 * @param products products to render, in result order
 */
@Schema(description = "PRODUCT_LIST card: catalog search results")
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductListCard(
        @Schema(description = "Products in result order")
        List<Product> products
) {

    /** Most products a merged card holds; products beyond it (in first-seen order) are dropped. */
    public static final int MAX_PRODUCTS = 50;

    public ProductListCard {
        products = products != null ? List.copyOf(products) : List.of();
    }

    @Schema(name = "ProductCardItem", description = "One product as returned by the catalog")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Product(
            @Schema(example = "NG-CHARGER-01") String sku,
            @Schema(example = "Nova 65W Fast Charger") String name,
            @Schema(description = "Category name, or null", example = "Chargers") String category,
            @Schema(example = "24.90") BigDecimal price,
            @Schema(description = "Live stock on hand", example = "200") Integer stockQuantity,
            @Schema(description = "Active and in stock") Boolean available
    ) {}

    /**
     * Combines two cards of the same turn (e.g. "chargers and cases" searched separately): products of both
     * are kept, in first-seen order; a SKU found again takes the later, fresher values. The result holds at
     * most {@link #MAX_PRODUCTS} products (the first ones seen).
     */
    public ProductListCard mergedWith(ProductListCard later) {
        Map<String, Product> bySku = new LinkedHashMap<>();
        products.forEach(p -> bySku.put(p.sku(), p));
        later.products().forEach(p -> bySku.put(p.sku(), p));
        return new ProductListCard(bySku.values().stream().limit(MAX_PRODUCTS).toList());
    }

    public ChatWidget toWidget() {
        return new ChatWidget(ChatWidget.PRODUCT_LIST, this);
    }
}
