package vn.danang.polaris.catalog.service;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.CategoryRepository;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.catalog.service.ProductService;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;
// What is this annotation (@ExtendWith(MockitoExtension.class)) used for in the test class?
// The `@ExtendWith(MockitoExtension.class)` annotation is used in the test class to enable the use of Mockito, 
// a popular mocking framework for unit tests in Java. This annotation integrates Mockito with JUnit 5,
// allowing you to create and manage mock objects easily within your test cases.
//When you annotate a test class with `@ExtendWith(MockitoExtension.class)`, it provides the following benefits:
//1. Automatic Initialization of Mocks: It automatically initializes fields annotated with `@Mock`, so you don't have to manually create mock instances.
//2. Cleaner Test Code: It reduces boilerplate code, making your test cases cleaner and more readable.
//3. Support for Mockito Annotations: It allows you to use other Mockito annotations like `@InjectMocks` to inject mock dependencies into the class under test.
//4. Integration with JUnit 5: It ensures that Mockito works seamlessly with JUnit 5's testing framework, 
// enabling you to write unit tests that leverage Mockito's capabilities for mocking and verifying interactions with dependencies.
//
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, categoryRepository);
    }

    @Test
    @DisplayName("adjustInventoryById should throw ResourceNotFoundException when product not found")
    void adjustInventoryById_notFound_throwsException() {
        when(productRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.adjustInventoryById(99L, 15))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found with id: 99");

        verify(productRepository).findByIdForUpdate(99L);
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("adjustInventory by SKU should acquire pessimistic lock and adjust stock by delta")
    void adjustInventory_success() {
        Product product = new Product();
        product.setId(2L);
        product.setSku("NG-TEST-02");
        product.setStockQty(20);

        when(productRepository.findBySkuIgnoreCaseForUpdate("NG-TEST-02")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product result = productService.adjustInventory("NG-TEST-02", 10);

        assertThat(result.getStockQty()).isEqualTo(30);
        verify(productRepository).findBySkuIgnoreCaseForUpdate("NG-TEST-02");
        verify(productRepository).save(product);
    }

    @Test
    @DisplayName("adjustInventory by SKU should throw IllegalArgumentException when resulting stock < 0")
    void adjustInventory_negativeResult_throwsException() {
        Product product = new Product();
        product.setId(2L);
        product.setSku("NG-TEST-02");
        product.setStockQty(5);

        when(productRepository.findBySkuIgnoreCaseForUpdate("NG-TEST-02")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.adjustInventory("NG-TEST-02", -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot adjust stock below 0. Current: 5, delta: -10");

        verify(productRepository).findBySkuIgnoreCaseForUpdate("NG-TEST-02");
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("adjustInventory by SKU should throw ResourceNotFoundException when product not found")
    void adjustInventory_notFound_throwsException() {
        when(productRepository.findBySkuIgnoreCaseForUpdate("UNKNOWN-SKU")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.adjustInventory("UNKNOWN-SKU", 5))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found with SKU: UNKNOWN-SKU");

        verify(productRepository).findBySkuIgnoreCaseForUpdate("UNKNOWN-SKU");
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("adjustInventoryById should acquire pessimistic lock and adjust stock by delta")
    void adjustInventoryById_success() {
        Product product = new Product();
        product.setId(1L);
        product.setSku("NG-TEST-01");
        product.setStockQty(20);

        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product result = productService.adjustInventoryById(1L, 15);

        assertThat(result.getStockQty()).isEqualTo(35);
        verify(productRepository).findByIdForUpdate(1L);
        verify(productRepository).save(product);
    }

    @Test
    @DisplayName("adjustInventoryById should throw IllegalArgumentException when resulting stock < 0")
    void adjustInventoryById_negativeResult_throwsException() {
        Product product = new Product();
        product.setId(1L);
        product.setSku("NG-TEST-01");
        product.setStockQty(5);

        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.adjustInventoryById(1L, -10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot adjust stock below 0. Current: 5, delta: -10");

        verify(productRepository).findByIdForUpdate(1L);
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("deductStock should acquire pessimistic lock by SKU and deduct available stock")
    void deductStock_success() {
        Product product = new Product();
        product.setId(2L);
        product.setSku("NG-TEST-02");
        product.setStockQty(20);

        when(productRepository.findBySkuIgnoreCaseForUpdate("NG-TEST-02")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product result = productService.deductStock("NG-TEST-02", 5);

        assertThat(result.getStockQty()).isEqualTo(15);
        verify(productRepository).findBySkuIgnoreCaseForUpdate("NG-TEST-02");
        verify(productRepository).save(product);
    }

    @Test
    @DisplayName("deductStock should throw InsufficientStockException when requested > current stock")
    void deductStock_insufficientStock_throwsException() {
        Product product = new Product();
        product.setId(2L);
        product.setSku("NG-TEST-02");
        product.setStockQty(3);

        when(productRepository.findBySkuIgnoreCaseForUpdate("NG-TEST-02")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.deductStock("NG-TEST-02", 10))
                .isInstanceOf(InsufficientStockException.class);

        verify(productRepository).findBySkuIgnoreCaseForUpdate("NG-TEST-02");
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("restoreStock should acquire pessimistic lock by SKU and increment stock")
    void restoreStock_success() {
        Product product = new Product();
        product.setId(2L);
        product.setSku("NG-TEST-02");
        product.setStockQty(5);

        when(productRepository.findBySkuIgnoreCaseForUpdate("NG-TEST-02")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product result = productService.restoreStock("NG-TEST-02", 10);

        assertThat(result.getStockQty()).isEqualTo(15);
        verify(productRepository).findBySkuIgnoreCaseForUpdate("NG-TEST-02");
        verify(productRepository).save(product);
    }
}
