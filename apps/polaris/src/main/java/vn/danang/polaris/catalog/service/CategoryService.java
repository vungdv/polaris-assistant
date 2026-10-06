package vn.danang.polaris.catalog.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.catalog.dto.CategoryResponse;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.entity.Category;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.mapper.CategoryMapper;
import vn.danang.polaris.catalog.repository.CategoryRepository;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.catalog.repository.ProductSpecifications;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CategoryMapper categoryMapper;

    @Autowired
    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository, CategoryMapper categoryMapper) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.categoryMapper = categoryMapper;
    }

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this(categoryRepository, productRepository, Mappers.getMapper(CategoryMapper.class));
    }

    public List<CategoryResponse> getCategories(boolean rootOnly) {
        Map<Long, Long> countsMap = getProductCountsMap();

        if (rootOnly) {
            List<Category> rootCategories = categoryRepository.findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc();
            return rootCategories.stream()
                    .map(cat -> mapToResponse(cat, countsMap))
                    .toList();
        }

        List<Category> allCategories = categoryRepository.findByIsActiveTrueOrderByIdAsc();
        return allCategories.stream()
                .map(cat -> mapToResponse(cat, countsMap))
                .toList();
    }

    public CategoryResponse getCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + id));

        Map<Long, Long> countsMap = getProductCountsMap();
        return mapToResponse(category, countsMap);
    }

    public CategoryResponse getCategoryByCode(String code) {
        Category category = categoryRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with code: " + code));

        Map<Long, Long> countsMap = getProductCountsMap();
        return mapToResponse(category, countsMap);
    }

    public Page<ProductResponse> getCategoryProducts(Long categoryId, Pageable pageable) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category not found with id: " + categoryId);
        }

        Specification<Product> spec = ProductSpecifications.hasCategoryId(categoryId);
        return productRepository.findAll(spec, pageable)
                .map(ProductResponse::from);
    }

    private Map<Long, Long> getProductCountsMap() {
        List<Object[]> results = productRepository.countProductsGroupedByCategoryId();
        return results.stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> ((Number) row[1]).longValue(),
                        (existing, replacement) -> existing
                ));
    }

    private CategoryResponse mapToResponse(Category category, Map<Long, Long> countsMap) {
        List<Category> activeChildren = category.getSubcategories() != null
                ? category.getSubcategories().stream()
                        .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                        .toList()
                : Collections.emptyList();

        long directCount = countsMap.getOrDefault(category.getId(), 0L);
        long childrenCount = activeChildren.stream()
                .mapToLong(c -> countsMap.getOrDefault(c.getId(), 0L))
                .sum();
        long totalCount = directCount + childrenCount;

        List<CategoryResponse> childResponses = activeChildren.stream()
                .map(c -> categoryMapper.toResponse(c, countsMap.getOrDefault(c.getId(), 0L), Collections.emptyList()))
                .toList();

        return categoryMapper.toResponse(category, totalCount, childResponses);
    }
}
