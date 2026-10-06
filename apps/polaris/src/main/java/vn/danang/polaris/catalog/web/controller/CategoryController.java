package vn.danang.polaris.catalog.web.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import vn.danang.polaris.catalog.dto.CategoryResponse;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.service.CategoryService;
import vn.danang.polaris.web.validator.PageableValidator;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Product catalog categories and taxonomy exploration")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_catalog.read')")
    @Operation(summary = "List categories", description = "List active categories. When rootOnly=true, returns only top-level categories without parents.")
    public List<CategoryResponse> listCategories(
            @Parameter(description = "If true, returns only top-level root categories without parents")
            @RequestParam(required = false, defaultValue = "false") Boolean rootOnly) {
        return categoryService.getCategories(Boolean.TRUE.equals(rootOnly));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_catalog.read')")
    @Operation(summary = "Get category by ID", description = "Retrieve single category details by internal database ID.")
    public CategoryResponse getCategoryById(
            @Parameter(description = "Category database ID")
            @PathVariable Long id) {
        return categoryService.getCategoryById(id);
    }

    @GetMapping("/code/{code}")
    @PreAuthorize("hasAuthority('PERM_catalog.read')")
    @Operation(summary = "Get category by slug/code", description = "Retrieve single category details by slug/code.")
    public CategoryResponse getCategoryByCode(
            @Parameter(description = "Category unique code/slug (e.g., audio, electronics)")
            @PathVariable String code) {
        return categoryService.getCategoryByCode(code);
    }

    @GetMapping("/{id}/products")
    @PreAuthorize("hasAuthority('PERM_catalog.read')")
    @Operation(summary = "Get products under category", description = "Retrieve products under the given category (and its direct subcategories), paged.")
    @Parameters({
        @Parameter(name = "page", description = "Zero-based page index (0..10000)", schema = @Schema(type = "integer", defaultValue = "0", minimum = "0", maximum = "10000")),
        @Parameter(name = "size", description = "The size of the page to be returned (1..100)", schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
        @Parameter(name = "sort", description = "Sorting criteria in the format: property(,asc|desc). Allowed properties: [id, sku, name, category, price, stockQuantity, stockQty, active, createdAt]", example = "id,asc", schema = @Schema(type = "string", defaultValue = "id,asc"))
    })
    public Page<ProductResponse> getCategoryProducts(
            @Parameter(description = "Category database ID")
            @PathVariable Long id,
            @Parameter(hidden = true)
            @PageableDefault(page = 0, size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        Pageable sanitized = PageableValidator.validateAndSanitize(pageable);
        return categoryService.getCategoryProducts(id, sanitized);
    }
}
