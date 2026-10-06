package vn.danang.polaris.assistant.observability.genai;

import com.grafana.agento11y.sdk.Agento11yClient;
import com.grafana.agento11y.sdk.Agento11yClientConfig;
import com.grafana.agento11y.sdk.GenerationExportConfig;
import com.grafana.agento11y.sdk.GenerationExportProtocol;

import io.opentelemetry.api.OpenTelemetry;

/**
 * Shared constants for GenAI telemetry recorded through the agento11y SDK.
 */
public final class GenAiTelemetry {

    /** {@code gen_ai.agent.name}: the label every AI Observability analytics panel groups by. */
    public static final String AGENT_NAME = "polaris-assistant";
    /** The TypeSafe intent gate is its own agent, so its traffic and errors chart separately. */
    public static final String INTENT_CLASSIFIER_AGENT_NAME = "polaris-intent-classifier";

    /** {@code gen_ai.provider.name} values; Grafana Cloud prices tokens by provider + model. */
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_TYPESAFE = "typesafe";

    private GenAiTelemetry() {
    }

    /**
     * A client that records nothing anywhere: no-op tracer and meter, no generation export. For callers
     * constructed outside Spring (tests, fallbacks) where no telemetry pipeline exists.
     */
    public static Agento11yClient disabledClient() {
        OpenTelemetry noop = OpenTelemetry.noop();
        return new Agento11yClient(new Agento11yClientConfig()
                .setTracer(noop.getTracer(GenAiTelemetryConfig.INSTRUMENTATION_SCOPE))
                .setMeter(noop.getMeter(GenAiTelemetryConfig.INSTRUMENTATION_SCOPE))
                .setGenerationExport(new GenerationExportConfig().setProtocol(GenerationExportProtocol.NONE)));
    }
}
