package vn.danang.polaris.assistant.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.dto.ChatWidget;
import vn.danang.polaris.assistant.dto.ProductListCard;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.IntentDefinition;
import vn.danang.polaris.assistant.intent.IntentToolExecutor;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.web.exception.SessionAccessDeniedException;
import vn.danang.polaris.assistant.tools.DefaultToolManager;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;
import vn.danang.polaris.assistant.tools.ToolResult;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ModelProviderException;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.observability.outcome.AssistantOutcomeMetrics;
import vn.danang.polaris.assistant.observability.trace.CustomNextSpanAspect;

/**
 * Unit tests for {@link AssistantChatService}.
 * <p>
 * SUT Responsibility: Conversational agent orchestrator managing the conversation lifecycle,
 * intent resolution delegation, ReAct loop iteration bounds, and tool batch delegation to {@link IntentResolutionFacade}.
 * Low-level execution details (MCP communication, policy enforcement rules, intent classification algorithms)
 * are encapsulated within collaborators and tested at their respective seam contracts.
 */
class AssistantChatServiceTest {

    private AssistantModelClient modelClient;
    private IntentResolutionFacade intentResolutionFacade;
    private InMemorySessionStore sessionStore;
    private AssistantChatService chatService;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        modelClient = mock(AssistantModelClient.class);
        intentResolutionFacade = mock(IntentResolutionFacade.class);
        sessionStore = new InMemorySessionStore();

        when(intentResolutionFacade.resolve(anyString(), anyList()))
                .thenReturn(new ResolvedIntent(
                        "general.conversation",
                        1.0,
                        true,
                        List.of()
                ));

        chatService = createChatService(modelClient, null, intentResolutionFacade);
    }

    // =========================================================================
    // 1. Happy path — conversational flow, tool loops, history & orchestration
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid message, when sendMessage is called, then forwards to model client and returns response")
        @SuppressWarnings("unchecked")
        void forwards_valid_message_to_model_client_and_returns_response() {
            ChatMessageRequest request = ChatMessageRequest.of("Tell me about Polaris");
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Polaris is an enterprise ecommerce platform.", List.of()));

            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.role()).isEqualTo("ASSISTANT");
            assertThat(response.reply()).isEqualTo("Polaris is an enterprise ecommerce platform.");
            assertThat(response.createdAt()).isNotNull();

            ArgumentCaptor<List<AssistantMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(modelClient).generateResponse(captor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> sentMessages = captor.getValue();
            assertThat(sentMessages).hasSize(1);
            assertThat(sentMessages.getFirst().getRole()).isEqualTo(MessageRole.USER);
            assertThat(sentMessages.getFirst().getContent()).isEqualTo("Tell me about Polaris");
            verify(intentResolutionFacade).resolve(eq("Tell me about Polaris"), anyList());
        }

        @Test
        @DisplayName("Given model requests tool call, when processed in ReAct loop, then delegates to IntentResolutionFacade and loops to final reply")
        void executes_tool_in_react_loop_and_returns_final_reply() {
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall));
            ModelResponse turn2Response = new ModelResponse("I found the Fast Charger 65W for $24.90.", List.of());

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(toolCall, "Found: Fast Charger 65W ($24.90)")));

            ChatMessageRequest request = ChatMessageRequest.of("Find fast chargers");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I found the Fast Charger 65W for $24.90.");
            verify(intentResolutionFacade, times(1)).executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any());
            verify(modelClient, times(2)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
        }

        @Test
        @DisplayName("Given multiple turns with same sessionId, when messages are sent, then preserves conversation history")
        @SuppressWarnings("unchecked")
        void preserves_conversation_history_across_multiple_turns_with_same_session_id() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Hello! How can I help you?", List.of()))
                    .thenReturn(new ModelResponse("Da Nang is a coastal city in Vietnam.", List.of()));

            String sessionId = "session-persist-100";
            ChatMessageRequest msg1 = ChatMessageRequest.of(sessionId, "Hi");
            ChatMessageResponse resp1 = chatService.sendMessage(msg1, "user-123");
            assertThat(resp1.reply()).isEqualTo("Hello! How can I help you?");

            ChatMessageRequest msg2 = ChatMessageRequest.of(sessionId, "Tell me about Da Nang");
            ChatMessageResponse resp2 = chatService.sendMessage(msg2, "user-123");
            assertThat(resp2.reply()).isEqualTo("Da Nang is a coastal city in Vietnam.");

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> secondTurnHistory = historyCaptor.getAllValues().get(1);
            assertThat(secondTurnHistory).hasSize(3);
            assertThat(secondTurnHistory.get(0).getContent()).isEqualTo("Hi");
            assertThat(secondTurnHistory.get(1).getContent()).isEqualTo("Hello! How can I help you?");
            assertThat(secondTurnHistory.get(2).getContent()).isEqualTo("Tell me about Da Nang");
        }

        @Test
        @DisplayName("Given a tool-using turn, when it completes, then persists user, tool call, tool result and reply to the session store in order")
        void persists_the_whole_turn_to_the_session_store() {
            ToolCall toolCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(toolCall)))
                    .thenReturn(new ModelResponse("Fast Charger 65W is $24.90.", List.of()));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(toolCall, "Found: Fast Charger 65W ($24.90)")));

            chatService.sendMessage(ChatMessageRequest.of("session-store-1", "Find fast chargers"), "user-123");

            List<AssistantMessage> persisted = sessionStore.persisted("session-store-1");
            assertThat(persisted).extracting(AssistantMessage::getRole).containsExactly(
                    MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT);
            assertThat(persisted.getFirst().getContent()).isEqualTo("Find fast chargers");
            assertThat(persisted.getLast().getContent()).isEqualTo("Fast Charger 65W is $24.90.");
            assertThat(persisted).allSatisfy(m -> assertThat(m.getSessionId()).isEqualTo("session-store-1"));
        }

        @Test
        @DisplayName("Given parallel tool calls returned by model, when appended to history, then groups all ASSISTANT turns before TOOL turns")
        @SuppressWarnings("unchecked")
        void groups_parallel_model_turns_before_tool_turns_in_history() {
            ToolCall toolCall1 = new ToolCall("search_products", Map.of("query", "charger"), "sig_parallel_call");
            ToolCall toolCall2 = new ToolCall("search_promotions", Map.of("category", "all"), null);

            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall1, toolCall2));
            ModelResponse turn2Response = new ModelResponse("Found charger with 10% discount.", List.of());

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall1, toolCall2)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(
                            ToolResult.success(toolCall1, "Charger"),
                            ToolResult.success(toolCall2, "10% off")
                    ));

            ChatMessageRequest request = ChatMessageRequest.of("Search charger and deals");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Found charger with 10% discount.");

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
            assertThat(turn2History).hasSize(5);

            assertThat(turn2History.get(0).getRole()).isEqualTo(MessageRole.USER);
            assertThat(turn2History.get(0).getContent()).isEqualTo("Search charger and deals");

            assertThat(turn2History.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(turn2History.get(1).getToolCallId()).isEqualTo("search_products");
            assertThat(turn2History.get(1).getThoughtSignature()).isEqualTo("sig_parallel_call");

            assertThat(turn2History.get(2).getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(turn2History.get(2).getToolCallId()).isEqualTo("search_promotions");

            assertThat(turn2History.get(3).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(turn2History.get(3).getToolCallId()).isEqualTo("search_products");
            assertThat(turn2History.get(3).getContent()).isEqualTo("Charger");

            assertThat(turn2History.get(4).getRole()).isEqualTo(MessageRole.TOOL);
            assertThat(turn2History.get(4).getToolCallId()).isEqualTo("search_promotions");
            assertThat(turn2History.get(4).getContent()).isEqualTo("10% off");
        }
    }

    // =========================================================================
    // 2. Invalid input & policy denials
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input & policy denials")
    class InvalidInput {

        @Test
        @DisplayName("Given model requests tool call and facade tool execution is denied, when processed, then short-circuits ReAct loop and returns denial reason")
        void short_circuits_and_returns_denial_reason_when_mcp_tool_execution_is_denied() {
            ToolCall toolCall = new ToolCall("place_order", Map.of("sku", "PROD-1"));
            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.denied(toolCall, "Missing scope 'order.write'.")));

            ChatMessageRequest request = ChatMessageRequest.of("buy wireless earbuds");
            ChatMessageResponse response = chatService.sendMessage(request, "user-no-scope");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Action denied: Missing scope 'order.write'.");
            verify(modelClient, times(1)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
            verify(intentResolutionFacade, times(1)).executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any());
        }

        @Test
        @DisplayName("Given a session opened by another user, when sendMessage is called, then throws SessionAccessDeniedException and never calls the model")
        void rejects_access_to_another_users_session() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Your cart has 2 chargers.", List.of()));
            chatService.sendMessage(ChatMessageRequest.of("session-alice", "What is in my cart?"), "user-alice");

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("session-alice", "Show me"), "user-mallory"))
                    .isInstanceOf(SessionAccessDeniedException.class);

            verify(modelClient, times(1)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
            assertThat(sessionStore.persisted("session-alice")).hasSize(2);
        }

        @Test
        @DisplayName("Given the model fails mid-turn, when sendMessage throws, then nothing from the failed turn is persisted")
        void persists_nothing_when_the_turn_fails() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenThrow(new RuntimeException("Gemini model failure"));

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("session-fail", "Hi"), "user-123"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(sessionStore.persisted("session-fail")).isEmpty();
            verify(intentResolutionFacade, never()).executeToolCalls(anyList(), any(ToolExecutionContext.class), any());
        }

        @Test
        @DisplayName("Given turn fails with model exception, when tracer present, then tags error and records exception on span")
        void records_error_and_propagates_exception_when_model_fails() {
            Span span = mock(Span.class);
            Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
            Tracer tracer = mockTracerSetup(span, spanInScope);

            AssistantChatService serviceWithTracer = createChatService(modelClient, tracer, intentResolutionFacade);

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenThrow(new RuntimeException("Gemini model failure"));

            ChatMessageRequest request = ChatMessageRequest.of("sess-err", "Find fast chargers");

            assertThatThrownBy(() -> serviceWithTracer.sendMessage(request, "user-err"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Gemini model failure");

            verify(span).error(any(RuntimeException.class));
            verify(span).tag("error", "true");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("Given primary context call returns null, when executed, then falls back to overloaded generateResponse or chat")
        void falls_back_to_alternate_model_calls_when_primary_context_call_returns_null() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(null);
            when(modelClient.generateResponse(anyList(), anyList()))
                    .thenReturn(null);
            when(modelClient.chat(anyList()))
                    .thenReturn("Fallback chat reply");

            ChatMessageRequest request = ChatMessageRequest.of("Hello fallback");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Fallback chat reply");
            verify(modelClient).chat(anyList());
        }
    }

    // =========================================================================
    // 3. Edge cases — iteration limits, telemetry tags, and metadata contexts
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given looping tool calls, when exceeding maximum 5 iterations, then terminates safely with completion reply")
        void terminates_safely_when_tool_calls_exceed_max_iterations() {
            ToolCall loopCall = new ToolCall("looping_tool", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(loopCall)));

            when(intentResolutionFacade.executeToolCalls(eq(List.of(loopCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(loopCall, "ok")));

            ChatMessageRequest request = ChatMessageRequest.of("Run loop");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I have completed processing your request.");
            verify(modelClient, times(5)).generateResponse(anyList(), anyList(), any(ModelRequestContext.class));
            verify(intentResolutionFacade, times(5)).executeToolCalls(eq(List.of(loopCall)), any(ToolExecutionContext.class), any());
        }

        @Test
        @DisplayName("Given active tracer, when ReAct turn completes, then tags agent.iterations.count on active span")
        void tags_iteration_count_on_active_span_when_tracer_is_present() {
            Span span = mock(Span.class);
            Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
            Tracer tracer = mockTracerSetup(span, spanInScope);

            AssistantChatService serviceWithTracer = createChatService(modelClient, tracer, intentResolutionFacade);

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Direct response", List.of()));

            ChatMessageRequest request = ChatMessageRequest.of("sess-tracer", "Hello");
            ChatMessageResponse response = serviceWithTracer.sendMessage(request, "user-trace");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("Direct response");
            verify(span).tag("agent.iterations.count", "1");
            verify(span).tag("agent.user_id", "user-trace");
            verify(span).tag("agent.session_id", "sess-tracer");
            verify(span).end();
        }

        @Test
        @DisplayName("Given model response with thoughtSignature, when tool executed, then preserves thoughtSignature on AssistantMessage in history")
        @SuppressWarnings("unchecked")
        void propagates_thought_signature_from_tool_call_to_history() {
            ToolCall toolCall = new ToolCall("default_api:search_available_products", Map.of("query", "charger"), "sig_token_xyz789");
            ModelResponse turn1Response = new ModelResponse("", List.of(toolCall));
            ModelResponse turn2Response = new ModelResponse("I found the charger for you.", List.of(), "sig_final_reply_token");

            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(turn1Response)
                    .thenReturn(turn2Response);

            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(toolCall, "Product found: Charger")));

            ChatMessageRequest request = ChatMessageRequest.of("Search for charger");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response).isNotNull();
            assertThat(response.reply()).isEqualTo("I found the charger for you.");

            ArgumentCaptor<List<AssistantMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            verify(modelClient, times(2)).generateResponse(historyCaptor.capture(), anyList(), any(ModelRequestContext.class));

            List<AssistantMessage> turn2History = historyCaptor.getAllValues().get(1);
            assertThat(turn2History).hasSize(3);

            AssistantMessage modelTurn = turn2History.get(1);
            assertThat(modelTurn.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(modelTurn.getToolCallId()).isEqualTo("default_api:search_available_products");
            assertThat(modelTurn.getThoughtSignature()).isEqualTo("sig_token_xyz789");
        }

        @Test
        @DisplayName("Given ReAct turn iteration and intent, when querying model client, then passes ModelRequestContext with iteration and intent details")
        void passes_model_request_context_with_iteration_and_intent_metadata() {
            Tool tool = Tool.builder("search_available_products", Map.of()).description("Search catalog").build();
            ResolvedIntent resolved = new ResolvedIntent(
                    "catalog.product.search",
                    0.95,
                    true,
                    List.of(tool)
            );
            when(intentResolutionFacade.resolve(eq("Find chargers in stock"), anyList()))
                    .thenReturn(resolved);

            when(modelClient.generateResponse(anyList(), eq(List.of(tool)), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Found the products.", List.of()));

            ChatMessageRequest request = ChatMessageRequest.of("Find chargers in stock");
            ChatMessageResponse response = chatService.sendMessage(request, "user-123");

            assertThat(response.reply()).isEqualTo("Found the products.");

            ArgumentCaptor<ModelRequestContext> contextCaptor = ArgumentCaptor.forClass(ModelRequestContext.class);
            verify(modelClient).generateResponse(anyList(), eq(List.of(tool)), contextCaptor.capture());

            ModelRequestContext captured = contextCaptor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.iteration()).isEqualTo(1);
            assertThat(captured.intentId()).isEqualTo("catalog.product.search");
            assertThat(captured.intentConfidence()).isEqualTo(0.95);
            assertThat(captured.toolsOfferedCount()).isEqualTo(1);
        }
    }

    // =========================================================================
    // 4. Model unavailable — fail loudly, never persist error text (plan A1, R1/R5)
    // =========================================================================
    @Nested
    @DisplayName("4. Model unavailable")
    class ModelUnavailable {

        private final ModelUnavailableException outage =
                new ModelUnavailableException("Gemini returned HTTP 503", new ModelProviderException(503), Duration.ofSeconds(20));

        @Test
        @DisplayName("Given Gemini is unavailable on the first call, when sendMessage is called, then the exception propagates and nothing is persisted")
        void propagates_and_persists_nothing_when_model_unavailable_before_any_tool() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class))).thenThrow(outage);

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("sess-down", "Hello"), "user-123"))
                    .isSameAs(outage);

            assertThat(sessionStore.persisted("sess-down")).isEmpty();
        }

        @Test
        @DisplayName("Given a tool ran and then Gemini fails (R5), when sendMessage is called, then the user and tool turns are persisted without any reply or error text")
        void persists_executed_tool_turns_then_rethrows_when_model_fails_mid_loop() {
            ToolCall stage = new ToolCall("stage_order_draft", Map.of("items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 2))));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(stage)))
                    .thenThrow(outage);
            when(intentResolutionFacade.executeToolCalls(eq(List.of(stage)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(stage, "Draft staged: draft-1")));

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("sess-r5", "order 2 chargers"), "user-123"))
                    .isSameAs(outage);

            List<AssistantMessage> persisted = sessionStore.persisted("sess-r5");
            assertThat(persisted).extracting(AssistantMessage::getRole)
                    .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL);
            assertThat(persisted.get(1).getToolCallId()).isEqualTo("stage_order_draft");
            assertThat(persisted.get(2).getContent()).isEqualTo("Draft staged: draft-1");
            assertThat(persisted).extracting(AssistantMessage::getContent)
                    .noneMatch(content -> content != null && (content.contains("503") || content.contains("Unable")));
        }

        @Test
        @DisplayName("Given Gemini is unavailable, when sendMessage is called, then the turn is counted as 'failed'")
        void counts_failed_turn() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class))).thenThrow(outage);

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("Hello"), "user-123"))
                    .isInstanceOf(ModelUnavailableException.class);

            assertThat(meters.find("polaris.assistant.turns").tags("outcome", "failed").counter().count()).isEqualTo(1);
        }
    }

    // =========================================================================
    // 5. Turn time budget
    // =========================================================================
    @Nested
    @DisplayName("5. Turn time budget")
    class TurnTimeBudget {

        @Test
        @DisplayName("Given a turn with tool iterations, when sendMessage is called, then every model call gets the same deadline, one turn budget from the start")
        void passes_one_turn_deadline_to_every_model_call() {
            Duration budget = Duration.ofSeconds(25);
            AssistantChatService service = new AssistantChatService(modelClient, null, intentResolutionFacade, sessionStore,
                    new ObjectMapper(), new AssistantOutcomeMetrics(meters), budget);
            ToolCall search = new ToolCall("search_available_products", Map.of("query", "charger"));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(search)))
                    .thenReturn(new ModelResponse("Found it.", List.of()));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(search)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(search, "Found")));

            Instant before = Instant.now();
            service.sendMessage(ChatMessageRequest.of("Find chargers"), "user-123");
            Instant after = Instant.now();

            ArgumentCaptor<ModelRequestContext> contexts = ArgumentCaptor.forClass(ModelRequestContext.class);
            verify(modelClient, times(2)).generateResponse(anyList(), anyList(), contexts.capture());
            assertThat(contexts.getAllValues()).extracting(ModelRequestContext::deadline).containsOnly(contexts.getValue().deadline());
            assertThat(contexts.getValue().deadline()).hasValueSatisfying(
                    deadline -> assertThat(deadline).isBetween(before.plus(budget), after.plus(budget)));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        if (instance == null) {
            return null;
        }
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    // =========================================================================
    // Widgets — cards produced by tool calls reach the response and the history
    // =========================================================================
    @Nested
    @DisplayName("Widgets")
    class Widgets {

        @Test
        @DisplayName("Given a tool result carrying a widget, when the turn ends, then the response and the persisted reply carry it")
        void returns_and_persists_widgets() {
            ToolCall stage = new ToolCall("stage_order_draft", Map.of("items", List.of()));
            ChatWidget card = new ChatWidget(ChatWidget.ORDER_DRAFT, Map.of("draftId", "dft-1"));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(stage)))
                    .thenReturn(new ModelResponse("Please review the draft.", List.of()));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(stage)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(stage, "staged").withWidget(card)));

            ChatMessageResponse response = chatService.sendMessage(ChatMessageRequest.of("sess-w", "order 2"), "user-1");

            assertThat(response.widgets()).containsExactly(card);
            AssistantMessage reply = sessionStore.persisted("sess-w").getLast();
            assertThat(reply.getRole()).isEqualTo(MessageRole.ASSISTANT);
            assertThat(reply.getWidgetType()).isEqualTo("ORDER_DRAFT");
            assertThat(reply.getWidgetPayload()).isEqualTo("[{\"type\":\"ORDER_DRAFT\",\"payload\":{\"draftId\":\"dft-1\"}}]");
        }

        @Test
        @DisplayName("Given two drafts staged in one turn, when the turn ends, then only the latest ORDER_DRAFT card is returned")
        void latest_card_of_a_type_wins() {
            ToolCall first = new ToolCall("stage_order_draft", Map.of("n", 1));
            ToolCall second = new ToolCall("stage_order_draft", Map.of("n", 2));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(first)))
                    .thenReturn(new ModelResponse("", List.of(second)))
                    .thenReturn(new ModelResponse("Updated.", List.of()));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(first)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(first, "a").withWidget(new ChatWidget(ChatWidget.ORDER_DRAFT, Map.of("draftId", "dft-1")))));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(second)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(second, "b").withWidget(new ChatWidget(ChatWidget.ORDER_DRAFT, Map.of("draftId", "dft-2")))));

            ChatMessageResponse response = chatService.sendMessage(ChatMessageRequest.of("sess-w2", "make it 3"), "user-1");

            assertThat(response.widgets()).singleElement()
                    .extracting(ChatWidget::payload).isEqualTo(Map.of("draftId", "dft-2"));
        }

        @Test
        @DisplayName("Given stage then discard in one batch, when the turn ends, then no ORDER_DRAFT card is returned")
        void discard_after_stage_retracts_card() {
            ToolCall stage = new ToolCall("stage_order_draft", Map.of());
            ToolCall discard = new ToolCall("discard_order_draft", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(stage, discard)))
                    .thenReturn(new ModelResponse("Draft discarded.", List.of()));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(stage, discard)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(
                            ToolResult.success(stage, "a").withWidget(new ChatWidget(ChatWidget.ORDER_DRAFT, Map.of("draftId", "dft-1"))),
                            ToolResult.success(discard, "b").retractingWidget(ChatWidget.ORDER_DRAFT)));

            ChatMessageResponse response = chatService.sendMessage(ChatMessageRequest.of("sess-w4", "never mind"), "user-1");

            assertThat(response.widgets()).isEmpty();
            assertThat(sessionStore.persisted("sess-w4").getLast().getWidgetType()).isNull();
        }

        @Test
        @DisplayName("Given a failed discard after a stage, when the turn ends, then the staged card is kept")
        void failed_discard_keeps_card() {
            ToolCall stage = new ToolCall("stage_order_draft", Map.of());
            ToolCall discard = new ToolCall("discard_order_draft", Map.of());
            ChatWidget card = new ChatWidget(ChatWidget.ORDER_DRAFT, Map.of("draftId", "dft-1"));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(stage, discard)))
                    .thenReturn(new ModelResponse("Could not discard.", List.of()));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(stage, discard)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(
                            ToolResult.success(stage, "a").withWidget(card),
                            ToolResult.error(discard, "conflict")));

            ChatMessageResponse response = chatService.sendMessage(ChatMessageRequest.of("sess-w5", "drop it"), "user-1");

            assertThat(response.widgets()).containsExactly(card);
        }

        @Test
        @DisplayName("Given no tool produced a card, when the turn ends, then widgets is empty and nothing is persisted as a widget")
        void no_widgets_by_default() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Hi!", List.of()));

            ChatMessageResponse response = chatService.sendMessage(ChatMessageRequest.of("sess-w3", "hi"), "user-1");

            assertThat(response.widgets()).isEmpty();
            assertThat(sessionStore.persisted("sess-w3").getLast().getWidgetType()).isNull();
        }
    }

    // =========================================================================
    // Product cards (S8) — a search turn through the real tool pipeline yields a PRODUCT_LIST card
    // =========================================================================
    @Nested
    @DisplayName("Product cards")
    class ProductCards {

        private static final String CHARGERS = """
            {"totalElements": 2, "products": [
              {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "category": "Chargers",
               "price": 24.90, "stockQuantity": 200, "available": true},
              {"sku": "NG-CHARGER-02", "name": "Nova 30W Charger", "category": null,
               "price": 14.10, "stockQuantity": 0, "available": false}]}
            """;

        private static final String CASES = """
            {"totalElements": 2, "products": [
              {"sku": "NG-CASE-01", "name": "Nova Phone Case", "category": "Accessories",
               "price": 14.90, "stockQuantity": 50, "available": true},
              {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "category": "Chargers",
               "price": 24.90, "stockQuantity": 198, "available": true}]}
            """;

        private PolarisMcpClient polarisMcpClient;
        private AssistantChatService searchChatService;

        @BeforeEach
        void setUpPipeline() {
            polarisMcpClient = mock(PolarisMcpClient.class);
            DefaultIntentManager taxonomy = new DefaultIntentManager();
            IntentDefinition search = taxonomy.getIntent("catalog.product.search").orElseThrow();
            DefaultToolManager toolManager = new DefaultToolManager(polarisMcpClient, Runnable::run, List.of());
            IntentResolutionFacade facade = new IntentResolutionFacade(
                    (message, history, tools) -> new ResolvedIntent(search.id(), 0.99, true, List.of(), search),
                    toolManager,
                    new IntentToolExecutor(toolManager));
            searchChatService = createChatService(modelClient, null, facade);
        }

        private CallToolResult searchResult(String text, String structuredJson) throws Exception {
            // Parsed like HttpPolarisMcpClient does: floats as BigDecimal
            Map<String, Object> structured = structuredJson == null ? null : new ObjectMapper()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(structuredJson, new TypeReference<Map<String, Object>>() {});
            return new CallToolResult(List.of(TextContent.builder(text).build()), false, structured, Map.of());
        }

        @Test
        @DisplayName("Given a successful search, when the turn ends, then the reply carries a PRODUCT_LIST card built from the tool's structured data")
        void search_turn_returns_product_list_widget() throws Exception {
            ToolCall searchCall = new ToolCall("search_available_products", Map.of("query", "charger"));
            when(polarisMcpClient.callTool(eq("search_available_products"), any()))
                    .thenReturn(searchResult("Found 2 product(s): ...", CHARGERS));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(searchCall)))
                    .thenReturn(new ModelResponse("Here are two chargers.", List.of()));

            ChatMessageResponse response = searchChatService.sendMessage(ChatMessageRequest.of("sess-p1", "fast chargers"), "user-1");

            assertThat(response.widgets()).singleElement().satisfies(widget -> {
                assertThat(widget.type()).isEqualTo(ChatWidget.PRODUCT_LIST);
                ProductListCard card = (ProductListCard) widget.payload();
                assertThat(card.products()).extracting(ProductListCard.Product::sku)
                        .containsExactly("NG-CHARGER-01", "NG-CHARGER-02");
                ProductListCard.Product first = card.products().getFirst();
                assertThat(first.name()).isEqualTo("Nova 65W Fast Charger");
                assertThat(first.category()).isEqualTo("Chargers");
                assertThat(first.price()).isEqualTo(new BigDecimal("24.90"));
                assertThat(first.stockQuantity()).isEqualTo(200);
                assertThat(first.available()).isTrue();
                assertThat(card.products().get(1).category()).isNull();
                assertThat(card.products().get(1).available()).isFalse();
            });

            // The model saw only the text; the card is persisted on the reply
            List<AssistantMessage> persisted = sessionStore.persisted("sess-p1");
            assertThat(persisted).filteredOn(m -> m.getRole() == MessageRole.TOOL)
                    .singleElement().extracting(AssistantMessage::getContent).isEqualTo("Found 2 product(s): ...");
            AssistantMessage reply = persisted.getLast();
            assertThat(reply.getWidgetType()).isEqualTo("PRODUCT_LIST");
            assertThat(reply.getWidgetPayload()).contains("\"sku\":\"NG-CHARGER-01\"").contains("\"price\":24.90");
        }

        @Test
        @DisplayName("Given two searches in one turn, when the turn ends, then one PRODUCT_LIST card holds the products of both, a repeated SKU once with its later values")
        void two_searches_merge_into_one_card() throws Exception {
            ToolCall chargers = new ToolCall("search_available_products", Map.of("query", "charger"));
            ToolCall cases = new ToolCall("search_available_products", Map.of("query", "case"));
            when(polarisMcpClient.callTool(eq("search_available_products"), eq(Map.of("query", "charger"))))
                    .thenReturn(searchResult("chargers", CHARGERS));
            when(polarisMcpClient.callTool(eq("search_available_products"), eq(Map.of("query", "case"))))
                    .thenReturn(searchResult("cases", CASES));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(chargers)))
                    .thenReturn(new ModelResponse("", List.of(cases)))
                    .thenReturn(new ModelResponse("Chargers and cases.", List.of()));

            ChatMessageResponse response = searchChatService.sendMessage(ChatMessageRequest.of("sess-p2", "chargers and cases"), "user-1");

            assertThat(response.widgets()).singleElement().satisfies(widget -> {
                ProductListCard card = (ProductListCard) widget.payload();
                assertThat(card.products()).extracting(ProductListCard.Product::sku)
                        .containsExactly("NG-CHARGER-01", "NG-CHARGER-02", "NG-CASE-01");
                assertThat(card.products().getFirst().stockQuantity()).isEqualTo(198);
            });
        }

        @Test
        @DisplayName("Given an empty or failed search, when the turn ends, then no PRODUCT_LIST card is returned")
        void empty_or_failed_search_returns_no_card() throws Exception {
            ToolCall empty = new ToolCall("search_available_products", Map.of("query", "zzz"));
            ToolCall failing = new ToolCall("search_available_products", Map.of("page", -1));
            when(polarisMcpClient.callTool(eq("search_available_products"), eq(Map.of("query", "zzz"))))
                    .thenReturn(searchResult("No products found matching the specified criteria.",
                            "{\"totalElements\": 0, \"products\": []}"));
            when(polarisMcpClient.callTool(eq("search_available_products"), eq(Map.of("page", -1))))
                    .thenReturn(new CallToolResult(List.of(TextContent.builder("Error searching products: bad page").build()),
                            true, null, Map.of()));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(empty, failing)))
                    .thenReturn(new ModelResponse("Nothing matched.", List.of()));

            ChatMessageResponse response = searchChatService.sendMessage(ChatMessageRequest.of("sess-p3", "zzz"), "user-1");

            assertThat(response.widgets()).isEmpty();
            assertThat(sessionStore.persisted("sess-p3").getLast().getWidgetType()).isNull();
        }
    }

    @Nested
    @DisplayName("Outcome metrics")
    class OutcomeMetrics {

        private static final IntentDefinition SEARCH = new IntentDefinition("catalog.product.search", "search", List.of());

        private double turns(String intent, String outcome) {
            var counter = meters.find("polaris.assistant.turns").tags("intent", intent, "outcome", outcome).counter();
            return counter != null ? counter.count() : 0;
        }

        private void resolveTo(ResolvedIntent intent) {
            when(intentResolutionFacade.resolve(anyString(), anyList())).thenReturn(intent);
        }

        @Test
        @DisplayName("Given a confident intent and a model reply, then one 'answered' turn tagged with the intent")
        void answered() {
            resolveTo(new ResolvedIntent("catalog.product.search", 0.95, true, List.of(), SEARCH));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Here are chargers.", List.of()));

            chatService.sendMessage(ChatMessageRequest.of("find chargers"), "user-1");

            assertThat(turns("catalog.product.search", "answered")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given confidence below threshold, then 'fallback'")
        void fallback() {
            IntentDefinition general = new IntentDefinition("general.conversation", "chat", List.of());
            resolveTo(new ResolvedIntent("general.conversation", 0.3, false, List.of(), general));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("Could you say more?", List.of()));

            chatService.sendMessage(ChatMessageRequest.of("hmm"), "user-1");

            assertThat(turns("general.conversation", "fallback")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given a denied tool call, then 'policy_denied'")
        void policy_denied() {
            resolveTo(new ResolvedIntent("catalog.product.search", 0.95, true, List.of(), SEARCH));
            ToolCall toolCall = new ToolCall("place_order", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(toolCall)));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(toolCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.denied(toolCall, "Missing scope.")));

            chatService.sendMessage(ChatMessageRequest.of("buy"), "user-1");

            assertThat(turns("catalog.product.search", "policy_denied")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given the model loops on tools to the limit, then 'iteration_limit'")
        void iteration_limit() {
            ToolCall loopCall = new ToolCall("looping_tool", Map.of());
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("", List.of(loopCall)));
            when(intentResolutionFacade.executeToolCalls(eq(List.of(loopCall)), any(ToolExecutionContext.class), any()))
                    .thenReturn(List.of(ToolResult.success(loopCall, "ok")));

            chatService.sendMessage(ChatMessageRequest.of("loop"), "user-1");

            // the default setUp intent has no matched definition
            assertThat(turns("unknown", "iteration_limit")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given the model throws, then 'failed' and the error still propagates")
        void failed() {
            resolveTo(new ResolvedIntent("catalog.product.search", 0.95, true, List.of(), SEARCH));
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenThrow(new RuntimeException("Gemini model failure"));

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("find"), "user-1"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(turns("catalog.product.search", "failed")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given intent resolution throws, then 'failed' with intent 'unknown'")
        void failed_before_intent() {
            when(intentResolutionFacade.resolve(anyString(), anyList())).thenThrow(new IllegalStateException("classifier down"));

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("find"), "user-1"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(turns("unknown", "failed")).isEqualTo(1);
        }

        @Test
        @DisplayName("Given another user's session, then no turn is counted: access is refused before the agent runs")
        void access_denied_is_not_a_turn() {
            when(modelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                    .thenReturn(new ModelResponse("ok", List.of()));
            chatService.sendMessage(ChatMessageRequest.of("session-alice", "hi"), "user-alice");

            assertThatThrownBy(() -> chatService.sendMessage(ChatMessageRequest.of("session-alice", "hi"), "user-mallory"))
                    .isInstanceOf(SessionAccessDeniedException.class);

            assertThat(meters.find("polaris.assistant.turns").counters()).singleElement()
                    .satisfies(counter -> assertThat(counter.count()).isEqualTo(1));
        }
    }

    private AssistantChatService createChatService(
            AssistantModelClient modelClient,
            Tracer tracer,
            IntentResolutionFacade facade) {
        AssistantChatService target = new AssistantChatService(
                modelClient,
                providerOf(tracer),
                facade != null ? facade : intentResolutionFacade,
                sessionStore,
                new ObjectMapper(),
                new AssistantOutcomeMetrics(meters)
        );
        if (tracer != null) {
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new CustomNextSpanAspect(tracer));
            return factory.getProxy();
        }
        return target;
    }

    private Tracer mockTracerSetup(Span mockSpan, Tracer.SpanInScope mockSpanInScope) {
        Tracer tracer = mock(Tracer.class);
        when(tracer.nextSpan()).thenReturn(mockSpan);
        when(tracer.currentSpan()).thenReturn(mockSpan);
        when(mockSpan.name(anyString())).thenReturn(mockSpan);
        when(mockSpan.tag(anyString(), anyString())).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);
        when(mockSpan.event(anyString())).thenReturn(mockSpan);
        when(tracer.withSpan(mockSpan)).thenReturn(mockSpanInScope);
        return tracer;
    }
}
