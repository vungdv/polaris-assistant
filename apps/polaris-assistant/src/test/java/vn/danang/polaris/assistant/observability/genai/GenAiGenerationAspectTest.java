package vn.danang.polaris.assistant.observability.genai;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelTokenUsage;

/**
 * The aspect's own contract, independent of any provider client: what it reads from arguments and results,
 * and when it records nothing.
 */
class GenAiGenerationAspectTest {

    private final InMemoryGenAiTelemetry telemetry = new InMemoryGenAiTelemetry();
    private final FakeProvider provider = telemetry.observed(new FakeProvider());

    @AfterEach
    void tearDown() {
        telemetry.close();
    }

    @Test
    @DisplayName("Given an annotated call returning a model call, when invoked, then records one generation from args and result")
    void records_generation_from_arguments_and_result() {
        provider.call("sess-9", ModelCall.succeeded("fake-model", null, null, new ModelTokenUsage(5, 7, 0, 0), "stop"));

        SpanData span = telemetry.onlySpan();
        assertThat(span.getName()).isEqualTo("generateText fake-model");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name"))).isEqualTo("fake");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.agent.name"))).isEqualTo("fake-agent");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.conversation.id"))).isEqualTo("sess-9");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(7L);
        assertThat(telemetry.client().debugSnapshot().getGenerations()).singleElement()
                .satisfies(generation -> assertThat(generation.getTags()).containsEntry("reply", "ok"));
    }

    @Test
    @DisplayName("Given a result without a model call, when invoked, then records nothing")
    void records_nothing_without_model_call() {
        provider.call("sess-9", null);

        assertThat(telemetry.spans()).isEmpty();
        assertThat(telemetry.histogramPoints("gen_ai.client.operation.duration")).isEmpty();
    }

    @Test
    @DisplayName("Given the annotated method throws, when invoked, then the exception propagates and nothing is recorded")
    void propagates_exceptions_without_recording() {
        assertThatThrownBy(provider::fail).isInstanceOf(IllegalArgumentException.class);

        assertThat(telemetry.spans()).isEmpty();
    }

    @Test
    @DisplayName("Given a tag expression that cannot be evaluated, when invoked, then the generation is still recorded without that tag")
    void tolerates_broken_tag_expressions() {
        provider.brokenTag(ModelCall.succeeded("fake-model", null, null, ModelTokenUsage.NONE, "stop"));

        assertThat(telemetry.spans()).hasSize(1);
        assertThat(telemetry.client().debugSnapshot().getGenerations()).singleElement()
                .satisfies(generation -> assertThat(generation.getTags()).doesNotContainKey("broken"));
    }

    static class FakeProvider {

        @GenAiGeneration(provider = "fake", agent = "fake-agent", conversationId = "#session",
                tags = @GenAiGeneration.Tag(key = "reply", expression = "#result.text()"))
        public ModelResponse call(String session, ModelCall modelCall) {
            return new ModelResponse("ok", List.of(), null, modelCall);
        }

        @GenAiGeneration(provider = "fake")
        public ModelResponse fail() {
            throw new IllegalArgumentException("not configured");
        }

        @GenAiGeneration(provider = "fake", tags = @GenAiGeneration.Tag(key = "broken", expression = "#missing.size()"))
        public ModelResponse brokenTag(ModelCall modelCall) {
            return new ModelResponse("ok", List.of(), null, modelCall);
        }
    }
}
