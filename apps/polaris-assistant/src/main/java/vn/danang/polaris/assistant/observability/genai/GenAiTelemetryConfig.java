package vn.danang.polaris.assistant.observability.genai;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.grafana.agento11y.sdk.Agento11yClient;
import com.grafana.agento11y.sdk.Agento11yClientConfig;
import com.grafana.agento11y.sdk.GenerationExportConfig;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import vn.danang.polaris.assistant.config.AssistantGenAiTelemetryProperties;

/**
 * Wires the Grafana AI Observability SDK to the application's own OpenTelemetry SDK.
 * <p>
 * Spring Boot builds its {@code OpenTelemetrySdk} without a {@link SdkMeterProvider} (Boot metrics go
 * through Micrometer), so without the bean below every {@code gen_ai.client.*} metric would be recorded
 * against a no-op meter and silently discarded. The SDK client is handed the app's tracer and meter
 * explicitly, so its spans join the active trace and share the service resource.
 */
@Configuration(proxyBeanMethods = false)
public class GenAiTelemetryConfig {

    static final String INSTRUMENTATION_SCOPE = "vn.danang.polaris.assistant.genai";

    @Bean(destroyMethod = "close")
    SdkMeterProvider sdkMeterProvider(AssistantGenAiTelemetryProperties properties, ObjectProvider<Resource> resource) {
        OtlpHttpMetricExporter exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint(properties.getMetricsEndpoint())
                .build();
        return SdkMeterProvider.builder()
                .setResource(resource.getIfAvailable(Resource::getDefault))
                .registerMetricReader(PeriodicMetricReader.builder(exporter)
                        .setInterval(properties.getMetricExportInterval())
                        .build())
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    Agento11yClient agento11yClient(AssistantGenAiTelemetryProperties properties, OpenTelemetry openTelemetry) {
        return new Agento11yClient(new Agento11yClientConfig()
                .setTracer(openTelemetry.getTracer(INSTRUMENTATION_SCOPE))
                .setMeter(openTelemetry.getMeter(INSTRUMENTATION_SCOPE))
                .setAgentName(GenAiTelemetry.AGENT_NAME)
                .setAgentVersion(properties.getAgentVersion())
                .setContentCapture(properties.getContentCapture())
                .setGenerationExport(new GenerationExportConfig()
                        .setProtocol(properties.getGenerationExportProtocol())));
    }
}
