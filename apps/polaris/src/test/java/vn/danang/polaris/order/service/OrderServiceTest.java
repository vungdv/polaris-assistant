package vn.danang.polaris.order.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.web.exception.IdempotencyKeyReusedException;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.exception.PriceChangedException;
import vn.danang.polaris.web.exception.ProductInactiveException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the branches of {@link OrderService#place} that are hard to force against a real database:
 * losing the idempotency-key race on the unique index, and the price/stock guards on merged lines.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Unit Tests")
class OrderServiceTest {

    private static final String KEY = "key-1";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private PlatformTransactionManager transactionManager;

    private OrderService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        service = new OrderService(orderRepository, productRepository, customerRepository, cacheManager, transactionManager);
    }

    private static Customer customer(long id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName("Customer " + id);
        return customer;
    }

    private static Product product(long id, String sku, String price, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName("Product " + sku);
        product.setPrice(new BigDecimal(price));
        product.setStockQty(stock);
        return product;
    }

    private static Order existingOrder(long customerId) {
        Order order = new Order();
        order.setOrderNumber("ORD-000042");
        order.setCustomer(customer(customerId));
        order.setIdempotencyKey(KEY);
        return order;
    }

    private void stubHappyPathUpToInsert(Product product) {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(productRepository.findBySkuIgnoreCaseForUpdate(product.getSku())).thenReturn(Optional.of(product));
        when(orderRepository.nextOrderNumberValue()).thenReturn(7L);
    }

    @Test
    @DisplayName("losing the idempotency race on the unique index returns the winner's order as a replay")
    void place_uniqueViolationOnKey_returnsWinnersOrder() {
        stubHappyPathUpToInsert(product(10L, "SKU-A", "5.00", 10));
        Order winner = existingOrder(1L);
        when(orderRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        when(orderRepository.save(any(Order.class))).thenThrow(new DataIntegrityViolationException("idx_orders_idempotency_key"));

        OrderService.Placement placement = service.place(1L, List.of(new OrderItemRequest("SKU-A", 1)), KEY);

        assertThat(placement.replayed()).isTrue();
        assertThat(placement.order()).isSameAs(winner);
        verify(orderRepository, times(3)).findByIdempotencyKey(KEY);
    }

    @Test
    @DisplayName("losing the race to another customer's order is reported as idempotency-key-reused")
    void place_uniqueViolationOnKey_otherCustomer_throwsKeyReused() {
        stubHappyPathUpToInsert(product(10L, "SKU-A", "5.00", 10));
        when(orderRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(existingOrder(2L)));
        when(orderRepository.save(any(Order.class))).thenThrow(new DataIntegrityViolationException("idx_orders_idempotency_key"));

        assertThatThrownBy(() -> service.place(1L, List.of(new OrderItemRequest("SKU-A", 1)), KEY))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("a unique violation without an idempotency key is not swallowed")
    void place_uniqueViolationWithoutKey_rethrows() {
        stubHappyPathUpToInsert(product(10L, "SKU-A", "5.00", 10));
        when(orderRepository.save(any(Order.class))).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.place(1L, List.of(new OrderItemRequest("SKU-A", 1)), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(orderRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    @DisplayName("an existing order for the key and customer is replayed without locking or deducting anything")
    void place_existingKeySameCustomer_replaysWithoutWrites() {
        Order existing = existingOrder(1L);
        when(orderRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing));

        OrderService.Placement placement = service.place(1L, List.of(new OrderItemRequest("SKU-A", 1)), " " + KEY + " ");

        assertThat(placement.replayed()).isTrue();
        assertThat(placement.order()).isSameAs(existing);
        verify(productRepository, never()).findBySkuIgnoreCaseForUpdate(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("price differing from expectedUnitPrice rejects the order before any stock change")
    void place_priceChanged_throwsWithChangedLines() {
        Product product = product(10L, "SKU-A", "5.00", 10);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(productRepository.findBySkuIgnoreCaseForUpdate("SKU-A")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.place(1L,
                List.of(new OrderItemRequest("SKU-A", 1, new BigDecimal("4.50"))), null))
                .isInstanceOfSatisfying(PriceChangedException.class, ex -> {
                    assertThat(ex.getChangedLines()).hasSize(1);
                    assertThat(ex.getChangedLines().get(0).sku()).isEqualTo("SKU-A");
                    assertThat(ex.getChangedLines().get(0).expectedUnitPrice()).isEqualByComparingTo("4.50");
                    assertThat(ex.getChangedLines().get(0).currentUnitPrice()).isEqualByComparingTo("5.00");
                });
        assertThat(product.getStockQty()).isEqualTo(10);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("repeated SKU lines are locked once and checked against stock with the summed quantity")
    void place_repeatedSkuLines_mergedForStockCheck() {
        Product product = product(10L, "SKU-A", "5.00", 5);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(productRepository.findBySkuIgnoreCaseForUpdate("SKU-A")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.place(1L,
                List.of(new OrderItemRequest("SKU-A", 3), new OrderItemRequest("sku-a", 3)), null))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.getRequestedQuantity()).isEqualTo(6);
                    assertThat(ex.getAvailableQuantity()).isEqualTo(5);
                });
        verify(productRepository, times(1)).findBySkuIgnoreCaseForUpdate(any());
        assertThat(product.getStockQty()).isEqualTo(5);
    }

    @Test
    @DisplayName("order numbers are formatted from the database sequence")
    void place_orderNumberFromSequence() {
        stubHappyPathUpToInsert(product(10L, "SKU-A", "5.00", 10));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService.Placement placement = service.place(1L, List.of(new OrderItemRequest("SKU-A", 2)), null);

        assertThat(placement.replayed()).isFalse();
        assertThat(placement.order().getOrderNumber()).isEqualTo("ORD-000007");
        assertThat(placement.order().getTotalAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("inactive products are rejected under lock with every inactive SKU listed")
    void place_inactiveProducts_throwsWithSkus() {
        Product active = product(10L, "SKU-A", "5.00", 10);
        Product inactive = product(11L, "SKU-B", "5.00", 10);
        inactive.setIsActive(false);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(productRepository.findBySkuIgnoreCaseForUpdate("SKU-A")).thenReturn(Optional.of(active));
        when(productRepository.findBySkuIgnoreCaseForUpdate("SKU-B")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.place(1L,
                List.of(new OrderItemRequest("SKU-B", 1), new OrderItemRequest("SKU-A", 1)), null))
                .isInstanceOfSatisfying(ProductInactiveException.class,
                        ex -> assertThat(ex.getSkus()).containsExactly("SKU-B"));
        verify(orderRepository, never()).save(any());
        assertThat(inactive.getStockQty()).isEqualTo(10);
    }

    @Test
    @DisplayName("an idempotency key longer than the column is rejected before touching the database")
    void place_idempotencyKeyTooLong_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.place(1L, List.of(new OrderItemRequest("SKU-A", 1)), "k".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 100");
        verify(orderRepository, never()).findByIdempotencyKey(any());
    }
}
