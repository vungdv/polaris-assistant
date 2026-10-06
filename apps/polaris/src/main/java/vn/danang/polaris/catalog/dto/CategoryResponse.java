package vn.danang.polaris.catalog.dto;

import java.util.Collections;
import java.util.List;

import org.mapstruct.factory.Mappers;

import vn.danang.polaris.catalog.entity.Category;
import vn.danang.polaris.catalog.mapper.CategoryMapper;

public record CategoryResponse(
    Long id,
    String code,
    String name,
    String description,
    Long parentId,
    String parentName,
    List<CategoryResponse> subcategories,
    Long productCount
) {
    private static final CategoryMapper MAPPER = Mappers.getMapper(CategoryMapper.class);

    public static CategoryResponse from(Category category, Long productCount, List<CategoryResponse> subcategories) {
        return MAPPER.toResponse(category, productCount, subcategories);
    }

    public static CategoryResponse from(Category category, Long productCount) {
        return MAPPER.toResponse(category, productCount);
    }

    public static CategoryResponse from(Category category) {
        return MAPPER.toResponse(category);
    }
}
