package vn.danang.polaris.assistant.intent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.assistant.ai.ModelCall;
import vn.danang.polaris.assistant.ai.ModelProviderException;
import vn.danang.polaris.assistant.ai.ModelTokenUsage;
import vn.danang.polaris.assistant.config.AssistantTypeSafeProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.observability.genai.GenAiGeneration;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;

/**
 * Classifies user intent using TypeSafe's Jev System One model (Choice primitive)
 * in place of hand-tuned lexical similarity. Sends the message plus recent
 * conversation history as state, and the intent taxonomy (id -&gt; description/examples)
 * as Choice criteria, letting the model make the semantic judgment call that brittle
 * string/keyword matching cannot generalize across paraphrases.
 * <p>
 * Each live call is reported on {@link IntentClassification#modelCall()}; {@link GenAiGeneration} records it as
 * GenAI telemetry under its own agent ({@value GenAiTelemetry#INTENT_CLASSIFIER_AGENT_NAME}).
 */
@Component
public class TypeSafeIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(TypeSafeIntentClassifier.class);

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final String QUESTION_ID = "intent";

    private final AssistantTypeSafeProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public TypeSafeIntentClassifier(AssistantTypeSafeProperties properties) {
        this(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build(),
                new ObjectMapper().findAndRegisterModules());
    }

    public TypeSafeIntentClassifier(AssistantTypeSafeProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @GenAiGeneration(
            provider = GenAiTelemetry.PROVIDER_TYPESAFE,
            agent = GenAiTelemetry.INTENT_CLASSIFIER_AGENT_NAME,
            tags = {
                    @GenAiGeneration.Tag(key = "intents_count", expression = "#intents != null ? #intents.size() : 0"),
                    @GenAiGeneration.Tag(key = "history_count", expression = "#history != null ? #history.size() : 0"),
                    @GenAiGeneration.Tag(key = "intent_result", expression = "#result.intentId()"),
                    @GenAiGeneration.Tag(key = "intent_confidence", expression = "#result.confidence()"),
                    @GenAiGeneration.Tag(key = "intent_fallback", expression = "#result.fallback()"),
                    @GenAiGeneration.Tag(key = "intent_fallback_reason", expression = "#result.fallbackReason()")
            }
    )
    public IntentClassification classify(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents) {
        if (intents == null || intents.isEmpty()) {
            log.warn("No intent taxonomy available; cannot classify query.");
            return fallback("empty_taxonomy");
        }

        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank() || (apiKey.startsWith("${") && apiKey.endsWith("}"))) {
            log.warn("TypeSafe API key is not configured; falling back to default intent for query classification.");
            return fallback("missing_api_key");
        }

        String model = properties.getModel();
        try {
            HttpRequest request = buildRequest(apiKey, query, history, intents);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            IntentClassification classification = parseResponse(response);
            ModelCall call = response.statusCode() == 200
                    ? ModelCall.succeeded(model, null, null, ModelTokenUsage.NONE, "stop")
                    : ModelCall.failed(model, new ModelProviderException(response.statusCode()));
            return classification.withModelCall(call);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("TypeSafe intent classification request interrupted", e);
            return fallback("interrupted").withModelCall(ModelCall.failed(model, e));
        } catch (HttpTimeoutException e) {
            log.warn("TypeSafe intent classification timed out after {} s; falling back to default intent", properties.getTimeoutSeconds());
            return fallback("timeout").withModelCall(ModelCall.failed(model, e));
        } catch (IOException e) {
            log.error("TypeSafe intent classification I/O error: {}", e.getMessage(), e);
            return fallback("io_error").withModelCall(ModelCall.failed(model, e));
        } catch (RuntimeException e) {
            log.error("Unexpected error classifying intent via TypeSafe: {}", e.getMessage(), e);
            return fallback("unexpected_error").withModelCall(ModelCall.failed(model, e));
        }
    }

    private HttpRequest buildRequest(String apiKey, String query, List<AssistantMessage> history, Collection<IntentDefinition> intents)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        String payload = objectMapper.writeValueAsString(buildRequestBody(query, history, intents));
        String url = properties.getBaseUrl().replaceAll("/+$", "") + "/v1/systemone";

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private Map<String, Object> buildRequestBody(String query, List<AssistantMessage> history, Collection<IntentDefinition> intents) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("message", query);
        state.put("recent_history", buildHistorySnippets(history));

        Map<String, Object> criteria = new LinkedHashMap<>();
        for (IntentDefinition def : intents) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("description", def.description());
            if (def.examples() != null && !def.examples().isEmpty()) {
                option.put("examples", def.examples());
            }
            criteria.put(def.id(), option);
        }

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions",
                "Which intent does the user's `message` express? Use `recent_history` only for context "
                        + "such as pronouns or follow-ups (e.g. \"cancel that\", \"is it in stock\"), not as the thing being classified.");
        question.put("criteria", criteria);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("model", properties.getModel());
        body.put("questions", Map.of(QUESTION_ID, question));
        return body;
    }

    private List<Map<String, String>> buildHistorySnippets(List<AssistantMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> snippets = new ArrayList<>();
        int start = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < history.size(); i++) {
            AssistantMessage msg = history.get(i);
            if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            String role = msg.getRole() != null ? msg.getRole().name() : "USER";
            snippets.add(Map.of("role", role, "content", msg.getContent()));
        }
        return snippets;
    }

    private IntentClassification parseResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() != 200) {
            log.error("TypeSafe API error status: {} body: {}", response.statusCode(), response.body());
            return fallback("http_status_" + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode answer = root.path("answers").path(QUESTION_ID);
        if (answer.isMissingNode()) {
            log.warn("TypeSafe response missing '{}' answer: {}", QUESTION_ID, response.body());
            return fallback("missing_answer");
        }

        String choice = answer.hasNonNull("choice") ? answer.path("choice").asText() : null;
        if (choice == null || choice.isBlank()) {
            log.warn("TypeSafe response returned no choice: {}", response.body());
            return fallback("blank_choice");
        }

        double confidence = answer.path("confidence").asDouble(0.0);
        return new IntentClassification(choice, confidence);
    }

    /**
     * Builds a fail-safe classification result, tagged with {@code reason} so tracing and logs
     * can distinguish "the model wasn't consulted" from a genuine low-confidence judgment.
     */
    private IntentClassification fallback(String reason) {
        return IntentClassification.fallback(DefaultIntentResolver.DEFAULT_INTENT, reason);
    }
}
