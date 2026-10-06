package vn.danang.polaris.catalog.repository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.CategoryRepository;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.catalog.repository.ProductSpecifications;

@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
public class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Test
    void searchAvailableProducts_shouldFilterOutOfStockItems() {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.isAvailable(true));

        Page<Product> availableProducts = productRepository.findAll(spec, PageRequest.of(0, 20));

        // This test depends on the seed data in the test database. 
        // The seed data should include a mix of in-stock and out-of-stock products.
        // The seed data has 5 in-stock and 1 out-of-stock ('NG-STAND-01')
        assertThat(availableProducts.getContent()).isNotEmpty();
        assertThat(availableProducts.getContent())
                .allMatch(p -> p.getStockQty() > 0 && Boolean.TRUE.equals(p.getIsActive()));
        assertThat(availableProducts.getContent())
                .noneMatch(p -> "NG-STAND-01".equals(p.getSku()));
    }

    @Test
    void searchByKeyword_shouldMatchAcrossNameSkuAndDescription() {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.hasKeyword("noise cancellation"));

        List<Product> results = productRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSku()).isEqualTo("NG-EARBUD-01");
    }

    @Test
    void searchByPriceRangeAndCategory() {
        Specification<Product> spec = Specification
                .where(ProductSpecifications.hasCategory("Accessories"))
                .and(ProductSpecifications.minPrice(new BigDecimal("20.00")))
                .and(ProductSpecifications.maxPrice(new BigDecimal("30.00")));

        List<Product> results = productRepository.findAll(spec);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSku()).isEqualTo("NG-CHARGER-01");
    }

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void searchByCategory_byCodeOrName_shouldMatch() {
        Specification<Product> byCodeSpec = Specification
                .where(ProductSpecifications.hasCategory("audio"));
        List<Product> audioByCode = productRepository.findAll(byCodeSpec);
        assertThat(audioByCode).isNotEmpty();
        assertThat(audioByCode).allMatch(p -> "audio".equals(p.getCategoryCode()));

        Specification<Product> byNameSpec = Specification
                .where(ProductSpecifications.hasCategory("Audio & Sound"));
        List<Product> audioByName = productRepository.findAll(byNameSpec);
        assertThat(audioByName).hasSameElementsAs(audioByCode);

        // Parent category matching
        Specification<Product> byParentSpec = Specification
                .where(ProductSpecifications.hasCategory("electronics"));
        List<Product> electronicsProducts = productRepository.findAll(byParentSpec);
        assertThat(electronicsProducts).isNotEmpty();
        assertThat(electronicsProducts).extracting(p -> p.getCategoryEntity().getParent().getCode())
                .containsOnly("electronics");
    }

    @Test
    void searchByCategoryId_shouldMatchCategoryAndSubcategories() {
        var electronics = categoryRepository.findByCode("electronics").orElseThrow();
        Specification<Product> rootSpec = Specification
                .where(ProductSpecifications.hasCategoryId(electronics.getId()));

        List<Product> rootProducts = productRepository.findAll(rootSpec);
        assertThat(rootProducts).isNotEmpty();
        assertThat(rootProducts).allMatch(p -> electronics.getId().equals(p.getCategoryEntity().getParent().getId()));

        var audio = categoryRepository.findByCode("audio").orElseThrow();
        Specification<Product> childSpec = Specification
                .where(ProductSpecifications.hasCategoryId(audio.getId()));

        List<Product> childProducts = productRepository.findAll(childSpec);
        assertThat(childProducts).hasSize(3);
        assertThat(childProducts).extracting(Product::getSku)
                .containsExactlyInAnyOrder("NG-EARBUD-01", "NG-SPEAKER-01", "NG-HEADPHONE-01");
    }

    @Test
    void findBySkuIgnoreCase_shouldReturnProduct() {
        var productOpt = productRepository.findBySkuIgnoreCase("ng-watch-01");
        assertThat(productOpt).isPresent();
        assertThat(productOpt.get().getName()).isEqualTo("Nova Smart Watch");
    }

    @Test
    void existsBySkuIgnoreCase_withExactAndCaseInsensitiveMatches() {
        // Exact match
        assertThat(productRepository.existsBySkuIgnoreCase("NG-EARBUD-01")).isTrue();

        // Lowercase
        assertThat(productRepository.existsBySkuIgnoreCase("ng-earbud-01")).isTrue();

        // Mixed case
        assertThat(productRepository.existsBySkuIgnoreCase("Ng-EaRbUd-01")).isTrue();

        // Non-existent SKU
        assertThat(productRepository.existsBySkuIgnoreCase("NON-EXISTENT-SKU")).isFalse();
    }

    @Test
    void findByIdForUpdate_shouldReturnProductWhenFound() {
        var existingProduct = productRepository.findBySku("NG-EARBUD-01").orElseThrow();
        var productOpt = productRepository.findByIdForUpdate(existingProduct.getId());

        assertThat(productOpt).isPresent();
        assertThat(productOpt.get().getSku()).isEqualTo("NG-EARBUD-01");

        var nonExistentOpt = productRepository.findByIdForUpdate(99999L);
        assertThat(nonExistentOpt).isEmpty();
    }
}
