package vn.danang.polaris.catalog.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.entity.Category;
import vn.danang.polaris.catalog.entity.Product;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductMapper Unit Tests")
class ProductMapperTest {

    private final ProductMapper mapper = Mappers.getMapper(ProductMapper.class);

    private Category createSampleCategory() {
        Category category = new Category();
        category.setId(10L);
        category.setCode("audio");
        category.setName("Audio & Sound");
        return category;
    }

    private Product createSampleProduct(Category category) {
        Product product = new Product();
        product.setId(1L);
        product.setSku("NG-EARBUD-01");
        product.setName("Nova Wireless Earbuds");
        product.setDescription("High-fidelity earbuds with ANC");
        product.setPrice(new BigDecimal("49.99"));
        product.setStockQty(50);
        product.setIsActive(true);
        product.setCreatedAt(Instant.parse("2026-01-01T12:00:00Z"));
        if (category != null) {
            product.setCategoryEntity(category);
            product.setCategory(category.getName());
        } else {
            product.setCategory("General");
        }
        return product;
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Should map complete Product entity with Category entity to ProductResponse")
        void shouldMapProductWithCategoryEntityToResponse() {
            Category category = createSampleCategory();
            Product product = createSampleProduct(category);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.sku()).isEqualTo("NG-EARBUD-01");
            assertThat(response.name()).isEqualTo("Nova Wireless Earbuds");
            assertThat(response.description()).isEqualTo("High-fidelity earbuds with ANC");
            assertThat(response.category()).isEqualTo("Audio & Sound");
            assertThat(response.categoryId()).isEqualTo(10L);
            assertThat(response.categoryCode()).isEqualTo("audio");
            assertThat(response.price()).isEqualTo(new BigDecimal("49.99"));
            assertThat(response.stockQuantity()).isEqualTo(50);
            assertThat(response.isAvailable()).isTrue();
            assertThat(response.active()).isTrue();
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-01-01T12:00:00Z"));
        }

        @Test
        @DisplayName("Should map Product with standalone category string without Category entity")
        void shouldMapProductWithStandaloneCategoryToResponse() {
            Product product = createSampleProduct(null);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response).isNotNull();
            assertThat(response.category()).isEqualTo("General");
            assertThat(response.categoryId()).isNull();
            assertThat(response.categoryCode()).isNull();
        }

        @Test
        @DisplayName("Should map Product list to ProductResponse list preserving order")
        void shouldMapProductListToResponseList() {
            Category category = createSampleCategory();
            Product p1 = createSampleProduct(category);

            Product p2 = new Product();
            p2.setId(2L);
            p2.setSku("NG-WATCH-01");
            p2.setName("Nova Smartwatch");
            p2.setPrice(new BigDecimal("199.99"));
            p2.setStockQty(10);
            p2.setIsActive(true);

            List<ProductResponse> responses = mapper.toResponseList(List.of(p1, p2));

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).sku()).isEqualTo("NG-EARBUD-01");
            assertThat(responses.get(1).sku()).isEqualTo("NG-WATCH-01");
        }

        @Test
        @DisplayName("Should map ProductResponse DTO to Product entity")
        void shouldMapProductResponseToProductEntity() {
            ProductResponse response = new ProductResponse(
                    5L,
                    "NG-MOUSE-01",
                    "Nova Mouse",
                    "Wireless mouse",
                    "Accessories",
                    new BigDecimal("29.99"),
                    100,
                    true,
                    true,
                    Instant.parse("2026-02-01T00:00:00Z"),
                    null,
                    null
            );

            Product entity = mapper.toEntity(response);

            assertThat(entity).isNotNull();
            assertThat(entity.getId()).isEqualTo(5L);
            assertThat(entity.getSku()).isEqualTo("NG-MOUSE-01");
            assertThat(entity.getName()).isEqualTo("Nova Mouse");
            assertThat(entity.getDescription()).isEqualTo("Wireless mouse");
            assertThat(entity.getCategory()).isEqualTo("Accessories");
            assertThat(entity.getPrice()).isEqualTo(new BigDecimal("29.99"));
            assertThat(entity.getStockQty()).isEqualTo(100);
            assertThat(entity.getIsActive()).isTrue();
            assertThat(entity.getCreatedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        }

        @Test
        @DisplayName("Should map Product to ProductResponse via static ProductResponse.from facade")
        void shouldMapViaProductResponseFromFacade() {
            Product product = createSampleProduct(createSampleCategory());

            ProductResponse response = ProductResponse.from(product);

            assertThat(response).isNotNull();
            assertThat(response.sku()).isEqualTo("NG-EARBUD-01");
            assertThat(response.stockQuantity()).isEqualTo(50);
            assertThat(response.isAvailable()).isTrue();
        }
    }

    @Nested
    @DisplayName("2. Invalid / missing input")
    class InvalidInput {

        @Test
        @DisplayName("Should calculate isAvailable as false when stockQty is zero")
        void shouldCalculateIsAvailableAsFalseWhenStockIsZero() {
            Product product = createSampleProduct(null);
            product.setStockQty(0);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response.stockQuantity()).isEqualTo(0);
            assertThat(response.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Should calculate isAvailable as false when stockQty is null")
        void shouldCalculateIsAvailableAsFalseWhenStockIsNull() {
            Product product = createSampleProduct(null);
            product.setStockQty(null);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response.stockQuantity()).isNull();
            assertThat(response.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Should calculate isAvailable as false when isActive is false")
        void shouldCalculateIsAvailableAsFalseWhenInactive() {
            Product product = createSampleProduct(null);
            product.setIsActive(false);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response.active()).isFalse();
            assertThat(response.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Should calculate isAvailable as false when isActive is null")
        void shouldCalculateIsAvailableAsFalseWhenActiveIsNull() {
            Product product = createSampleProduct(null);
            product.setIsActive(null);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response.active()).isNull();
            assertThat(response.isAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return null when source Product is null")
        void shouldReturnNullWhenProductIsNull() {
            assertThat(mapper.toResponse(null)).isNull();
            assertThat(ProductResponse.from(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when source ProductResponse is null for toEntity")
        void shouldReturnNullWhenResponseIsNullForToEntity() {
            assertThat(mapper.toEntity(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when products list is null")
        void shouldReturnNullWhenProductsListIsNull() {
            assertThat(mapper.toResponseList(null)).isNull();
        }

        @Test
        @DisplayName("Should return empty list when products list is empty")
        void shouldReturnEmptyListWhenProductsListIsEmpty() {
            assertThat(mapper.toResponseList(Collections.emptyList())).isEmpty();
        }

        @Test
        @DisplayName("Should fallback createdAt to current timestamp when product createdAt is null")
        void shouldFallbackCreatedAtWhenNull() {
            Product product = createSampleProduct(null);
            product.setCreatedAt(null);

            ProductResponse response = mapper.toResponse(product);

            assertThat(response.createdAt()).isNotNull();
        }
    }
}
