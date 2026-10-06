package vn.danang.polaris.outbox.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.OutboxIntegrationEventPublisher;
import vn.danang.polaris.outbox.relay.OutboxRelayWorker;
import vn.danang.polaris.outbox.store.JdbcOutboxRelayStore;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;
import vn.danang.polaris.outbox.transport.EventTransport;
import vn.danang.polaris.outbox.store.JdbcOutboxStore;
import vn.danang.polaris.outbox.store.OutboxStore;

class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class));

    @Test
    void withDataSource_providesThePublisherAndStore() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class)).run(context -> {
            assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
            assertThat(context.getBean(IntegrationEventPublisher.class)).isInstanceOf(OutboxIntegrationEventPublisher.class);
            assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
            assertThat(context.getBean(OutboxRelayStore.class)).isInstanceOf(JdbcOutboxRelayStore.class);
            assertThat(context).hasSingleBean(OutboxMetrics.class);
            assertThat(context).hasSingleBean(OutboxRelayWorker.class);
        });
    }

    @Test
    void withoutTransport_relayWorkerRunsIdle() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class)).run(context -> {
            OutboxRelayWorker worker = context.getBean(OutboxRelayWorker.class);
            assertThat(worker.isRunning()).isTrue();
            assertThat(worker.isRelaying()).isFalse();
        });
    }

    @Test
    void relayDisabledByProperty_keepsRecordingAndMetrics_withoutAWorker() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("polaris.outbox.relay.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(IntegrationEventPublisher.class);
                    assertThat(context).hasSingleBean(OutboxMetrics.class);
                    assertThat(context).doesNotHaveBean(OutboxRelayWorker.class);
                });
    }

    @Test
    void withoutDataSource_backsOffWithoutFailing() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IntegrationEventPublisher.class);
            assertThat(context).doesNotHaveBean(OutboxStore.class);
            assertThat(context).doesNotHaveBean(OutboxRelayWorker.class);
            assertThat(context).doesNotHaveBean(OutboxMetrics.class);
        });
    }

    @Test
    void disabledByProperty_backsOff() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("polaris.outbox.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IntegrationEventPublisher.class);
                    assertThat(context).doesNotHaveBean(OutboxRelayWorker.class);
                });
    }

    @Test
    void twoTransports_failStartupClearly_insteadOfIdling() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean("kafkaTransport", EventTransport.class, () -> event -> { })
                .withBean("otherTransport", EventTransport.class, () -> event -> { })
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining("needs exactly one EventTransport bean but found 2");
                });
    }

    @Test
    void applicationBeans_win() {
        OutboxStore custom = record -> { };
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(OutboxStore.class, () -> custom)
                .run(context -> assertThat(context.getBean(OutboxStore.class)).isSameAs(custom));
    }
}
