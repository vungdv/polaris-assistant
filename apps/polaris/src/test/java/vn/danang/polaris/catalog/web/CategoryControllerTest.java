package vn.danang.polaris.catalog.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.config.WebConfig;
import vn.danang.polaris.catalog.dto.CategoryResponse;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.service.CategoryService;
import vn.danang.polaris.catalog.web.controller.CategoryController;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(CategoryController.class)
@ImportAutoConfiguration(WebConfig.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    void listCategories_default_shouldReturnAllCategories() throws Exception {
        CategoryResponse cat1 = new CategoryResponse(
                1L, "electronics", "Electronics", "Consumer electronics", null, null, Collections.emptyList(), 7L
        );
        CategoryResponse cat2 = new CategoryResponse(
                2L, "audio", "Audio & Sound", "Headphones and speakers", 1L, "Electronics", Collections.emptyList(), 3L
        );

        when(categoryService.getCategories(false)).thenReturn(List.of(cat1, cat2));

        mockMvc.perform(get("/api/v1/categories").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].code").value("electronics"))
                .andExpect(jsonPath("$[0].name").value("Electronics"))
                .andExpect(jsonPath("$[0].productCount").value(7))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].code").value("audio"))
                .andExpect(jsonPath("$[1].parentId").value(1))
                .andExpect(jsonPath("$[1].parentName").value("Electronics"));
    }

    @Test
    void listCategories_rootOnlyTrue_shouldReturnOnlyRootCategories() throws Exception {
        CategoryResponse sub = new CategoryResponse(
                2L, "audio", "Audio & Sound", "Headphones and speakers", 1L, "Electronics", Collections.emptyList(), 3L
        );
        CategoryResponse root = new CategoryResponse(
                1L, "electronics", "Electronics", "Consumer electronics", null, null, List.of(sub), 7L
        );

        when(categoryService.getCategories(true)).thenReturn(List.of(root));

        mockMvc.perform(get("/api/v1/categories")
                        .param("rootOnly", "true")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].code").value("electronics"))
                .andExpect(jsonPath("$[0].subcategories", hasSize(1)))
                .andExpect(jsonPath("$[0].subcategories[0].code").value("audio"));
    }

    @Test
    void getCategoryById_whenFound_shouldReturnCategory() throws Exception {
        CategoryResponse cat = new CategoryResponse(
                1L, "electronics", "Electronics", "Consumer electronics", null, null, Collections.emptyList(), 7L
        );
        when(categoryService.getCategoryById(1L)).thenReturn(cat);

        mockMvc.perform(get("/api/v1/categories/1").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("electronics"))
                .andExpect(jsonPath("$.name").value("Electronics"))
                .andExpect(jsonPath("$.productCount").value(7));
    }

    @Test
    void getCategoryById_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(categoryService.getCategoryById(999L))
                .thenThrow(new ResourceNotFoundException("Category not found with id: 999"));

        mockMvc.perform(get("/api/v1/categories/999").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Category not found with id: 999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getCategoryByCode_whenFound_shouldReturnCategory() throws Exception {
        CategoryResponse cat = new CategoryResponse(
                2L, "audio", "Audio & Sound", "Headphones and speakers", 1L, "Electronics", Collections.emptyList(), 3L
        );
        when(categoryService.getCategoryByCode("audio")).thenReturn(cat);

        mockMvc.perform(get("/api/v1/categories/code/audio").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.code").value("audio"))
                .andExpect(jsonPath("$.name").value("Audio & Sound"))
                .andExpect(jsonPath("$.parentId").value(1))
                .andExpect(jsonPath("$.parentName").value("Electronics"));
    }

    @Test
    void getCategoryByCode_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(categoryService.getCategoryByCode("non-existent"))
                .thenThrow(new ResourceNotFoundException("Category not found with code: non-existent"));

        mockMvc.perform(get("/api/v1/categories/code/non-existent").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Category not found with code: non-existent"));
    }

    @Test
    void getCategoryProducts_whenFound_shouldReturnPagedProducts() throws Exception {
        ProductResponse product = new ProductResponse(
                1L,
                "NG-EARBUD-01",
                "Nova Wireless Earbuds",
                "High-fidelity wireless earbuds",
                "Audio & Sound",
                new BigDecimal("49.90"),
                120,
                true,
                true,
                Instant.now(),
                2L,
                "audio"
        );
        Page<ProductResponse> page = new PageImpl<>(List.of(product), PageRequest.of(0, 20), 1);
        when(categoryService.getCategoryProducts(eq(2L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/categories/2/products").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.content[0].categoryId").value(2))
                .andExpect(jsonPath("$.content[0].categoryCode").value("audio"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getCategoryProducts_whenCategoryNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(categoryService.getCategoryProducts(eq(999L), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException("Category not found with id: 999"));

        mockMvc.perform(get("/api/v1/categories/999/products").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Category not found with id: 999"));
    }

    @Test
    void getCategoryProducts_invalidSortProperty_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/categories/2/products")
                        .param("sort", "string")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Sort Property"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-sort"));
    }

    @Test
    void listCategories_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listCategories_missingCatalogReadPermission_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }
}
