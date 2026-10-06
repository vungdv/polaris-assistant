package vn.danang.polaris.catalog.mapper;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.catalog.dto.CategoryResponse;
import vn.danang.polaris.catalog.entity.Category;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CategoryMapper Unit Tests")
class CategoryMapperTest {

    private final CategoryMapper mapper = Mappers.getMapper(CategoryMapper.class);

    private Category createSampleParent() {
        Category parent = new Category();
        parent.setId(10L);
        parent.setCode("electronics");
        parent.setName("Electronics");
        parent.setDescription("Electronic gadgets and devices");
        return parent;
    }

    private Category createSampleCategory(Category parent) {
        Category category = new Category();
        category.setId(20L);
        category.setCode("audio");
        category.setName("Audio & Sound");
        category.setDescription("Speakers, headphones, and earbuds");
        category.setParent(parent);
        return category;
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Should map complete Category entity and hierarchy to CategoryResponse")
        void shouldMapCategoryWithHierarchyAndCountsToResponse() {
            Category parent = createSampleParent();
            Category category = createSampleCategory(parent);
            CategoryResponse subResponse = new CategoryResponse(
                    30L, "earbuds", "Earbuds", "Wireless earbuds", 20L, "Audio & Sound", Collections.emptyList(), 2L
            );

            CategoryResponse response = mapper.toResponse(category, 5L, List.of(subResponse));

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(20L);
            assertThat(response.code()).isEqualTo("audio");
            assertThat(response.name()).isEqualTo("Audio & Sound");
            assertThat(response.description()).isEqualTo("Speakers, headphones, and earbuds");
            assertThat(response.parentId()).isEqualTo(10L);
            assertThat(response.parentName()).isEqualTo("Electronics");
            assertThat(response.productCount()).isEqualTo(5L);
            assertThat(response.subcategories()).containsExactly(subResponse);
        }

        @Test
        @DisplayName("Should map Category with convenience overloads applying proper defaults")
        void shouldMapCategoryWithConvenienceOverloads() {
            Category category = createSampleCategory(null);

            CategoryResponse responseWithCount = mapper.toResponse(category, 12L);
            CategoryResponse responseSimple = mapper.toResponse(category);

            assertThat(responseWithCount.productCount()).isEqualTo(12L);
            assertThat(responseWithCount.subcategories()).isEmpty();

            assertThat(responseSimple.productCount()).isEqualTo(0L);
            assertThat(responseSimple.subcategories()).isEmpty();
        }

        @Test
        @DisplayName("Should map Category list to CategoryResponse list preserving order")
        void shouldMapCategoryListToResponseList() {
            Category parent = createSampleParent();
            Category child = createSampleCategory(parent);

            List<CategoryResponse> responses = mapper.toResponseList(List.of(parent, child));

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).code()).isEqualTo("electronics");
            assertThat(responses.get(1).code()).isEqualTo("audio");
        }

        @Test
        @DisplayName("Should map CategoryResponse DTO to Category entity")
        void shouldMapCategoryResponseToCategoryEntity() {
            CategoryResponse response = new CategoryResponse(
                    50L, "wearables", "Wearables", "Smartwatches and trackers", 10L, "Electronics", Collections.emptyList(), 8L
            );

            Category entity = mapper.toEntity(response);

            assertThat(entity).isNotNull();
            assertThat(entity.getId()).isEqualTo(50L);
            assertThat(entity.getCode()).isEqualTo("wearables");
            assertThat(entity.getName()).isEqualTo("Wearables");
            assertThat(entity.getDescription()).isEqualTo("Smartwatches and trackers");
            assertThat(entity.getIsActive()).isTrue();
            assertThat(entity.getDisplayOrder()).isZero();
            assertThat(entity.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("2. Invalid / missing input")
    class InvalidInput {

        @Test
        @DisplayName("Should map Category without parent to response with null parent fields")
        void shouldMapCategoryWithoutParentToResponseWithNullParentFields() {
            Category category = createSampleCategory(null);

            CategoryResponse response = mapper.toResponse(category);

            assertThat(response.parentId()).isNull();
            assertThat(response.parentName()).isNull();
        }

        @Test
        @DisplayName("Should map Category with null subcategories parameter to empty list")
        void shouldMapCategoryWithNullSubcategoriesToEmptyList() {
            Category category = createSampleCategory(null);

            CategoryResponse response = mapper.toResponse(category, 3L, null);

            assertThat(response.subcategories()).isEmpty();
        }

        @Test
        @DisplayName("Should map Category with null productCount parameter to zero")
        void shouldMapCategoryWithNullProductCountToZero() {
            Category category = createSampleCategory(null);

            CategoryResponse response = mapper.toResponse(category, null, Collections.emptyList());

            assertThat(response.productCount()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return null when source Category entity is null")
        void shouldReturnNullWhenCategoryIsNull() {
            assertThat(mapper.toResponse(null)).isNull();
            assertThat(mapper.toResponse(null, 5L)).isNull();
            assertThat(mapper.toResponse(null, 5L, Collections.emptyList())).isNull();
        }

        @Test
        @DisplayName("Should return null when source CategoryResponse is null for toEntity")
        void shouldReturnNullWhenCategoryResponseIsNullForToEntity() {
            assertThat(mapper.toEntity(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when categories list is null")
        void shouldReturnNullWhenCategoriesListIsNull() {
            assertThat(mapper.toResponseList(null)).isNull();
        }

        @Test
        @DisplayName("Should return empty list when categories list is empty")
        void shouldReturnEmptyListWhenCategoriesListIsEmpty() {
            assertThat(mapper.toResponseList(Collections.emptyList())).isEmpty();
        }
    }
}
