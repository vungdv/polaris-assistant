package vn.danang.polaris.assistant.observability.genai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.grafana.agento11y.sdk.Agento11yClient;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import vn.danang.polaris.assistant.config.AssistantGenAiTelemetryProperties;

/**
 * Boot builds its OpenTelemetrySdk without a MeterProvider; without {@link GenAiTelemetryConfig} every
 * {@code gen_ai.client.*} metric would be recorded against a no-op meter and never leave the process.
 */
class GenAiTelemetryConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class))
            .withUserConfiguration(AssistantGenAiTelemetryProperties.class, GenAiTelemetryConfig.class)
            .withPropertyValues("polaris.ai.telemetry.metrics-endpoint=http://localhost:4318/v1/metrics");

    @Test
    @DisplayName("Given the GenAI telemetry config, when the context starts, then the app OpenTelemetry carries the SDK MeterProvider")
    void registers_sdk_meter_provider_on_application_open_telemetry() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SdkMeterProvider.class).hasSingleBean(Agento11yClient.class);
            assertThat(context.getBean(OpenTelemetrySdk.class).getSdkMeterProvider())
                    .isSameAs(context.getBean(SdkMeterProvider.class));
        });
    }

    @Test
    @DisplayName("Given no overrides, when the context starts, then content capture is metadata-only and generation export is off")
    void defaults_to_metadata_only_capture_without_generation_export() {
        runner.run(context -> {
            AssistantGenAiTelemetryProperties properties = context.getBean(AssistantGenAiTelemetryProperties.class);
            assertThat(properties.getContentCapture()).hasToString("METADATA_ONLY");
            assertThat(properties.getGenerationExportProtocol()).hasToString("NONE");
        });
    }
}
