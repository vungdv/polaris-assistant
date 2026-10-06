package vn.danang.polaris.catalog.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
public class CategoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listCategories_default_shouldReturnAllActiveCategories() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].code").value("electronics"))
                .andExpect(jsonPath("$[0].name").value("Electronics"))
                .andExpect(jsonPath("$[0].productCount").value(7))
                .andExpect(jsonPath("$[0].subcategories", hasSize(3)))
                .andExpect(jsonPath("$[1].code").value("accessories"))
                .andExpect(jsonPath("$[1].name").value("Accessories"))
                .andExpect(jsonPath("$[1].productCount").value(5))
                .andExpect(jsonPath("$[1].subcategories", hasSize(3)));
    }

    @Test
    void listCategories_rootOnlyTrue_shouldReturnOnlyRootCategories() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .param("rootOnly", "true")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].code").value("electronics"))
                .andExpect(jsonPath("$[0].parentId").isEmpty())
                .andExpect(jsonPath("$[0].subcategories", hasSize(3)))
                .andExpect(jsonPath("$[1].code").value("accessories"))
                .andExpect(jsonPath("$[1].parentId").isEmpty())
                .andExpect(jsonPath("$[1].subcategories", hasSize(3)));
    }

    @Test
    void getCategoryById_whenFound_shouldReturnHierarchyAndCount() throws Exception {
        // Find electronics by listing first or checking ID 1
        mockMvc.perform(get("/api/v1/categories/code/electronics")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("electronics"))
                .andExpect(jsonPath("$.productCount").value(7))
                .andExpect(jsonPath("$.subcategories", hasSize(3)));
    }

    @Test
    void getCategoryByCode_subCategory_shouldIncludeParentDetails() throws Exception {
        mockMvc.perform(get("/api/v1/categories/code/audio")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("audio"))
                .andExpect(jsonPath("$.name").value("Audio & Sound"))
                .andExpect(jsonPath("$.parentName").value("Electronics"))
                .andExpect(jsonPath("$.productCount").value(3));
    }

    @Test
    void getCategoryByCode_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/categories/code/non-existent-category")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getCategoryProducts_forRootCategory_shouldReturnProductsInSubcategories() throws Exception {
        // Electronics ID is 1
        mockMvc.perform(get("/api/v1/categories/1/products")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(7)))
                .andExpect(jsonPath("$.totalElements").value(7));
    }

    @Test
    void getCategoryProducts_forSubCategory_shouldReturnOnlySubCategoryProducts() throws Exception {
        // Audio ID is 3
        mockMvc.perform(get("/api/v1/categories/3/products")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].categoryCode").value("audio"));
    }

    @Test
    void getCategoryProducts_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/categories/9999/products")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void productsEndpoint_withCategoryIdFilter_shouldReturnProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", "3")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].categoryCode").value("audio"))
                .andExpect(jsonPath("$.content[0].categoryId").value(3));
    }

    @Test
    void productsEndpoint_withCategorySlugOrName_shouldMatchCaseInsensitively() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("category", "audio")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));

        mockMvc.perform(get("/api/v1/products")
                        .param("category", "Audio & Sound")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));

        mockMvc.perform(get("/api/v1/products")
                        .param("category", "electronics")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(7)));
    }

    @Test
    void unauthenticatedRequest_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listCategories_withoutCatalogReadPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }

    @Test
    void distributedTracingPropagation_shouldReturnTraceIdInHeader() throws Exception {
        mockMvc.perform(get("/api/v1/categories")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
    }
}
