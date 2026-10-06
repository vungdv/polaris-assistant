package vn.danang.polaris.catalog.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.catalog.dto.CreateProductRequest;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.service.ProductService;
import vn.danang.polaris.catalog.web.controller.ProductController;
import vn.danang.polaris.web.exception.DuplicateSkuException;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
import vn.danang.polaris.web.support.JwtMockFactory;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
public class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void searchProducts_shouldReturnPagedResults() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L,
                "NG-EARBUD-01",
                "Nova Wireless Earbuds",
                "High-fidelity wireless earbuds",
                "Audio",
                new BigDecimal("49.90"),
                120,
                true,
                true,
                Instant.now()
        );

        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);
        when(productService.searchProducts(eq("wireless"), eq("Audio"), eq(null), any(), any(), eq(true), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("query", "wireless")
                        .param("category", "Audio")
                        .param("available", "true")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.content[0].name").value("Nova Wireless Earbuds"))
                .andExpect(jsonPath("$.content[0].category").value("Audio"))
                .andExpect(jsonPath("$.content[0].price").value(49.90))
                .andExpect(jsonPath("$.content[0].stockQuantity").value(120))
                .andExpect(jsonPath("$.content[0].isAvailable").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchProducts_withCategoryId_shouldFilterByCategoryId() throws Exception {
        ProductResponse sample = new ProductResponse(
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

        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);
        when(productService.searchProducts(any(), any(), eq(2L), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("categoryId", "2")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].categoryId").value(2))
                .andExpect(jsonPath("$.content[0].categoryCode").value("audio"));
    }

    @Test
    void getProductById_whenFound_shouldReturnProduct() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L,
                "NG-EARBUD-01",
                "Nova Wireless Earbuds",
                "High-fidelity wireless earbuds",
                "Audio",
                new BigDecimal("49.90"),
                120,
                true,
                true,
                Instant.now()
        );
        when(productService.getProductById(1L)).thenReturn(sample);

        mockMvc.perform(get("/api/v1/products/1").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.name").value("Nova Wireless Earbuds"));
    }

    @Test
    void getProductById_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(productService.getProductById(999L))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 999"));

        mockMvc.perform(get("/api/v1/products/999").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product not found with id: 999"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void getProductBySku_whenFound_shouldReturnProduct() throws Exception {
        ProductResponse sample = new ProductResponse(
                2L,
                "NG-WATCH-01",
                "Nova Smart Watch",
                "Advanced smartwatch",
                "Wearables",
                new BigDecimal("89.90"),
                60,
                true,
                true,
                Instant.now()
        );
        when(productService.getProductBySku("NG-WATCH-01")).thenReturn(sample);

        mockMvc.perform(get("/api/v1/products/sku/NG-WATCH-01").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.sku").value("NG-WATCH-01"))
                .andExpect(jsonPath("$.category").value("Wearables"));
    }

    @Test
    void getProductBySku_whenNotFound_shouldReturn404ProblemDetail() throws Exception {
        when(productService.getProductBySku("NON-EXISTENT"))
                .thenThrow(new ResourceNotFoundException("Product not found with SKU: NON-EXISTENT"));

        mockMvc.perform(get("/api/v1/products/sku/NON-EXISTENT").with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Product not found with SKU: NON-EXISTENT"));
    }

    @Test
    void searchProducts_invalidSortProperty_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("sort", "string")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Sort Property"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-sort"))
                .andExpect(jsonPath("$.detail").value("Invalid sort property 'string'. Allowed sort properties are: [id, sku, name, category, price, stockQuantity, stockQty, active, createdAt]. Format: property(,asc|desc)."))
                .andExpect(jsonPath("$.invalid_property").value("string"))
                .andExpect(jsonPath("$.allowed_properties", hasSize(9)));
    }

    @Test
    void searchProducts_pageSizeTooLarge_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("size", "1073741824")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page size must be between 1 and 100. Received: 1073741824."))
                .andExpect(jsonPath("$.invalid_param").value("size"))
                .andExpect(jsonPath("$.min").value(1))
                .andExpect(jsonPath("$.max").value(100))
                .andExpect(jsonPath("$.received").value(1073741824));
    }

    @Test
    void searchProducts_pageSizeBelowOne_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("size", "0")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page size must be between 1 and 100. Received: 0."))
                .andExpect(jsonPath("$.invalid_param").value("size"))
                .andExpect(jsonPath("$.min").value(1))
                .andExpect(jsonPath("$.max").value(100))
                .andExpect(jsonPath("$.received").value(0));
    }

    @Test
    void searchProducts_pageIndexTooLarge_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "1073741824")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page index must be between 0 and 10000. Received: 1073741824."))
                .andExpect(jsonPath("$.invalid_param").value("page"))
                .andExpect(jsonPath("$.min").value(0))
                .andExpect(jsonPath("$.max").value(10000))
                .andExpect(jsonPath("$.received").value(1073741824));
    }

    @Test
    void searchProducts_pageIndexNegative_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "-1")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Pagination Parameter"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/invalid-pagination"))
                .andExpect(jsonPath("$.detail").value("Page index must be between 0 and 10000. Received: -1."))
                .andExpect(jsonPath("$.invalid_param").value("page"))
                .andExpect(jsonPath("$.min").value(0))
                .andExpect(jsonPath("$.max").value(10000))
                .andExpect(jsonPath("$.received").value(-1));
    }

    @Test
    void searchProducts_sortByAliasStockQuantity_shouldReturn200() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L, "NG-EARBUD-01", "Nova Wireless Earbuds", "Description",
                "Audio", new BigDecimal("49.90"), 120, true, true, Instant.now()
        );
        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1);
        when(productService.searchProducts(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("sort", "stockQuantity,desc")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void searchProducts_validSortAndPagination_shouldReturn200() throws Exception {
        ProductResponse sample = new ProductResponse(
                1L, "NG-EARBUD-01", "Nova Wireless Earbuds", "Description",
                "Audio", new BigDecimal("49.90"), 120, true, true, Instant.now()
        );
        Page<ProductResponse> page = new PageImpl<>(List.of(sample), PageRequest.of(0, 10), 1);
        when(productService.searchProducts(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/products")
                        .param("page", "0")
                        .param("size", "10")
                        .param("sort", "price,asc")
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void adjustInventory_deltaAdjustment_shouldReturn200() throws Exception {
        vn.danang.polaris.catalog.entity.Product entity = new vn.danang.polaris.catalog.entity.Product();
        entity.setId(1L);
        entity.setSku("NG-EARBUD-01");
        entity.setName("Nova Wireless Earbuds");
        entity.setStockQty(140);
        entity.setPrice(new BigDecimal("49.90"));
        entity.setIsActive(true);

        when(productService.adjustInventory(eq("NG-EARBUD-01"), eq(20))).thenReturn(entity);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/products/sku/NG-EARBUD-01/inventory")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"delta\": 20}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.stockQuantity").value(140));
    }

    @Test
    void createProduct_withRequiredFieldsOnly_shouldReturn201WithLocation() throws Exception {
        ProductResponse created = new ProductResponse(
                10L,
                "NG-KEYBOARD-01",
                "Nova Mechanical Keyboard",
                null,
                null,
                new BigDecimal("89.99"),
                0,
                false,
                true,
                Instant.now()
        );
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-KEYBOARD-01",
                                    "name": "Nova Mechanical Keyboard",
                                    "price": 89.99
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/products/10"))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.sku").value("NG-KEYBOARD-01"))
                .andExpect(jsonPath("$.name").value("Nova Mechanical Keyboard"))
                .andExpect(jsonPath("$.price").value(89.99))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.isAvailable").value(false))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createProduct_withFullOptionalFieldsAndCategory_shouldReturn201() throws Exception {
        ProductResponse created = new ProductResponse(
                11L,
                "NG-SPEAKER-PRO",
                "Nova SoundCore Portable Speaker",
                "High-fidelity Bluetooth speaker with 24-hour battery life",
                "Audio & Sound",
                new BigDecimal("129.50"),
                75,
                true,
                true,
                Instant.now(),
                1L,
                "audio"
        );
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-SPEAKER-PRO",
                                    "name": "Nova SoundCore Portable Speaker",
                                    "description": "High-fidelity Bluetooth speaker with 24-hour battery life",
                                    "categoryId": 1,
                                    "price": 129.50,
                                    "stockQuantity": 75,
                                    "active": true
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/products/11"))
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.sku").value("NG-SPEAKER-PRO"))
                .andExpect(jsonPath("$.categoryId").value(1))
                .andExpect(jsonPath("$.category").value("Audio & Sound"))
                .andExpect(jsonPath("$.stockQuantity").value(75))
                .andExpect(jsonPath("$.isAvailable").value(true));
    }

    @Test
    void createProduct_duplicateSku_shouldReturn409ProblemDetail() throws Exception {
        when(productService.createProduct(any(CreateProductRequest.class)))
                .thenThrow(new DuplicateSkuException("NG-EARBUD-01"));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-EARBUD-01",
                                    "name": "Duplicate Earbuds",
                                    "price": 49.99
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate SKU Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/duplicate-sku"))
                .andExpect(jsonPath("$.sku").value("NG-EARBUD-01"))
                .andExpect(jsonPath("$.detail").value("A product with SKU 'NG-EARBUD-01' already exists."))
                .andExpect(jsonPath("$.remedy").value("Choose a unique SKU code or update the existing product."));
    }

    @Test
    void createProduct_validationFailure_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "",
                                    "name": "",
                                    "price": -10.00,
                                    "stockQuantity": -5
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void createProduct_nonExistentCategory_shouldReturn404ProblemDetail() throws Exception {
        when(productService.createProduct(any(CreateProductRequest.class)))
                .thenThrow(new ResourceNotFoundException("Category not found with id: 9999"));

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-NEW-ITEM",
                                    "name": "New Item",
                                    "categoryId": 9999,
                                    "price": 29.99
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.detail").value("Category not found with id: 9999"));
    }

    @Test
    void adjustInventoryById_deltaAdjustment_shouldReturn200() throws Exception {
        vn.danang.polaris.catalog.entity.Product entity = new vn.danang.polaris.catalog.entity.Product();
        entity.setId(2L);
        entity.setSku("NG-WATCH-01");
        entity.setName("Nova Smart Watch");
        entity.setStockQty(50);
        entity.setPrice(new BigDecimal("89.90"));
        entity.setIsActive(true);

        when(productService.adjustInventoryById(eq(2L), eq(30))).thenReturn(entity);

        mockMvc.perform(put("/api/v1/products/2/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": 30}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.sku").value("NG-WATCH-01"))
                .andExpect(jsonPath("$.stockQuantity").value(50))
                .andExpect(jsonPath("$.isAvailable").value(true));
    }

    @Test
    void adjustInventoryById_negativeResultingStock_shouldReturn400ProblemDetail() throws Exception {
        when(productService.adjustInventoryById(eq(3L), eq(-10)))
                .thenThrow(new IllegalArgumentException("Cannot adjust stock below 0. Current: 5, delta: -10"));

        mockMvc.perform(put("/api/v1/products/3/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": -10}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                .andExpect(jsonPath("$.detail").value("Cannot adjust stock below 0. Current: 5, delta: -10"));
    }

    @Test
    void adjustInventoryById_nonExistentProductId_shouldReturn404ProblemDetail() throws Exception {
        when(productService.adjustInventoryById(eq(9999L), eq(20)))
                .thenThrow(new ResourceNotFoundException("Product not found with id: 9999"));

        mockMvc.perform(put("/api/v1/products/9999/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": 20}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.detail").value("Product not found with id: 9999"));
    }

    @Test
    void adjustInventoryById_emptyPayload_shouldReturn400ProblemDetail() throws Exception {
        mockMvc.perform(put("/api/v1/products/1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"));
    }

    @Test
    void searchProducts_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchProducts_missingCatalogReadPermission_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProduct_missingCatalogWritePermission_shouldReturn403() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"NG-NEW-01\",\"name\":\"Test\",\"price\":10.0}")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adjustInventory_missingInventoryWritePermission_shouldReturn403() throws Exception {
        mockMvc.perform(put("/api/v1/products/1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": 10}")
                        .with(JwtMockFactory.productCatalog())) // productCatalog has catalog.read/write, NOT inventory.write
                .andExpect(status().isForbidden());
    }
}
