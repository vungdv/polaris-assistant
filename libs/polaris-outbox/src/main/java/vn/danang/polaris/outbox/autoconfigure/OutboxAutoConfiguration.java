package vn.danang.polaris.outbox.autoconfigure;

import java.time.Clock;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import tools.jackson.databind.json.JsonMapper;
import vn.danang.polaris.outbox.IntegrationEventPublisher;
import vn.danang.polaris.outbox.OutboxIntegrationEventPublisher;
import vn.danang.polaris.outbox.relay.OutboxRelay;
import vn.danang.polaris.outbox.relay.OutboxRelayWorker;
import vn.danang.polaris.outbox.relay.OutboxRetention;
import vn.danang.polaris.outbox.relay.RetryBackoff;
import vn.danang.polaris.outbox.store.JdbcOutboxRelayStore;
import vn.danang.polaris.outbox.store.JdbcOutboxStore;
import vn.danang.polaris.outbox.store.OutboxRelayStore;
import vn.danang.polaris.outbox.store.OutboxStore;
import vn.danang.polaris.outbox.telemetry.HandOffTracing;
import vn.danang.polaris.outbox.telemetry.OutboxMetrics;
import vn.danang.polaris.outbox.transport.EventTransport;

/**
 * Wires the outbox publisher and relay only where the application already has a single {@link DataSource}:
 * apps without one get no beans and no failure. Opt out with {@code polaris.outbox.enabled=false}, or keep
 * recording but stop relaying with {@code polaris.outbox.relay.enabled=false}.
 *
 * <p>The relay hands events to the application's {@link EventTransport} bean. Without one (until Plan 2 adds
 * Kafka) the relay stays idle and events accumulate as pending; metrics and the retention purge still run.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration" })
@ConditionalOnClass({ JdbcClient.class, PlatformTransactionManager.class })
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnBooleanProperty(name = "polaris.outbox.enabled", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    /** Statement timeout of the metrics queries (seconds). */
    private static final int METRICS_QUERY_TIMEOUT_SECONDS = 5;

    @Bean
    @ConditionalOnMissingBean
    OutboxStore outboxStore(DataSource dataSource) {
        return new JdbcOutboxStore(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationEventPublisher integrationEventPublisher(OutboxStore outboxStore, ObjectProvider<JsonMapper> jsonMapper) {
        return new OutboxIntegrationEventPublisher(outboxStore, jsonMapper.getIfUnique(JsonMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelayStore outboxRelayStore(DataSource dataSource) {
        return new JdbcOutboxRelayStore(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxMetrics outboxMetrics(DataSource dataSource, ObjectProvider<MeterRegistry> meterRegistry) {
        // Gauges are read on the registry's publish thread: own store with a statement timeout, snapshot cache and a
        // bounded wait, so a slow database can never stall the whole app's metric export.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(METRICS_QUERY_TIMEOUT_SECONDS);
        // Without an app registry the meters stay local (not exported) rather than leaking into the global one.
        return new OutboxMetrics(meterRegistry.getIfUnique(SimpleMeterRegistry::new),
                new JdbcOutboxRelayStore(JdbcClient.create(template)), Clock.systemUTC(),
                OutboxMetrics.DEFAULT_SNAPSHOT_TTL, OutboxMetrics.DEFAULT_QUERY_TIMEOUT, OutboxMetrics.DEFAULT_STALE_AFTER);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "polaris.outbox.relay.enabled", matchIfMissing = true)
    OutboxRelayWorker outboxRelayWorker(OutboxProperties properties, OutboxRelayStore relayStore, OutboxMetrics metrics,
            ObjectProvider<EventTransport> transport, ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<OpenTelemetry> openTelemetry) {
        Clock clock = Clock.systemUTC();
        OutboxProperties.Relay relay = properties.relay();
        OutboxProperties.Backoff backoff = relay.backoff();
        // Resolved when the worker starts, so a transport from a later auto-configuration (Plan 2) is found.
        Supplier<OutboxRelay> relayFactory = () -> {
            EventTransport configured;
            try {
                configured = transport.getIfAvailable();
            } catch (NoUniqueBeanDefinitionException ambiguous) {
                // Never go silently idle; picking one would also split a key's events across transports.
                throw new IllegalStateException("The outbox relay needs exactly one EventTransport bean but found "
                        + ambiguous.getNumberOfBeansFound() + ": " + ambiguous.getBeanNamesFound()
                        + ". Mark one @Primary or set polaris.outbox.relay.enabled=false", ambiguous);
            }
            if (configured == null) {
                return null;
            }
            return new OutboxRelay(relayStore, configured, new TransactionTemplate(transactionManager.getObject()),
                    new RetryBackoff(backoff.initial(), backoff.multiplier(), backoff.max()), relay.batchSize(),
                    metrics, new HandOffTracing(openTelemetry.getIfUnique(OpenTelemetry::noop)), clock);
        };
        OutboxRetention retention = new OutboxRetention(relayStore, properties.retention().period(), metrics, clock);
        return new OutboxRelayWorker(relayFactory, retention, relay.pollInterval(), properties.retention().purgeInterval());
    }
}
