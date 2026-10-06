package vn.danang.polaris.assistant.intent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import vn.danang.polaris.assistant.config.AssistantIntentRedisProperties;

/**
 * Verifies {@link RedisIntentManager}'s two-layer caching (in-process cache in front of Redis)
 * and its fallback to {@link DefaultIntentManager} whenever Redis is unreachable, empty, or
 * returns unparseable data. Redis itself is mocked here; wiring against a real Redis instance
 * is covered by the Testcontainers-backed {@code RedisIntentManagerIntegrationTest}.
 */
class RedisIntentManagerTest {

    private static final IntentDefinition REDIS_INTENT =
            new IntentDefinition("catalog.product.search", "From Redis", List.of("find product"));
    private static final IntentDefinition FALLBACK_INTENT =
            new IntentDefinition("general.conversation", "From classpath fallback", List.of("hello"));

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DefaultIntentManager fallback;
    private AssistantIntentRedisProperties properties;
    private MutableClock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        fallback = new DefaultIntentManager(List.of(FALLBACK_INTENT));

        properties = new AssistantIntentRedisProperties();
        properties.setKey("polaris:assistant:intents");
        properties.setLocalCacheTtl(Duration.ofSeconds(30));
        properties.setFallbackRetryInterval(Duration.ofSeconds(5));
        properties.setSeedIfMissing(false);

        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private RedisIntentManager newManager() {
        return new RedisIntentManager(redisTemplate, fallback, properties, clock);
    }

    // =========================================================================
    // 1. Happy path — reading from Redis and serving from the local cache
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given intents stored in Redis, when listIntents called, then returns the Redis-backed taxonomy")
        void lists_intents_loaded_from_redis() throws Exception {
            when(valueOperations.get(properties.getKey())).thenReturn(toJson(REDIS_INTENT));
            RedisIntentManager manager = newManager();

            assertThat(manager.listIntents())
                    .extracting(IntentDefinition::id)
                    .containsExactly("catalog.product.search");
        }

        @Test
        @DisplayName("Given a known intent id, when getIntent called, then returns the matching definition from Redis")
        void gets_intent_by_id_from_redis() throws Exception {
            when(valueOperations.get(properties.getKey())).thenReturn(toJson(REDIS_INTENT));
            RedisIntentManager manager = newManager();

            assertThat(manager.getIntent("catalog.product.search")).contains(REDIS_INTENT);
        }

        @Test
        @DisplayName("Given repeated calls within the local cache TTL, when listIntents called, then Redis is queried only once")
        void serves_from_local_cache_within_ttl() throws Exception {
            when(valueOperations.get(properties.getKey())).thenReturn(toJson(REDIS_INTENT));
            RedisIntentManager manager = newManager();

            manager.listIntents();
            clock.advance(Duration.ofSeconds(10));
            manager.listIntents();
            manager.getIntent("catalog.product.search");

            verify(valueOperations, times(1)).get(properties.getKey());
        }

        @Test
        @DisplayName("Given the local cache has expired, when listIntents called again, then Redis is queried again")
        void refreshes_from_redis_after_local_cache_expires() throws Exception {
            when(valueOperations.get(properties.getKey())).thenReturn(toJson(REDIS_INTENT));
            RedisIntentManager manager = newManager();
            manager.listIntents();

            clock.advance(properties.getLocalCacheTtl().plusSeconds(1));
            manager.listIntents();

            verify(valueOperations, times(2)).get(properties.getKey());
        }
    }

    // =========================================================================
    // 2. Fallback behavior — Redis unavailable, empty, or unparseable
    // =========================================================================
    @Nested
    @DisplayName("2. Fallback behavior")
    class FallbackBehavior {

        @Test
        @DisplayName("Given Redis has no value for the key, when listIntents called, then falls back to the default taxonomy")
        void falls_back_when_redis_key_missing() {
            when(valueOperations.get(properties.getKey())).thenReturn(null);
            RedisIntentManager manager = newManager();

            assertThat(manager.listIntents())
                    .extracting(IntentDefinition::id)
                    .containsExactly("general.conversation");
        }

        @Test
        @DisplayName("Given Redis throws on read, when listIntents called, then falls back to the default taxonomy")
        void falls_back_when_redis_throws() {
            when(valueOperations.get(properties.getKey())).thenThrow(new RuntimeException("connection refused"));
            RedisIntentManager manager = newManager();

            assertThat(manager.listIntents())
                    .extracting(IntentDefinition::id)
                    .containsExactly("general.conversation");
        }

        @Test
        @DisplayName("Given Redis returns malformed JSON, when listIntents called, then falls back to the default taxonomy")
        void falls_back_when_redis_value_is_malformed() {
            when(valueOperations.get(properties.getKey())).thenReturn("{ not valid json ");
            RedisIntentManager manager = newManager();

            assertThat(manager.listIntents())
                    .extracting(IntentDefinition::id)
                    .containsExactly("general.conversation");
        }

        @Test
        @DisplayName("Given Redis stays unreachable, when listIntents called repeatedly within the retry interval, then Redis is not hammered")
        void does_not_retry_redis_before_fallback_retry_interval_elapses() {
            when(valueOperations.get(properties.getKey())).thenThrow(new RuntimeException("connection refused"));
            RedisIntentManager manager = newManager();

            manager.listIntents();
            clock.advance(Duration.ofSeconds(2));
            manager.listIntents();

            verify(valueOperations, times(1)).get(properties.getKey());
        }

        @Test
        @DisplayName("Given Redis recovers after the fallback retry interval, when listIntents called again, then Redis is retried and wins")
        void retries_redis_after_fallback_retry_interval_elapses() throws Exception {
            when(valueOperations.get(properties.getKey()))
                    .thenThrow(new RuntimeException("connection refused"))
                    .thenReturn(toJson(REDIS_INTENT));
            RedisIntentManager manager = newManager();

            List<IntentDefinition> first = manager.listIntents();
            clock.advance(properties.getFallbackRetryInterval().plusSeconds(1));
            List<IntentDefinition> second = manager.listIntents();

            assertThat(first).extracting(IntentDefinition::id).containsExactly("general.conversation");
            assertThat(second).extracting(IntentDefinition::id).containsExactly("catalog.product.search");
            verify(valueOperations, times(2)).get(properties.getKey());
        }

        @Test
        @DisplayName("Given a null intent id, when getIntent called during a Redis outage, then returns empty")
        void returns_empty_for_null_id_during_outage() {
            when(valueOperations.get(properties.getKey())).thenThrow(new RuntimeException("boom"));
            RedisIntentManager manager = newManager();

            assertThat(manager.getIntent(null)).isEmpty();
        }
    }

    // =========================================================================
    // 3. Edge cases — startup seeding
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class Seeding {

        @Test
        @DisplayName("Given an empty Redis and seeding enabled, when constructed, then seeds the taxonomy and records its hash")
        void seeds_redis_with_default_taxonomy_when_missing() throws Exception {
            properties.setSeedIfMissing(true);
            when(redisTemplate.hasKey(properties.getKey())).thenReturn(false);

            newManager();

            verify(valueOperations).set(properties.getKey(), toJson(FALLBACK_INTENT));
            verify(valueOperations).set(eq(properties.getKey() + ":source-sha256"), anyString());
        }

        @Test
        @DisplayName("Given Redis holds a taxonomy seeded by an older release (hash differs), when constructed, then the classpath taxonomy overwrites it")
        void overwrites_stale_taxonomy_when_classpath_hash_differs() throws Exception {
            properties.setSeedIfMissing(true);
            when(valueOperations.get(properties.getKey() + ":source-sha256")).thenReturn("old-hash");
            when(redisTemplate.hasKey(properties.getKey())).thenReturn(true);

            newManager();

            verify(valueOperations).set(properties.getKey(), toJson(FALLBACK_INTENT));
            verify(valueOperations, never()).setIfAbsent(anyString(), anyString());
        }

        @Test
        @DisplayName("Given Redis was seeded from the same classpath taxonomy, when constructed, then Redis content is left alone")
        void keeps_redis_when_classpath_hash_matches() throws Exception {
            properties.setSeedIfMissing(true);
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(toJson(FALLBACK_INTENT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            when(valueOperations.get(properties.getKey() + ":source-sha256")).thenReturn(hash);
            when(valueOperations.setIfAbsent(eq(properties.getKey()), anyString())).thenReturn(false);

            newManager();

            verify(valueOperations, never()).set(eq(properties.getKey()), anyString());
        }

        @Test
        @DisplayName("Given seeding disabled, when constructed, then never writes to Redis")
        void does_not_seed_when_disabled() {
            properties.setSeedIfMissing(false);

            newManager();

            verify(valueOperations, never()).setIfAbsent(anyString(), anyString());
            verify(valueOperations, never()).set(anyString(), anyString());
        }

        @Test
        @DisplayName("Given Redis is unreachable during seeding, when constructed, then construction succeeds and reads still fall back")
        void construction_survives_seeding_failure() {
            properties.setSeedIfMissing(true);
            when(valueOperations.get(anyString())).thenThrow(new RuntimeException("connection refused"));

            RedisIntentManager manager = newManager();

            assertThat(manager.listIntents())
                    .extracting(IntentDefinition::id)
                    .containsExactly("general.conversation");
        }
    }

    private static String toJson(IntentDefinition... intents) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(intents);
    }

    /** A {@link Clock} that only advances when told to, for deterministic TTL assertions. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
