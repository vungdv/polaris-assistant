package vn.danang.polaris.assistant.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.grafana.agento11y.sdk.ContentCaptureMode;
import com.grafana.agento11y.sdk.GenerationExportProtocol;

import lombok.Getter;
import lombok.Setter;

/**
 * GenAI telemetry recorded with the Grafana AI Observability (agento11y) SDK.
 * <p>
 * Two independent pipelines: OTel GenAI semconv spans and metrics always go to the OTLP collector;
 * generation export (prompt/response records for the Grafana Cloud Conversations view) is off unless
 * {@code AGENTO11Y_PROTOCOL} is set, in which case endpoint and credentials come from the SDK's own
 * {@code AGENTO11Y_ENDPOINT} / {@code AGENTO11Y_AUTH_*} environment variables.
 */
@Configuration
@ConfigurationProperties(prefix = "polaris.ai.telemetry")
@Getter
@Setter
public class AssistantGenAiTelemetryProperties {

    /** {@code gen_ai.agent.version}; blank omits the label. */
    private String agentVersion = "";
    /** Prompts, responses and tool I/O stay out of spans by default (metadata only). */
    private ContentCaptureMode contentCapture = ContentCaptureMode.METADATA_ONLY;
    private GenerationExportProtocol generationExportProtocol = GenerationExportProtocol.NONE;
    /** OTLP endpoint for the GenAI metrics; same collector as the Micrometer metrics. */
    private String metricsEndpoint = "http://localhost:4318/v1/metrics";
    private Duration metricExportInterval = Duration.ofSeconds(15);
}
