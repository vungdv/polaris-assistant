package vn.danang.polaris.assistant.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.config.QwenProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.observability.genai.GenAiGeneration;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;

/**
 * Qwen (or any model) served over the OpenAI Chat Completions API. Orchestrates one call: map the conversation
 * ({@link OpenAiRequestMapper}), send it ({@link OpenAiTransport}), read the answer
 * ({@link OpenAiResponseParser}). Each call is reported on {@link ModelResponse#modelCall()} for
 * {@link GenAiGeneration}.
 * <p>
 * Failures are never reply text: they raise {@link ModelUnavailableException}. A call waits at most
 * {@code timeout-seconds}, capped by what is left of the turn's {@link ModelRequestContext#deadline()}.
 */
@Component
public class OpenAiCompatibleModelClient implements AssistantModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);

    private final QwenProperties properties;
    private final OpenAiRequestMapper mapper;
    private final OpenAiTransport transport;
    private final OpenAiResponseParser parser;

    @Autowired
    public OpenAiCompatibleModelClient(QwenProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper().findAndRegisterModules());
    }

    public OpenAiCompatibleModelClient(QwenProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.mapper = new OpenAiRequestMapper(objectMapper);
        this.transport = new OpenAiTransport(httpClient);
        this.parser = new OpenAiResponseParser(objectMapper);
    }

    @Override
    public Duration turnDeadline() {
        return properties.getTurnDeadline();
    }

    @Override
    public String chat(List<AssistantMessage> messages) {
        return generateResponse(messages, List.of()).text();
    }

    @Override
    @GenAiGeneration(provider = GenAiTelemetry.PROVIDER_QWEN)
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools) {
        return generateResponse(messages, tools, ModelRequestContext.empty());
    }

    @Override
    @GenAiGeneration(
            provider = GenAiTelemetry.PROVIDER_QWEN,
            conversationId = "#context?.conversationId()",
            tags = {
                    @GenAiGeneration.Tag(key = "iteration", expression = "#context?.iteration()"),
                    @GenAiGeneration.Tag(key = "intent_id", expression = "#context?.intentId()"),
                    @GenAiGeneration.Tag(key = "intent_confidence", expression = "#context?.intentConfidence()"),
                    @GenAiGeneration.Tag(key = "tools_offered", expression = "#tools != null ? #tools.size() : 0")
            }
    )
    public ModelResponse generateResponse(List<AssistantMessage> messages, List<Tool> tools,
            @Nullable ModelRequestContext context) {
        String model = properties.getModel();
        ModelRequestContext effective = Optional.ofNullable(context).orElseGet(ModelRequestContext::empty);
        Duration timeout = callTimeout(effective);
        if (timeout.isNegative() || timeout.isZero()) {
            TurnDeadlineExceededException exhausted = new TurnDeadlineExceededException("Turn deadline exceeded before calling Qwen");
            log.error("Turn deadline exceeded before calling Qwen");
            throw new ModelUnavailableException(exhausted.getMessage(), exhausted, ModelCall.failed(model, exhausted));
        }
        String body;
        try {
            body = mapper.toRequestBody(model, properties.getSystemPrompt(), messages, tools);
        } catch (RuntimeException e) {
            log.error("Unexpected error building Qwen request", e);
            throw new ModelUnavailableException("Unexpected error building Qwen request", e);
        }
        log.info("Sending request to Qwen model {}", model);
        String responseBody = transport.postChatCompletions(properties.getBaseUrl(), properties.getApiKey(), model, body,
                timeout);
        try {
            return parser.parse(responseBody, model);
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("Qwen returned an unreadable response: {}", e.getClass().getSimpleName());
            throw new ModelUnavailableException("Qwen returned an unreadable response", e, ModelCall.failed(model, e));
        }
    }

    /** The per-call timeout, capped by what is left of the turn's budget. */
    private Duration callTimeout(ModelRequestContext context) {
        Duration perCall = Duration.ofSeconds(properties.getTimeoutSeconds());
        return context.deadline()
                .map(deadline -> Duration.between(Instant.now(), deadline))
                .filter(remaining -> remaining.compareTo(perCall) < 0)
                .orElse(perCall);
    }
}
