package vn.danang.polaris.catalog.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
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
 * <p>L2 writes are immediate ({@link CacheConfig} configures the Redis cache writer that way), so
 * every assertion checks a key's Redis state right after the call that put or evicted it.
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class ProductCacheIntegrationTest {

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

        boolean exists = inRedis(idKey(productId));
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("a cold L1 (e.g. after eviction/restart) is served from Redis L2, not the database")
    void getProductById_afterLocalEviction_servedFromRedisWithoutDbHit() {
        productService.getProductById(productId);
        assertThat(inRedis(idKey(productId))).isTrue();
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
        assertThat(inRedis(idKey(productId))).isTrue();

        productService.adjustInventoryById(productId, 7);
        assertThat(inRedis(idKey(productId))).isFalse();
        ProductResponse after = productService.getProductById(productId);

        assertThat(after.stockQuantity()).isEqualTo(before.stockQuantity() + 7);
        // Re-populated by the read above with the fresh value, not left stale.
        assertThat(inRedis(idKey(productId))).isTrue();
        verify(productRepository, times(2)).findById(eq(productId));
    }

    @Test
    @DisplayName("adjustInventory by SKU evicts the cache entry keyed by the product's ID")
    void adjustInventory_bySku_evictsCacheKeyedById() {
        productService.getProductById(productId);
        assertThat(inRedis(idKey(productId))).isTrue();

        productService.adjustInventory("NG-CHARGER-02", -5);
        assertThat(inRedis(idKey(productId))).isFalse();
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
        assertThat(inRedis(skuKey("NG-CHARGER-02"))).isTrue();
        clearInvocations(productRepository);

        productService.adjustInventoryById(productId, 3);
        assertThat(inRedis(skuKey("NG-CHARGER-02"))).isFalse();
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
        assertThat(inRedis(idKey(created.id()))).isTrue();
        assertThat(inRedis(skuKey("NG-CACHE-TEST-01"))).isTrue();
        clearInvocations(productRepository);

        ProductResponse byId = productService.getProductById(created.id());
        ProductResponse bySku = productService.getProductBySku("NG-CACHE-TEST-01");

        assertThat(byId).isEqualTo(created);
        assertThat(bySku).isEqualTo(created);
        verify(productRepository, times(0)).findById(eq(created.id()));
        verify(productRepository, times(0)).findBySkuIgnoreCase("NG-CACHE-TEST-01");
    }

    @Test
    @DisplayName("L2 puts, evictions and clears have landed in Redis when the cache call returns")
    void redisWrites_areVisibleAsSoonAsTheCacheCallReturns() {
        ProductResponse product = productService.getProductById(productId);
        Cache products = cacheManager.getCache(CacheConfig.PRODUCTS_CACHE);

        products.put(productId, product);
        products.put(localSkuKey("NG-CHARGER-02"), product);
        assertThat(inRedis(idKey(productId))).isTrue();

        products.evict(productId);
        assertThat(inRedis(idKey(productId))).isFalse();

        products.put(productId, product);
        assertThat(inRedis(idKey(productId))).isTrue();
        assertThat(inRedis(skuKey("NG-CHARGER-02"))).isTrue();
        products.clear();
        assertThat(inRedis(idKey(productId))).isFalse();
        assertThat(inRedis(skuKey("NG-CHARGER-02"))).isFalse();
        // A cold L1 must now fall through to the database, not to a stale L2 entry still awaiting deletion.
        clearInvocations(productRepository);
        productService.getProductById(productId);
        verify(productRepository, times(1)).findById(eq(productId));
    }

    @Test
    @DisplayName("clear() removes every cache key with SCAN, across several batches, never KEYS, and leaves other keys alone")
    void clear_usesScanAcrossBatches_andLeavesOtherKeys() {
        int entries = 2 * CacheConfig.CLEAR_SCAN_BATCH_SIZE + 500;
        Map<String, String> keys = new HashMap<>();
        for (int i = 0; i < entries; i++) {
            keys.put(CacheConfig.PRODUCTS_CACHE + "::bulk-" + i, "x");
        }
        redisTemplate.opsForValue().multiSet(keys);
        String unrelated = "not-a-cache-key:" + System.nanoTime();
        redisTemplate.opsForValue().set(unrelated, "keep");
        long keysBefore = commandCalls("keys");
        long scansBefore = commandCalls("scan");

        try {
            cacheManager.getCache(CacheConfig.PRODUCTS_CACHE).clear();

            assertThat(redisTemplate.countExistingKeys(keys.keySet())).isZero();
            assertThat(inRedis(unrelated)).isTrue();
            assertThat(commandCalls("keys") - keysBefore).as("KEYS calls").isZero();
            assertThat(commandCalls("scan") - scansBefore).as("SCAN calls").isGreaterThan(1);
        } finally {
            redisTemplate.delete(unrelated);
        }
    }

    // Order placement evicts only after commit, and this class's test transaction never commits,
    // so these tests opt out of it and remove the order they create themselves.

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("placing and cancelling an order evicts the product's id- and SKU-keyed entries after commit")
    void placeAndCancelOrder_evictCacheAfterCommit_nextReadsSeeFreshStock() {
        ProductResponse before = productService.getProductBySku("NG-CHARGER-02");
        productService.getProductById(productId);
        assertThat(inRedis(skuKey("NG-CHARGER-02"))).isTrue();
        assertThat(inRedis(idKey(productId))).isTrue();

        Order order = orderService.placeOrder(1L, List.of(new OrderItemRequest("NG-CHARGER-02", 2)), null);
        try {
            // Both tiers are evicted synchronously in afterCommit
            assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(localSkuKey("NG-CHARGER-02"))).isNull();
            assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(productId)).isNull();
            assertThat(inRedis(skuKey("NG-CHARGER-02"))).isFalse();
            assertThat(inRedis(idKey(productId))).isFalse();
            assertThat(productService.getProductBySku("NG-CHARGER-02").stockQuantity()).isEqualTo(before.stockQuantity() - 2);
            assertThat(productService.getProductById(productId).stockQuantity()).isEqualTo(before.stockQuantity() - 2);
            assertThat(inRedis(skuKey("NG-CHARGER-02"))).isTrue();

            orderService.cancelOrder(order.getOrderNumber());

            assertThat(localCacheManager.getCache(CacheConfig.PRODUCTS_CACHE).get(localSkuKey("NG-CHARGER-02"))).isNull();
            assertThat(inRedis(skuKey("NG-CHARGER-02"))).isFalse();
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
        assertThat(inRedis(skuKey("NG-CHARGER-02"))).isTrue();

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

    /** Times Redis has executed {@code command}, from {@code INFO commandstats}. */
    private long commandCalls(String command) {
        String stats = redisTemplate.execute((RedisCallback<String>) connection ->
                connection.serverCommands().info("commandstats").getProperty("cmdstat_" + command));
        // e.g. calls=3,usec=12,usec_per_call=4.00,rejected_calls=0,failed_calls=0
        return stats == null ? 0 : Long.parseLong(stats.replaceAll("^calls=(\\d+),.*$", "$1"));
    }

    private boolean inRedis(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
