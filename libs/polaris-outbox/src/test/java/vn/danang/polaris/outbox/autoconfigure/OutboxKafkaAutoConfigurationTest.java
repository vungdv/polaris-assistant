package vn.danang.polaris.outbox.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.cloudevents.kafka.KafkaMessageFactory;
import vn.danang.polaris.outbox.kafka.KafkaEventTransport;
import vn.danang.polaris.outbox.transport.EventTransport;

class OutboxKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, OutboxAutoConfiguration.class,
                    OutboxKafkaAutoConfiguration.class))
            // Bean wiring only; the relay itself runs against real PostgreSQL and Kafka in KafkaEventTransportIntegrationTest
            .withPropertyValues("polaris.outbox.relay.enabled=false");

    @Test
    void withOutboxAndKafka_providesTheKafkaTransport() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class)).run(context -> {
            assertThat(context).hasSingleBean(EventTransport.class);
            assertThat(context.getBean(EventTransport.class)).isInstanceOf(KafkaEventTransport.class);
        });
    }

    @Test
    void disabledByProperty_leavesTheRelayWithoutTransport() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("polaris.outbox.kafka.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EventTransport.class));
    }

    @Test
    void anApplicationTransportWins() {
        EventTransport own = event -> { };
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(EventTransport.class, () -> own)
                .run(context -> assertThat(context.getBean(EventTransport.class)).isSameAs(own));
    }

    @Test
    void withoutTheOutbox_backsOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EventTransport.class);
        });
    }

    @Test
    void withoutTheCloudEventsBinding_backsOff() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withClassLoader(new FilteredClassLoader(KafkaMessageFactory.class))
                .run(context -> assertThat(context).doesNotHaveBean(EventTransport.class));
    }

    @Test
    void withoutSpringBootKafka_backsOff() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class, OutboxKafkaAutoConfiguration.class))
                .withPropertyValues("polaris.outbox.relay.enabled=false")
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .run(context -> assertThat(context).doesNotHaveBean(EventTransport.class));
    }
}
