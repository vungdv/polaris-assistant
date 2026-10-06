package vn.danang.polaris.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.github.benmanes.caffeine.cache.Caffeine;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import lombok.Getter;
import lombok.Setter;
import vn.danang.polaris.config.cache.TwoLevelCacheManager;

/**
 * Two-layer cache for the product catalog: Caffeine as an in-process L1 (short TTL, serves reads
 * without a network round trip) in front of Redis as a shared L2 (longer TTL, keeps hot products
 * warm across instances and survives an individual instance restart). See
 * {@link vn.danang.polaris.config.cache.TwoLevelCache} for the read/write/eviction semantics and
 * its Redis-outage fallback behavior.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCTS_CACHE = "products";

    /**
     * Key of a SKU lookup in {@link #PRODUCTS_CACHE}. SKU lookups share the cache with id lookups, so the key is
     * prefixed ({@code sku:<lowercased-sku>}) to keep it distinct from the plain numeric id keys. Used by
     * {@code ProductService}'s cache annotations and by order placement's post-commit eviction, so both agree.
     */
    public static String productSkuKey(String sku) {
        return "sku:" + sku.toLowerCase(java.util.Locale.ROOT);
    }

    @Bean
    @ConfigurationProperties(prefix = "polaris.cache.products")
    public ProductCacheProperties productCacheProperties() {
        return new ProductCacheProperties();
    }

    @Bean
    public CaffeineCacheManager localCacheManager(ProductCacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(PRODUCTS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(properties.getLocalMaxSize())
                .expireAfterWrite(properties.getLocalTtl()));
        return manager;
    }

    @Bean
    public RedisCacheManager remoteCacheManager(RedisConnectionFactory redisConnectionFactory, ProductCacheProperties properties) {
        // GenericJacksonJsonRedisSerializer (Jackson 3, "tools.jackson") replaces the deprecated
        // GenericJackson2JsonRedisSerializer - it's a separate mapper from Spring's Jackson 2
        // HTTP ObjectMapper, built fresh here rather than shared, but that's fine since this
        // serializer only ever sees our own cached DTOs. java.time types (e.g.
        // ProductResponse#createdAt) serialize out of the box, no extra module needed. Default
        // typing embeds a type hint so a cached value deserializes back into its concrete DTO
        // type instead of a generic Map. Scoped to our own domain package (rather than
        // enableUnsafeDefaultTyping(), which allows any class on the classpath) since Jackson's
        // polymorphic deserialization can be abused for RCE if an attacker can write into this
        // Redis key - see https://owasp.org/www-community/vulnerabilities/Deserialization_of_untrusted_data.
        // BigDecimal is explicitly allow-listed too: Jackson's default typing always wraps it with
        // a type tag (e.g. ["java.math.BigDecimal", 9.99]) to preserve numeric precision, even
        // though it's a final class - that's a JDK core type, not an attacker-controlled one.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("vn.danang.polaris.")
                .allowIfSubType(java.math.BigDecimal.class)
                .build();
        GenericJacksonJsonRedisSerializer valueSerializer = GenericJacksonJsonRedisSerializer.create(
                builder -> builder.enableDefaultTyping(typeValidator));
        RedisCacheConfiguration redisConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.getRemoteTtl())
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(redisConfig)
                .withCacheConfiguration(PRODUCTS_CACHE, redisConfig)
                .build();
    }

    // Primary: localCacheManager and remoteCacheManager are also CacheManager beans (kept
    // separate so tests can reach into each tier directly), so @Cacheable/@CacheEvict need an
    // unambiguous default to resolve to this composite two-layer manager.
    @Bean
    @Primary
    public CacheManager cacheManager(CaffeineCacheManager localCacheManager, RedisCacheManager remoteCacheManager) {
        return new TwoLevelCacheManager(localCacheManager, remoteCacheManager);
    }

    @Getter
    @Setter
    public static class ProductCacheProperties {
        /** How long a product stays in the in-process L1 cache before it's refreshed from L2/DB. */
        private Duration localTtl = Duration.ofSeconds(30);

        /** Max number of distinct products held in L1 at once (Caffeine evicts by recency past this). */
        private long localMaxSize = 5_000;

        /** How long a product stays in the shared Redis L2 cache before it's refreshed from the DB. */
        private Duration remoteTtl = Duration.ofMinutes(10);
    }
}
