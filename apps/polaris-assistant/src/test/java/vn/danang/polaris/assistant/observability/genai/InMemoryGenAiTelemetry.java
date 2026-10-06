package vn.danang.polaris.assistant.observability.genai;

import java.util.List;
import java.util.Optional;

import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.grafana.agento11y.sdk.Agento11yClient;
import com.grafana.agento11y.sdk.Agento11yClientConfig;
import com.grafana.agento11y.sdk.ContentCaptureMode;
import com.grafana.agento11y.sdk.GenerationExportConfig;
import com.grafana.agento11y.sdk.GenerationExportProtocol;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * A real OpenTelemetry SDK with in-memory span and metric exporters, wired to an agento11y client exactly as
 * {@link GenAiTelemetryConfig} wires the production one (explicit tracer + meter, metadata-only capture,
 * no generation export). Tests assert on what would leave the process over OTLP.
 */
public final class InMemoryGenAiTelemetry implements AutoCloseable {

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryMetricReader metrics = InMemoryMetricReader.create();
    private final OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .build();
    private final Agento11yClient client = new Agento11yClient(new Agento11yClientConfig()
            .setTracer(openTelemetry.getTracer(GenAiTelemetryConfig.INSTRUMENTATION_SCOPE))
            .setMeter(openTelemetry.getMeter(GenAiTelemetryConfig.INSTRUMENTATION_SCOPE))
            .setAgentName(GenAiTelemetry.AGENT_NAME)
            .setContentCapture(ContentCaptureMode.METADATA_ONLY)
            .setGenerationExport(new GenerationExportConfig().setProtocol(GenerationExportProtocol.NONE)));

    public Agento11yClient client() {
        return client;
    }

    /** {@code target} behind a proxy carrying {@link GenAiGenerationAspect}, as Spring wires it. */
    public <T> T observed(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new GenAiGenerationAspect(client));
        return factory.getProxy();
    }

    public OpenTelemetrySdk openTelemetry() {
        return openTelemetry;
    }

    public List<SpanData> spans() {
        return spans.getFinishedSpanItems();
    }

    public SpanData onlySpan() {
        List<SpanData> finished = spans();
        if (finished.size() != 1) {
            throw new AssertionError("expected exactly one span, got " + finished.stream().map(SpanData::getName).toList());
        }
        return finished.get(0);
    }

    public List<MetricData> metrics() {
        return List.copyOf(metrics.collectAllMetrics());
    }

    public Optional<MetricData> metric(String name) {
        return metrics().stream().filter(m -> m.getName().equals(name)).findFirst();
    }

    /** All histogram points of a metric; empty when the metric was never recorded. */
    public List<HistogramPointData> histogramPoints(String name) {
        return metric(name).map(m -> List.copyOf(m.getHistogramData().getPoints())).orElse(List.of());
    }

    @Override
    public void close() {
        client.shutdown();
        openTelemetry.close();
    }
}
