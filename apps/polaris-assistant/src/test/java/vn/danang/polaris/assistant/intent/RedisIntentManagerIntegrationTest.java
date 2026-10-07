package vn.danang.polaris.assistant.intent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import vn.danang.polaris.assistant.config.AssistantIntentRedisProperties;

/**
 * Exercises {@link RedisIntentManager} against a real, ephemeral Redis instance to verify the
 * seeding and read path end-to-end, and the fallback when Redis hangs (container paused). Other
 * failure/fallback branches are covered with mocks in {@link RedisIntentManagerTest}.
 */
@Testcontainers
class RedisIntentManagerIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /** Same as the {@code spring.data.redis.timeout} default in application.yml. */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);

    private StringRedisTemplate redisTemplate;
    private CountingFallback fallback;
    private AssistantIntentRedisProperties properties;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        fallback = new CountingFallback(
                List.of(new IntentDefinition("general.conversation", "fallback", List.of("hi"))));

        properties = new AssistantIntentRedisProperties();
        // Unique per test so tests don't interact with each other's data in the shared container.
        properties.setKey("test:intents:" + UUID.randomUUID());
    }

    @Test
    @DisplayName("Given an empty Redis, when constructed, then seeds it with the default taxonomy and serves that taxonomy")
    void seeds_and_reads_from_real_redis() {
        RedisIntentManager manager = new RedisIntentManager(redisTemplate, fallback, properties);

        assertThat(manager.listIntents())
                .extracting(IntentDefinition::id)
                .containsExactly("general.conversation");
        assertThat(redisTemplate.opsForValue().get(properties.getKey())).isNotBlank();
    }

    @Test
    @DisplayName("Given Redis was seeded by this taxonomy and then edited at runtime, when restarted, then the runtime edit is served, not overwritten")
    void keeps_runtime_edits_while_classpath_taxonomy_is_unchanged() throws Exception {
        new RedisIntentManager(redisTemplate, fallback, properties);
        String json = new ObjectMapper().writeValueAsString(
                List.of(new IntentDefinition("catalog.product.search", "from redis", List.of("find product"))));
        redisTemplate.opsForValue().set(properties.getKey(), json);

        RedisIntentManager manager = new RedisIntentManager(redisTemplate, fallback, properties);

        assertThat(manager.listIntents())
                .extracting(IntentDefinition::id)
                .containsExactly("catalog.product.search");
    }

    @Test
    @DisplayName("Given Redis holds a taxonomy from an older release, when a release with a changed taxonomy starts, then the classpath taxonomy wins")
    void classpath_taxonomy_overwrites_stale_redis() throws Exception {
        // Seeded by an older release: with SETNX-only seeding (no source hash), this content was never replaced.
        String stale = new ObjectMapper().writeValueAsString(
                List.of(new IntentDefinition("commerce.order.place", "old tools", List.of("order"))));
        redisTemplate.opsForValue().set(properties.getKey(), stale);

        RedisIntentManager manager = new RedisIntentManager(redisTemplate, fallback, properties);

        assertThat(manager.listIntents())
                .extracting(IntentDefinition::id)
                .containsExactly("general.conversation");
        assertThat(redisTemplate.opsForValue().get(properties.getKey() + ":source-sha256")).isNotBlank();

        // A later release changes the taxonomy again: the new hash differs, so Redis is overwritten again.
        DefaultIntentManager newer = new DefaultIntentManager(List.of(
                new IntentDefinition("general.conversation", "fallback v2", List.of("hi"))));
        RedisIntentManager upgraded = new RedisIntentManager(redisTemplate, newer, properties);
        assertThat(upgraded.listIntents()).extracting(IntentDefinition::description).containsExactly("fallback v2");
    }

    @Test
    @DisplayName("Given Redis hangs, when intents are read, then the read fails fast and the default taxonomy is served")
    void hung_redis_fails_fast_and_falls_back() throws Exception {
        properties.setLocalCacheTtl(Duration.ZERO);
        RedisIntentManager manager = new RedisIntentManager(redisTemplate, fallback, properties);
        redisTemplate.opsForValue().set(properties.getKey(), new ObjectMapper().writeValueAsString(
                List.of(new IntentDefinition("catalog.product.search", "from redis", List.of("find product")))));
        assertThat(manager.listIntents()).extracting(IntentDefinition::id).containsExactly("catalog.product.search");

        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            List<IntentDefinition> intents = manager.listIntents();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(intents).extracting(IntentDefinition::id).containsExactly("general.conversation");
            // Generous bound; without a command timeout Lettuce would wait 60 s.
            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("Given Redis hangs, when several callers find the cache expired at once, then one refresh hits Redis and the fallback, the others wait for it, and all return fast")
    void hung_redis_concurrent_callers_share_one_refresh() throws Exception {
        int callers = 8;
        RedisIntentManager manager = new RedisIntentManager(redisTemplate, fallback, properties);
        long getsBefore = redisGetCalls();
        fallback.calls.set(0);
        // The refreshing caller enters the fallback only once every other caller is queued behind it, so the
        // single-flight check runs against a known interleaving rather than a timing-dependent one.
        fallback.beforeServing = () -> awaitWaiters(manager, callers - 1);

        List<Future<Duration>> results = new ArrayList<>();
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    long t0 = System.nanoTime();
                    List<IntentDefinition> intents = manager.listIntents();
                    assertThat(intents).extracting(IntentDefinition::id).containsExactly("general.conversation");
                    return Duration.ofNanos(System.nanoTime() - t0);
                }));
            }
            start.countDown();
            for (Future<Duration> result : results) {
                // Generous bound: one 1 s command timeout, shared by every caller; Lettuce's default is 60 s.
                assertThat(result.get(10, TimeUnit.SECONDS)).isLessThan(Duration.ofSeconds(10));
            }
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }

        assertThat(fallback.calls).as("fallback loads").hasValue(1);
        assertThat(redisGetCalls() - getsBefore).as("Redis GETs").isEqualTo(1);
    }

    /** Blocks until {@code expected} callers are parked behind the in-flight refresh (fails after 10 s). */
    private static void awaitWaiters(RedisIntentManager manager, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (manager.refreshWaiters() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + manager.refreshWaiters() + " of " + expected + " callers queued behind the refresh");
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Redis GET commands executed so far, from {@code INFO commandstats}. The template shares one Lettuce connection,
     * which runs commands in order, so this INFO also waits for any GET that timed out on the client while Redis was
     * paused and was executed once it resumed.
     */
    private long redisGetCalls() {
        String info = redisTemplate.execute((RedisCallback<String>) connection ->
                String.valueOf(connection.serverCommands().info("commandstats").getProperty("cmdstat_get")));
        if (info == null || "null".equals(info)) {
            return 0;
        }
        // e.g. calls=3,usec=12,usec_per_call=4.00,rejected_calls=0,failed_calls=0
        return Long.parseLong(info.replaceAll("^calls=(\\d+),.*$", "$1"));
    }

    /** Fallback taxonomy that counts its loads and can hold the refreshing caller at a chosen point. */
    private static final class CountingFallback extends DefaultIntentManager {
        final AtomicInteger calls = new AtomicInteger();
        volatile Runnable beforeServing = () -> {};

        CountingFallback(List<IntentDefinition> intents) {
            super(intents);
        }

        @Override
        public List<IntentDefinition> listIntents() {
            calls.incrementAndGet();
            beforeServing.run();
            return super.listIntents();
        }
    }
}
