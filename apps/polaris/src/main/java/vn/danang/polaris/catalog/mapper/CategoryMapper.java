package vn.danang.polaris.catalog.mapper;

import java.util.Collections;
import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import vn.danang.polaris.catalog.dto.CategoryResponse;
import vn.danang.polaris.catalog.entity.Category;

@Mapper(componentModel = "spring")
public interface CategoryMapper {

    default CategoryResponse toResponse(Category category, Long productCount, List<CategoryResponse> subcategories) {
        if (category == null) {
            return null;
        }
        return mapToResponse(category, productCount, subcategories);
    }

    @Mapping(target = "id", source = "category.id")
    @Mapping(target = "code", source = "category.code")
    @Mapping(target = "name", source = "category.name")
    @Mapping(target = "description", source = "category.description")
    @Mapping(target = "parentId", source = "category.parent.id")
    @Mapping(target = "parentName", source = "category.parent.name")
    @Mapping(target = "subcategories", expression = "java(subcategories != null ? subcategories : java.util.Collections.emptyList())")
    @Mapping(target = "productCount", expression = "java(productCount != null ? productCount : 0L)")
    CategoryResponse mapToResponse(Category category, Long productCount, List<CategoryResponse> subcategories);

    default CategoryResponse toResponse(Category category, Long productCount) {
        if (category == null) {
            return null;
        }
        return toResponse(category, productCount, Collections.emptyList());
    }

    default CategoryResponse toResponse(Category category) {
        if (category == null) {
            return null;
        }
        return toResponse(category, 0L, Collections.emptyList());
    }

    List<CategoryResponse> toResponseList(List<Category> categories);

    @Mapping(target = "parent", ignore = true)
    @Mapping(target = "subcategories", ignore = true)
    @Mapping(target = "displayOrder", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Category toEntity(CategoryResponse response);
}
