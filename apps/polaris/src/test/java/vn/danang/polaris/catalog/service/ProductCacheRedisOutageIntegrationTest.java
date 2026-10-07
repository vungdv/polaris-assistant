package vn.danang.polaris.catalog.service;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.config.CacheConfig;

/**
 * Verifies the Redis-outage fallback of the two-layer product cache ({@link CacheConfig}): while
 * Redis is unresponsive, every L2 read and write fails within the configured Redis command
 * timeout ({@code spring.data.redis.timeout}), and the cached operation still succeeds on the
 * in-process L1 cache and the database.
 *
 * <p>The Redis container is paused (frozen, not stopped), so the TCP connection stays open and
 * commands get no answer: the hung-Redis case that only a command timeout bounds. Without one,
 * Lettuce waits 60 s per command, so the elapsed-time bounds below would fail.
 */
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class ProductCacheRedisOutageIntegrationTest {

    /** Per cached call; a call makes at most two L2 round trips (a read and a write, or two evictions). */
    private static final Duration FAIL_FAST_BOUND = Duration.ofSeconds(10);

    @Autowired
    private ProductService productService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private LettuceConnectionFactory redisConnectionFactory;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redisContainer;

    @MockitoSpyBean
    private ProductRepository productRepository;

    private Long productId;

    @BeforeEach
    void setUp() {
        cacheManager.getCache(CacheConfig.PRODUCTS_CACHE).clear();
        productId = productRepository.findBySkuIgnoreCase("NG-CHARGER-02").orElseThrow().getId();
        clearInvocations(productRepository);
    }

    @AfterEach
    void resumeRedis() {
        if (isRedisPaused()) {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
        // Drops what this test left in either tier (Redis has answered the queued commands by now).
        cacheManager.getCache(CacheConfig.PRODUCTS_CACHE).clear();
    }

    @Test
    @DisplayName("the Redis command timeout is short, not Lettuce's 60 s default")
    void redisCommandTimeout_isShort() {
        assertThat(redisConnectionFactory.getClientConfiguration().getCommandTimeout())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("with Redis hung, a read fails fast on L2, is served from the database, and then from L1")
    void redisHung_readFailsFastAndFallsBackToDatabaseThenLocalCache() {
        pauseRedis();

        long start = System.nanoTime();
        ProductResponse first = productService.getProductById(productId);
        Duration firstRead = Duration.ofNanos(System.nanoTime() - start);

        assertThat(first.id()).isEqualTo(productId);
        assertThat(firstRead).isLessThan(FAIL_FAST_BOUND);
        verify(productRepository, times(1)).findById(eq(productId));

        // The failed L2 put still populated L1, so the next read needs neither Redis nor the database.
        long secondStart = System.nanoTime();
        ProductResponse second = productService.getProductById(productId);
        Duration secondRead = Duration.ofNanos(System.nanoTime() - secondStart);

        assertThat(second).isEqualTo(first);
        assertThat(secondRead).isLessThan(FAIL_FAST_BOUND);
        verify(productRepository, times(1)).findById(eq(productId));
    }

    @Test
    @DisplayName("with Redis hung, an inventory change fails fast on its L2 evictions and the next read sees fresh stock")
    void redisHung_evictionFailsFastAndLocalCacheIsStillEvicted() {
        ProductResponse before = productService.getProductById(productId);
        pauseRedis();

        long start = System.nanoTime();
        productService.adjustInventoryById(productId, 4);
        Duration adjust = Duration.ofNanos(System.nanoTime() - start);

        assertThat(adjust).isLessThan(FAIL_FAST_BOUND);
        long readStart = System.nanoTime();
        ProductResponse after = productService.getProductById(productId);
        Duration read = Duration.ofNanos(System.nanoTime() - readStart);

        assertThat(after.stockQuantity()).isEqualTo(before.stockQuantity() + 4);
        assertThat(read).isLessThan(FAIL_FAST_BOUND);
    }

    private void pauseRedis() {
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
    }

    private boolean isRedisPaused() {
        return Boolean.TRUE.equals(redisContainer.getDockerClient()
                .inspectContainerCmd(redisContainer.getContainerId()).exec().getState().getPaused());
    }
}
