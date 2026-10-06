package vn.danang.polaris.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "polaris.assistant.intent.redis")
@Getter
@Setter
public class AssistantIntentRedisProperties {

    /** Redis key holding the serialized intent taxonomy (a JSON array of IntentDefinition). */
    private String key = "polaris:assistant:intents";

    /** How long a successful load from Redis is served from the in-process cache before refreshing. */
    private Duration localCacheTtl = Duration.ofSeconds(30);

    /** How long a fallback (Redis unavailable/empty) result is cached before retrying Redis. */
    private Duration fallbackRetryInterval = Duration.ofSeconds(5);

    /**
     * Whether to sync Redis with the classpath taxonomy on startup: seed it if missing, and overwrite it when
     * the classpath taxonomy changed since it was last seeded (see {@code RedisIntentManager#seedIfMissing}).
     */
    private boolean seedIfMissing = true;
}
