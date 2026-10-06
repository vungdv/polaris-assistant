package vn.danang.polaris;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16");
    }

    /** Same image and topic policy as compose (TR-B1): topics exist only if their owner provisions them. */
    public static final String KAFKA_IMAGE = "apache/kafka:3.9.1";

    @Bean
    @ServiceConnection
    public KafkaContainer kafkaContainer() {
        return new KafkaContainer(KAFKA_IMAGE).withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }

    // No dedicated Testcontainers Redis module/@ServiceConnection support is wired up here, so
    // the L2 cache backend is a plain GenericContainer wired to Spring Data Redis via the
    // DynamicPropertyRegistrar bean below instead.
    @Bean(initMethod = "start", destroyMethod = "stop")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }

    @Bean
    public DynamicPropertyRegistrar redisPropertiesRegistrar(GenericContainer<?> redisContainer) {
        return registry -> {
            registry.add("spring.data.redis.host", redisContainer::getHost);
            registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        };
    }
}
