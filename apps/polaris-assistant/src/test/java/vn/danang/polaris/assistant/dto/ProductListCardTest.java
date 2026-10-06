package vn.danang.polaris.assistant.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductListCardTest {

    private static ProductListCard card(int fromInclusive, int toExclusive) {
        return new ProductListCard(IntStream.range(fromInclusive, toExclusive)
                .mapToObj(i -> new ProductListCard.Product("SKU-" + i, "Product " + i, null, new BigDecimal("1.00"), 1, true))
                .toList());
    }

    @Test
    @DisplayName("merging keeps first-seen order and lets a repeated SKU take the later values")
    void merge_firstSeenOrder_laterValuesWin() {
        ProductListCard earlier = card(0, 2);
        ProductListCard later = new ProductListCard(List.of(
                new ProductListCard.Product("SKU-2", "Product 2", null, new BigDecimal("2.00"), 3, true),
                new ProductListCard.Product("SKU-0", "Product 0", null, new BigDecimal("9.99"), 0, false)));

        ProductListCard merged = earlier.mergedWith(later);

        assertThat(merged.products()).extracting(ProductListCard.Product::sku).containsExactly("SKU-0", "SKU-1", "SKU-2");
        assertThat(merged.products().getFirst().price()).isEqualTo(new BigDecimal("9.99"));
        assertThat(merged.products().getFirst().available()).isFalse();
    }

    @Test
    @DisplayName("a merged card holds at most MAX_PRODUCTS products, the first ones seen")
    void merge_cappedAtMaxProducts() {
        ProductListCard merged = card(0, 40).mergedWith(card(40, 80));

        assertThat(merged.products()).hasSize(ProductListCard.MAX_PRODUCTS);
        assertThat(merged.products().getFirst().sku()).isEqualTo("SKU-0");
        assertThat(merged.products().getLast().sku()).isEqualTo("SKU-" + (ProductListCard.MAX_PRODUCTS - 1));
    }
}
