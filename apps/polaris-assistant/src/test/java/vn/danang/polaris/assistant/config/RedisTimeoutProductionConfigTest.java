package vn.danang.polaris.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * The production {@code application.yml} Redis timeouts reach the Lettuce client. The test {@code application.yml}
 * shadows the main one on the test classpath, so this loads the main file directly (as
 * {@code ModelResilienceProductionConfigTest} does) and binds it through Boot's own Redis auto-configuration.
 * No Redis is needed: the connection factory connects lazily.
 */
class RedisTimeoutProductionConfigTest {

    private static final String MAIN_CONFIG = "src/main/resources/application.yml";

    @Test
    @DisplayName("Redis commands time out after 1 s and connects after 2 s, not Lettuce's 60 s / 10 s defaults")
    void production_redis_timeouts() throws IOException {
        PropertySource<?> main = new YamlPropertySourceLoader()
                .load("main-application.yml", new FileSystemResource(MAIN_CONFIG)).get(0);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(main))
                .run(context -> {
                    LettuceClientConfiguration client = context.getBean(LettuceConnectionFactory.class)
                            .getClientConfiguration();
                    assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(client.getClientOptions().orElseThrow().getSocketOptions().getConnectTimeout())
                            .isEqualTo(Duration.ofSeconds(2));
                });
    }
}
