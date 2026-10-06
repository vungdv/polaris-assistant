package vn.danang.polaris.catalog.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.dto.CreateProductRequest;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.config.CacheConfig;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.exception.PriceChangedException;

/**
 * Verifies the two-layer (Caffeine L1 / Redis L2) cache wired up in {@link CacheConfig} for
 * {@link ProductService}: repeated reads (by id or by SKU) are served without hitting the
 * database, values actually land in Redis (not just the in-process tier), a cold L1 falls back
 * to L2 rather than the database, {@code createProduct}'s {@code @CachePut} primes both keys for
 * a new product, and every mutation evicts both the id- and SKU-keyed entries so neither lookup
 * path serves stale data. That includes stock changed by placing or cancelling an order, which
 * evicts only once the order transaction commits.
 *
 * <p>The Redis write path in this environment commits the underlying SET/DEL slightly after the
 * Java call returns (a sub-millisecond window, invisible to any real request but reproducible in
 * a zero-delay test), so assertions that depend on a key's Redis-visible state use
 * {@link #awaitRedisKey} to poll for that state rather than checking it immediately.
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class ProductCacheIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(2);

    @Autowired
    private ProductService productService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CaffeineCacheManager localCacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    private Long productId;

    @BeforeEach
    void setUp() {
        cacheManager.getCache(CacheConfig.PRODUCTS_CACHE).clear();
        productId = productRepository.findBySkuIgnoreCase("NG-CHARGER-02").orElseThrow().getId();
        clearInvocations(productRepository);
    }

    @Test
    @DisplayName("repeated getProductById calls hit the database only once")
    void getProductById_repeatedCalls_hitsDatabaseOnce() {
        ProductResponse first = productService.getProductById(productId);
        ProductResponse second = productService.getProductById(productId);
        ProductResponse third = productService.getProductById(productId);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        verify(productRepository, times(1)).findById(eq(productId));
    }

    @Test
    @DisplayName("a successful read writes through to the Redis L2 cache")
    void getProductById_writesThroughToRedis() {
        productService.getProductById(productId);

        boolean exists = awaitRedisKey(idKey(productId), true);
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("a cold L1 (e.g. after eviction/restart) is served from Redis L2, not the database")
    void getProductById_afterLocalEviction_servedFromRedisWithoutDbHit() {
        productService.getProductById(productId);
        awaitRedisKey(idKey(productId), true);
        localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).clear();
        clearInvocations(productRepository);

        ProductResponse fromRedis = productService.getProductById(productId);

        assertThat(fromRedis.id()).isEqualTo(productId);
        verify(productRepository, times(0)).findById(eq(productId));
    }

    @Test
    @DisplayName("adjustInventoryById evicts both cache tiers so the next read sees fresh stock")
    void adjustInventoryById_evictsCache_nextReadSeesFreshStock() {
        ProductResponse before = productService.getProductById(productId);
        awaitRedisKey(idKey(productId), true);

        productService.adjustInventoryById(productId, 7);
        awaitRedisKey(idKey(productId), false);
        ProductResponse after = productService.getProductById(productId);

        assertThat(after.stockQuantity()).isEqualTo(before.stockQuantity() + 7);
        // Re-populated by the read above with the fresh value, not left stale.
        assertThat(awaitRedisKey(idKey(productId), true)).isTrue();
        verify(productRepository, times(2)).findById(eq(productId));
    }

    @Test
    @DisplayName("adjustInventory by SKU evicts the cache entry keyed by the product's ID")
    void adjustInventory_bySku_evictsCacheKeyedById() {
        productService.getProductById(productId);
        awaitRedisKey(idKey(productId), true);

        productService.adjustInventory("NG-CHARGER-02", -5);
        awaitRedisKey(idKey(productId), false);
        ProductResponse after = productService.getProductById(productId);

        verify(productRepository, times(2)).findById(eq(productId));
        assertThat(after.stockQuantity()).isNotNull();
    }

    @Test
    @DisplayName("repeated getProductBySku calls hit the database only once")
    void getProductBySku_repeatedCalls_hitsDatabaseOnce() {
        ProductResponse first = productService.getProductBySku("NG-CHARGER-02");
        ProductResponse second = productService.getProductBySku("NG-CHARGER-02");

        assertThat(second).isEqualTo(first);
        verify(productRepository, times(1)).findBySkuIgnoreCase("NG-CHARGER-02");
    }

    @Test
    @DisplayName("adjustInventoryById also evicts the SKU-keyed cache entry, not just the id-keyed one")
    void adjustInventoryById_evictsSkuKeyedCacheToo() {
        productService.getProductBySku("NG-CHARGER-02");
        awaitRedisKey(skuKey("NG-CHARGER-02"), true);
        clearInvocations(productRepository);

        productService.adjustInventoryById(productId, 3);
        awaitRedisKey(skuKey("NG-CHARGER-02"), false);
        productService.getProductBySku("NG-CHARGER-02");

        // The id-keyed mutation must also evict the sku-keyed entry, or this would be 0.
        verify(productRepository, times(1)).findBySkuIgnoreCase("NG-CHARGER-02");
    }

    @Test
    @DisplayName("createProduct primes the cache by id and by SKU, so the very next reads skip the database")
    void createProduct_primesCacheByIdAndSku() {
        CreateProductRequest request = new CreateProductRequest(
                "NG-CACHE-TEST-01", "Cache Priming Test Widget", null, null, null,
                new BigDecimal("9.99"), 5, true);

        ProductResponse created = productService.createProduct(request);
        awaitRedisKey(idKey(created.id()), true);
        awaitRedisKey(skuKey("NG-CACHE-TEST-01"), true);
        clearInvocations(productRepository);

        ProductResponse byId = productService.getProductById(created.id());
        ProductResponse bySku = productService.getProductBySku("NG-CACHE-TEST-01");

        assertThat(byId).isEqualTo(created);
        assertThat(bySku).isEqualTo(created);
        verify(productRepository, times(0)).findById(eq(created.id()));
        verify(productRepository, times(0)).findBySkuIgnoreCase("NG-CACHE-TEST-01");
    }

    // Order placement evicts only after commit, and this class's test transaction never commits,
    // so these tests opt out of it and remove the order they create themselves.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("placing and cancelling an order evicts the product's id- and SKU-keyed entries after commit")
    void placeAndCancelOrder_evictCacheAfterCommit_nextReadsSeeFreshStock() {
        ProductResponse before = productService.getProductBySku("NG-CHARGER-02");
        productService.getProductById(productId);
        assertThat(awaitRedisKey(skuKey("NG-CHARGER-02"), true)).isTrue();
        assertThat(awaitRedisKey(idKey(productId), true)).isTrue();

        Order order = orderService.placeOrder(1L, List.of(new OrderItemRequest("NG-CHARGER-02", 2)), null);
        try {
            // L1 eviction happens synchronously in afterCommit; the Redis DEL is polled for (see class Javadoc)
            assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(localSkuKey("NG-CHARGER-02"))).isNull();
            assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(productId)).isNull();
            assertThat(awaitRedisKey(skuKey("NG-CHARGER-02"), false)).isFalse();
            assertThat(awaitRedisKey(idKey(productId), false)).isFalse();
            assertThat(productService.getProductBySku("NG-CHARGER-02").stockQuantity()).isEqualTo(before.stockQuantity() - 2);
            assertThat(productService.getProductById(productId).stockQuantity()).isEqualTo(before.stockQuantity() - 2);
            assertThat(awaitRedisKey(skuKey("NG-CHARGER-02"), true)).isTrue();

            orderService.cancelOrder(order.getOrderNumber());

            assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(localSkuKey("NG-CHARGER-02"))).isNull();
            assertThat(awaitRedisKey(skuKey("NG-CHARGER-02"), false)).isFalse();
            assertThat(productService.getProductBySku("NG-CHARGER-02").stockQuantity()).isEqualTo(before.stockQuantity());
        } finally {
            orderRepository.findByOrderNumber(order.getOrderNumber()).ifPresent(orderRepository::delete);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("a rejected order rolls back and leaves the cached product untouched")
    void rejectedOrder_rollsBack_leavesCacheUntouched() {
        productService.getProductBySku("NG-CHARGER-02");
        assertThat(awaitRedisKey(skuKey("NG-CHARGER-02"), true)).isTrue();

        assertThatThrownBy(() -> orderService.placeOrder(1L,
                List.of(new OrderItemRequest("NG-CHARGER-02", 1, new BigDecimal("0.01"))), null))
                .isInstanceOf(PriceChangedException.class);

        assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(localSkuKey("NG-CHARGER-02"))).isNotNull();
        assertThat(redisTemplate.hasKey(skuKey("NG-CHARGER-02"))).isTrue();
    }

    /** Key of a SKU lookup inside the {@code products} cache (the Redis key adds the {@code products::} prefix). */
    private static String localSkuKey(String sku) {
        return CacheConfig.productSkuKey(sku);
    }

    private static String idKey(Long id) {
        return CacheConfig.PRODUCTS_CACHE + "::" + id;
    }

    private static String skuKey(String sku) {
        return CacheConfig.PRODUCTS_CACHE + "::" + CacheConfig.productSkuKey(sku);
    }

    /**
     * Polls the given Redis key's presence until it matches {@code expectedPresent} or
     * {@link #AWAIT_TIMEOUT} elapses, returning the last observed state. The put/evict commands
     * issued by the cache land asynchronously relative to the Java call that triggers them, so
     * checking a key's state right after such a call is inherently racy without this.
     */
    private boolean awaitRedisKey(String key, boolean expectedPresent) {
        Supplier<Boolean> present = () -> Boolean.TRUE.equals(redisTemplate.hasKey(key));
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        boolean last = present.get();
        while (last != expectedPresent && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            last = present.get();
        }
        return last;
    }
}
