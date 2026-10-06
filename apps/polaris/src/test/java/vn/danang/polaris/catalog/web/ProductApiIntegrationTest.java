package vn.danang.polaris.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
// Why do we need @Transactional here? 
// Because we want to roll back the database changes after each test, so that tests don't interfere with each other.
// Ah, this @transactional will override the one defined in the service layer 
// so there is a single transaction defined here only, all query live in the same transaction?
// But what if there are nested transactions in the service layer?
// In that case, the nested transactions will be ignored and all queries will still be part of the same transaction defined here.

// I think nested transactions are anti patterns, it makes system become overly complex and hard to reason about.
// Should we avoid nested transactions in the service layer? 
// Yes, we should avoid nested transactions in the service layer.
@Transactional
@Import(TestcontainersConfiguration.class)
public class ProductApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void createProduct_success_persistsToDatabaseAndReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-KEYBOARD-NEW",
                                    "name": "Nova Mechanical Keyboard V2",
                                    "price": 99.99
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/products/")))
                .andExpect(jsonPath("$.sku").value("NG-KEYBOARD-NEW"))
                .andExpect(jsonPath("$.name").value("Nova Mechanical Keyboard V2"))
                .andExpect(jsonPath("$.price").value(99.99))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.isAvailable").value(false))
                .andExpect(jsonPath("$.active").value(true));

        var saved = productRepository.findBySku("NG-KEYBOARD-NEW");
        assertThat(saved).isPresent();
        assertThat(saved.get().getName()).isEqualTo("Nova Mechanical Keyboard V2");
    }

    @Test
    void createProduct_withCategoryId_associatesCategoryHierarchy() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-DAC-01",
                                    "name": "Nova USB-C Audio DAC",
                                    "description": "High-res DAC converter",
                                    "categoryId": 3,
                                    "price": 39.95,
                                    "stockQuantity": 25,
                                    "active": true
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("NG-DAC-01"))
                .andExpect(jsonPath("$.categoryId").value(3))
                .andExpect(jsonPath("$.category").value("Audio & Sound"))
                .andExpect(jsonPath("$.stockQuantity").value(25))
                .andExpect(jsonPath("$.isAvailable").value(true));

        var saved = productRepository.findBySku("NG-DAC-01").orElseThrow();
        assertThat(saved.getCategoryEntity()).isNotNull();
        assertThat(saved.getCategoryEntity().getId()).isEqualTo(3L);
    }

    @Test
    void createProduct_duplicateSku_returns409Conflict() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "ng-earbud-01",
                                    "name": "Duplicate Earbuds Attempt",
                                    "price": 49.90
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate SKU Conflict"))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/duplicate-sku"))
                .andExpect(jsonPath("$.sku").value("ng-earbud-01"))
                .andExpect(jsonPath("$.remedy").value("Choose a unique SKU code or update the existing product."));
    }

    @Test
    void createProduct_nonExistentCategoryId_returns404NotFound() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sku": "NG-ORPHAN-01",
                                    "name": "Orphan Product",
                                    "categoryId": 99999,
                                    "price": 19.99
                                }
                                """)
                        .with(JwtMockFactory.productCatalog()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Category not found with id: 99999"));
    }

    @Test
    void adjustInventoryById_relativeDelta_deductsStockToZeroAndUpdatesAvailability() throws Exception {
        var before = productRepository.findById(1L).orElseThrow();
        int initialStock = before.getStockQty();

        mockMvc.perform(put("/api/v1/products/1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": -" + initialStock + "}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.stockQuantity").value(0))
                .andExpect(jsonPath("$.isAvailable").value(false));

        var updated = productRepository.findById(1L).orElseThrow();
        assertThat(updated.getStockQty()).isEqualTo(0);
    }

    @Test
    void adjustInventoryById_relativeDelta_restocksCorrectly() throws Exception {
        var before = productRepository.findById(2L).orElseThrow();
        int initialStock = before.getStockQty();

        mockMvc.perform(put("/api/v1/products/2/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": 25}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.stockQuantity").value(initialStock + 25))
                .andExpect(jsonPath("$.isAvailable").value(true));

        var updated = productRepository.findById(2L).orElseThrow();
        assertThat(updated.getStockQty()).isEqualTo(initialStock + 25);
    }

    @Test
    void adjustInventoryById_negativeResultingStock_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/products/1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": -999999}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"));
    }

    @Test
    void adjustInventoryById_nonExistentProductId_returns404() throws Exception {
        mockMvc.perform(put("/api/v1/products/99999/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\": 50}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void adjustInventoryById_missingDelta_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/products/1/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(JwtMockFactory.inventory()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
