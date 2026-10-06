package vn.danang.polaris.assistant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.Nullable;
import vn.danang.polaris.assistant.config.AssistantAiProperties;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.ProductListCard;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics.TurnOutcome;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.genai.GenAiTelemetry;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpan;
import vn.danang.polaris.assistant.observability.trace.SpanTag;

/**
 * Orchestrates the conversational agent main workflow for Polaris Assistant.
 * Coordinates conversation turn lifecycle, tool discovery, intent resolution,
 * and the reactive execution loop as specified in the assistant orchestrator design.
 * <p>
 * Conversation history is loaded from and appended to the {@link SessionStore} in short
 * transactions around the turn, so no database transaction spans the model round-trips.
 * <p>
 * Cards ({@link ChatWidget}) produced by successful tool calls are returned with the reply and persisted
 * on the turn's final assistant message ({@code widget_type} / {@code widget_payload}).
 * <p>
 * Each turn has a deadline ({@code polaris.ai.turn-deadline}) passed to every model call; when it runs out the
 * model client raises {@link ModelUnavailableException}.
 */
@Service
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);
    private static final int MAX_TOOL_ITERATIONS = 5;
    private static final String DEFAULT_COMPLETION_REPLY = "I have completed processing your request.";

    private final AssistantModelClient modelClient;
    private final Optional<Tracer> tracer;
    private final IntentResolutionFacade intentResolutionFacade;
    private final ObjectMapper objectMapper;
    private final SessionStore sessionStore;
    private final AssistantOutcomeMetrics outcomeMetrics;
    private final Duration turnDeadline;

    @Autowired
    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            SessionStore sessionStore,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            AssistantOutcomeMetrics outcomeMetrics,
            AssistantAiProperties aiProperties) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.intentResolutionFacade = Objects.requireNonNull(intentResolutionFacade, "intentResolutionFacade must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.objectMapper = objectMapperProvider != null && objectMapperProvider.getIfAvailable() != null
                ? objectMapperProvider.getIfAvailable()
                : new ObjectMapper().findAndRegisterModules();
        this.outcomeMetrics = Objects.requireNonNull(outcomeMetrics, "outcomeMetrics must not be null");
        this.turnDeadline = Objects.requireNonNull(aiProperties.getTurnDeadline(), "turnDeadline must not be null");
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            SessionStore sessionStore,
            @Nullable ObjectMapper objectMapper) {
        this(modelClient, tracerProvider, intentResolutionFacade, sessionStore, objectMapper, AssistantOutcomeMetrics.detached());
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            SessionStore sessionStore,
            @Nullable ObjectMapper objectMapper,
            AssistantOutcomeMetrics outcomeMetrics) {
        this(modelClient, tracerProvider, intentResolutionFacade, sessionStore, objectMapper, outcomeMetrics,
                new AssistantAiProperties().getTurnDeadline());
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            SessionStore sessionStore,
            @Nullable ObjectMapper objectMapper,
            AssistantOutcomeMetrics outcomeMetrics,
            Duration turnDeadline) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tracer = Optional.ofNullable(tracerProvider).map(ObjectProvider::getIfAvailable);
        this.intentResolutionFacade = Objects.requireNonNull(intentResolutionFacade, "intentResolutionFacade must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.outcomeMetrics = Objects.requireNonNull(outcomeMetrics, "outcomeMetrics must not be null");
        this.turnDeadline = Objects.requireNonNull(turnDeadline, "turnDeadline must not be null");
    }

    public AssistantChatService(
            AssistantModelClient modelClient,
            ObjectProvider<Tracer> tracerProvider,
            IntentResolutionFacade intentResolutionFacade,
            SessionStore sessionStore) {
        this(modelClient, tracerProvider, intentResolutionFacade, sessionStore, (ObjectMapper) null);
    }

    @CustomNextSpan(
            name = "agent.turn",
            tags = {
                @SpanTag(key = "agent.name", value = "assistant-chat"),
                @SpanTag(key = "agent.framework", value = "polaris-assistant"),
                @SpanTag(key = "agent.session_id", expression = "#request?.sessionId()"),
                @SpanTag(key = "agent.user_id", expression = "#userId != null && !#userId.isBlank() ? #userId : 'anonymous'"),
                // OTel GenAI semconv (invoke_agent) keys, alongside the agent.* keys above
                @SpanTag(key = "gen_ai.operation.name", value = "invoke_agent"),
                @SpanTag(key = "gen_ai.agent.name", value = GenAiTelemetry.AGENT_NAME),
                @SpanTag(key = "gen_ai.conversation.id", expression = "#request?.sessionId()")
            }
    )
    public ChatMessageResponse sendMessage(ChatMessageRequest request, String userId) {
        Instant deadline = Instant.now().plus(turnDeadline);
        String messageText = request.message();
        String sessionId = request.sessionId();

        // 1. Load session history (owner-checked) & append user message
        List<AssistantMessage> history = sessionStore.loadHistory(sessionId, userId);
        int persistedTurns = history.size();
        history.add(toUserTurn(messageText));

        // 2. Resolve intent & narrow tools to use.
        ResolvedIntent resolvedIntent = null;
        ConversationLoopResult loopResult;
        try {
            resolvedIntent = intentResolutionFacade.resolve(messageText, history);
            // 3. Execute tool loop (max 5 iterations)
            loopResult = executeConversationLoop(sessionId, userId, history, resolvedIntent, deadline);
        } catch (ModelUnavailableException e) {
            outcomeMetrics.turn(resolvedIntent, TurnOutcome.FAILED);
            persistExecutedToolTurns(sessionId, userId, history, persistedTurns);
            throw e;
        } catch (RuntimeException e) {
            outcomeMetrics.turn(resolvedIntent, TurnOutcome.FAILED);
            throw e;
        }
        outcomeMetrics.turn(resolvedIntent, loopResult.outcome());

        // 4. Tag iteration count on active span
        tagIterationsCount(loopResult.iterations());

        // 5. Append final assistant response (with this turn's cards) to history
        AssistantMessage assistantMsg = toAssistantTurn(loopResult.reply(), loopResult.thoughtSignature());
        attachWidgets(assistantMsg, loopResult.widgets());
        history.add(assistantMsg);

        // 6. Persist this turn's messages (user, tool calls/results, reply) in one go
        sessionStore.append(sessionId, userId, history.subList(persistedTurns, history.size()));

        // 7. Return response to controller
        return new ChatMessageResponse(
                sessionId,
                MessageRole.ASSISTANT.name(),
                loopResult.reply(),
                assistantMsg.getCreatedAt(),
                loopResult.widgets()
        );
    }

    /**
     * The model became unavailable mid-turn. Tools that already ran (e.g. a staged draft) have side effects the
     * conversation must remember, so their call/result turns are persisted with the user message; no reply and no
     * error text are. When no tool ran, nothing is persisted and the user can simply resend the message.
     */
    private void persistExecutedToolTurns(String sessionId, String userId, List<AssistantMessage> history, int persistedTurns) {
        List<AssistantMessage> turnMessages = history.subList(persistedTurns, history.size());
        boolean toolsRan = turnMessages.stream().anyMatch(msg -> msg.getRole() == MessageRole.TOOL);
        if (!toolsRan) {
            return;
        }
        log.warn("AI model unavailable after tools ran; persisting {} executed tool turn(s) for sessionId: {}",
                turnMessages.size() - 1, sessionId);
        sessionStore.append(sessionId, userId, List.copyOf(turnMessages));
    }

    private ConversationLoopResult executeConversationLoop(
            String sessionId,
            String userId,
            List<AssistantMessage> history,
            ResolvedIntent resolvedIntent,
            Instant deadline) {

        int iterations = 0;
        String finalReply = null;
        String finalThoughtSignature = null;
        TurnOutcome outcome = TurnOutcome.ITERATION_LIMIT;
        // One card per type, updated in call order (see applyWidgetChanges).
        Map<String, ChatWidget> widgets = new LinkedHashMap<>();

        while (iterations < MAX_TOOL_ITERATIONS) {
            iterations++;
            log.info("Executing conversation turn iteration {} for sessionId: {}, userId: {}", iterations, sessionId, userId);

            ModelRequestContext context = new ModelRequestContext(
                    iterations,
                    resolvedIntent.intentId(),
                    resolvedIntent.confidence(),
                    resolvedIntent.acceptedTools().size(),
                    sessionId,
                    deadline
            );

            ModelResponse modelResponse = queryModel(history, resolvedIntent.acceptedTools(), context);

            if (modelResponse.hasToolCalls()) {
                ToolExecutionOutcome batch = executeToolBatch(sessionId, userId, iterations, resolvedIntent, modelResponse.toolCalls());
                history.addAll(batch.turns());
                applyWidgetChanges(batch.results(), widgets);

                if (batch.policyDenied()) {
                    finalReply = batch.denialMessage();
                    outcome = TurnOutcome.POLICY_DENIED;
                    break;
                }
            } else {
                finalReply = modelResponse.text();
                finalThoughtSignature = modelResponse.thoughtSignature();
                outcome = resolvedIntent.meetsThreshold() ? TurnOutcome.ANSWERED : TurnOutcome.FALLBACK;
                break;
            }
        }

        if (finalReply == null || finalReply.isBlank()) {
            finalReply = DEFAULT_COMPLETION_REPLY;
        }

        return new ConversationLoopResult(finalReply, finalThoughtSignature, iterations, List.copyOf(widgets.values()), outcome);
    }

    private ToolExecutionOutcome executeToolBatch(
            String sessionId,
            String userId,
            int iteration,
            ResolvedIntent resolvedIntent,
            List<ToolCall> toolCalls) {

        List<AssistantMessage> turns = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            turns.add(toModelTurn(toolCall));
        }

        ToolExecutionContext toolContext = new ToolExecutionContext(
                sessionId,
                userId,
                iteration
        );

        List<ToolResult> toolResults = intentResolutionFacade.executeToolCalls(toolCalls, toolContext, resolvedIntent);
        boolean policyDenied = false;
        String denialMessage = null;
        List<ToolResult> executed = new ArrayList<>();

        if (toolResults != null) {
            for (ToolResult result : toolResults) {
                turns.add(toToolTurn(result));
                executed.add(result);
                if (result.isDenied()) {
                    String reason = (result.result() != null && !result.result().isBlank())
                            ? result.result()
                            : "Authorization required.";
                    denialMessage = "Action denied: " + reason;
                    policyDenied = true;
                    break;
                }
            }
        }

        return new ToolExecutionOutcome(turns, policyDenied, denialMessage, executed);
    }

    private ModelResponse queryModel(List<AssistantMessage> history, List<Tool> tools, ModelRequestContext context) {
        ModelResponse modelResponse = modelClient.generateResponse(new ArrayList<>(history), tools, context);
        if (modelResponse == null) {
            modelResponse = modelClient.generateResponse(new ArrayList<>(history), tools);
        }
        if (modelResponse == null) {
            String fallback = modelClient.chat(new ArrayList<>(history));
            modelResponse = new ModelResponse(fallback != null ? fallback : "");
        }
        return modelResponse;
    }

    private void tagIterationsCount(int iterations) {
        currentSpan().ifPresent(span -> span.tag("agent.iterations.count", String.valueOf(iterations)));
    }

    private Optional<Span> currentSpan() {
        return tracer.map(Tracer::currentSpan);
    }

    private AssistantMessage toUserTurn(String messageText) {
        return AssistantMessage.of(messageText);
    }

    private AssistantMessage toModelTurn(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        AssistantMessage modelTurn = new AssistantMessage();
        modelTurn.setRole(MessageRole.ASSISTANT);
        modelTurn.setToolCallId(toolCall.name());
        try {
            modelTurn.setWidgetPayload(objectMapper.writeValueAsString(toolCall.args()));
        } catch (Exception e) {
            modelTurn.setWidgetPayload("{}");
        }
        modelTurn.setThoughtSignature(toolCall.thoughtSignature());
        modelTurn.setCreatedAt(Instant.now());
        return modelTurn;
    }

    private AssistantMessage toToolTurn(ToolResult toolResult) {
        Objects.requireNonNull(toolResult, "toolResult must not be null");
        AssistantMessage toolTurn = new AssistantMessage();
        toolTurn.setRole(MessageRole.TOOL);
        toolTurn.setToolCallId(toolResult.toolCall().name());
        toolTurn.setContent(toolResult.result());
        toolTurn.setCreatedAt(Instant.now());
        return toolTurn;
    }

    /**
     * Applies the cards of successful tool results in call order: a later card of a type replaces the
     * earlier one (a re-staged draft supersedes the previous draft), and a retraction removes it (a
     * discarded draft leaves no card). Mutating local tools run in call order (see DefaultToolManager), so
     * the {@code ORDER_DRAFT} card left at the end is the draft that is actually open.
     * <p>
     * {@code PRODUCT_LIST} is the exception: searches don't supersede each other ("chargers and cases" may
     * be two searches), so their products are merged into one card, a repeated SKU taking the later values.
     */
    private static void applyWidgetChanges(List<ToolResult> results, Map<String, ChatWidget> widgets) {
        for (ToolResult result : results) {
            if (!result.isSuccess()) {
                continue;
            }
            if (result.retractsWidget() != null) {
                widgets.remove(result.retractsWidget());
            }
            if (result.widget() != null) {
                ChatWidget earlier = widgets.remove(result.widget().type());
                widgets.put(result.widget().type(), combine(earlier, result.widget()));
            }
        }
    }

    private static ChatWidget combine(@Nullable ChatWidget earlier, ChatWidget later) {
        if (earlier != null && earlier.payload() instanceof ProductListCard earlierList
                && later.payload() instanceof ProductListCard laterList) {
            return earlierList.mergedWith(laterList).toWidget();
        }
        return later;
    }

    /**
     * Persists the turn's cards on the reply message: {@code widget_type} lists the card types
     * (comma-separated), {@code widget_payload} holds the {@code [{type, payload}]} array as returned.
     */
    private void attachWidgets(AssistantMessage assistantMsg, List<ChatWidget> widgets) {
        if (widgets.isEmpty()) {
            return;
        }
        try {
            assistantMsg.setWidgetPayload(objectMapper.writeValueAsString(widgets));
            assistantMsg.setWidgetType(String.join(",", widgets.stream().map(ChatWidget::type).toList()));
        } catch (Exception e) {
            log.error("Failed to serialize chat widgets types={} error={}", widgets.stream().map(ChatWidget::type).toList(), e.getMessage());
        }
    }

    private AssistantMessage toAssistantTurn(String reply, @Nullable String thoughtSignature) {
        return AssistantMessage.of(reply, MessageRole.ASSISTANT, thoughtSignature);
    }

    private record ConversationLoopResult(String reply, @Nullable String thoughtSignature, int iterations, List<ChatWidget> widgets,
                                          TurnOutcome outcome) {}
}
