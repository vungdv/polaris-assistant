package vn.danang.polaris.assistant.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.RedisIntentManager;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.resilience.ModelCircuitBreakers;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * Per-turn time budget end to end: the real TypeSafe classifier and Gemini client against WireMock, with a short
 * turn deadline (2 s), a long Gemini per-call timeout (10 s) and a 1 s TypeSafe timeout, so a turn can only stay
 * within the budget if every Gemini call is capped by what is left of it.
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class AssistantTurnTimeBudgetIntegrationTest {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/gemini-3.6-flash:generateContent";
    private static final String SYSTEM_ONE_PATH = "/v1/systemone";
    private static final Duration TURN_DEADLINE = Duration.ofSeconds(2);
    /** Slack for MockMvc, security and persistence around the turn; still far below any uncapped wait. */
    private static final Duration WITHIN_DEADLINE = TURN_DEADLINE.plusSeconds(2);
    private static final int GEMINI_HANG_MILLIS = 10_000;

    private static final WireMockServer gemini = new WireMockServer(wireMockConfig().dynamicPort());
    private static final WireMockServer typeSafe = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        gemini.start();
        typeSafe.start();
    }

    private static final String QUOTE = """
        {"orderable": true, "totalAmount": 49.80, "lines": [
          {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "requestedQuantity": 2,
           "unitPrice": 24.90, "availableQuantity": 200, "lineTotal": 49.80, "problem": null}]}
        """;

    private static final String ORDER_PLACE_INTENT = """
        { "answers": { "intent": { "type": "choice", "choice": "commerce.order.place", "confidence": 0.99 } } }
        """;

    private static final String STAGE_DRAFT_CALL = """
        { "candidates": [{ "content": { "role": "model", "parts": [
          { "functionCall": { "name": "stage_order_draft",
              "args": { "items": [ { "sku": "NG-CHARGER-01", "quantity": 2 } ] } } }
        ] } }] }
        """;

    @DynamicPropertySource
    static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add("polaris.ai.base-url", gemini::baseUrl);
        registry.add("polaris.ai.api-key", () -> "test-wiremock-api-key");
        registry.add("polaris.ai.model", () -> "gemini-3.6-flash");
        registry.add("polaris.ai.timeout-seconds", () -> 10);
        registry.add("polaris.ai.turn-deadline", TURN_DEADLINE::toString);
        registry.add("polaris.typesafe.base-url", typeSafe::baseUrl);
        registry.add("polaris.typesafe.api-key", () -> "test-wiremock-typesafe-key");
        registry.add("polaris.typesafe.timeout-seconds", () -> 1);
    }

    @AfterAll
    static void stopModels() {
        gemini.stop();
        typeSafe.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssistantMessageRepository messageRepository;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @MockitoBean
    private CurrentCustomerClient currentCustomerClient;

    @MockitoBean
    private RedisIntentManager redisIntentManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        gemini.resetAll();
        typeSafe.resetAll();
        circuitBreakers.circuitBreaker(ModelCircuitBreakers.GEMINI).reset();
        circuitBreakers.circuitBreaker(ModelCircuitBreakers.TYPESAFE).reset();
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(ORDER_PLACE_INTENT)));

        DefaultIntentManager taxonomy = new DefaultIntentManager();
        when(redisIntentManager.listIntents()).thenReturn(taxonomy.listIntents());
        when(redisIntentManager.getIntent(anyString())).thenAnswer(inv -> taxonomy.getIntent(inv.getArgument(0)));

        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(
                Tool.builder("search_available_products", Map.of()).build(),
                Tool.builder("quote_order", Map.of()).build()));
        Map<String, Object> structured = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(QUOTE, Map.class);
        when(polarisMcpClient.callTool(eq("quote_order"), anyMap())).thenReturn(new CallToolResult(
                List.of(TextContent.builder("Quote").build()), false, structured, Map.of()));
        when(currentCustomerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(new CustomerRef(7L, "Alice Tran")));
    }

    @Test
    @DisplayName("Given Gemini hangs beyond the turn budget, when chatting, then 503 Problem Details within about the deadline and nothing in history")
    void slow_gemini_returns_503_at_the_turn_deadline() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(GEMINI_HANG_MILLIS).withBody("{}")));

        long started = System.nanoTime();
        chatAsShopper(sessionId, "hello")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/assistant-unavailable"));

        assertThat(elapsedSince(started)).isLessThan(WITHIN_DEADLINE);
        assertThat(messageRepository.findBySessionIdOrderByIdAsc(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Given a tool ran and the next Gemini call hangs, when chatting, then the second call is capped by the remaining budget: 503, the draft and tool turns are kept")
    void second_call_is_capped_by_remaining_budget_and_keeps_executed_tool_turns() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .inScenario("budget").whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(1_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(STAGE_DRAFT_CALL))
                .willSetStateTo("tool-ran"));
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .inScenario("budget").whenScenarioStateIs("tool-ran")
                .willReturn(aResponse().withStatus(200).withFixedDelay(GEMINI_HANG_MILLIS).withBody("{}")));

        long started = System.nanoTime();
        chatAsShopper(sessionId, "order 2 of NG-CHARGER-01")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(elapsedSince(started)).isLessThan(WITHIN_DEADLINE);
        gemini.verify(2, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));

        assertThat(draftRepository.findOpenDraft(sessionId)).hasValueSatisfying(
                draft -> assertThat(draft.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION));
        List<AssistantMessage> history = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        assertThat(history).extracting(AssistantMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL);
        assertThat(history).extracting(AssistantMessage::getContent)
                .noneMatch(text -> text != null && (text.contains("timed out") || text.contains("deadline")));
    }

    @Test
    @DisplayName("Given TypeSafe hangs, when chatting, then classification falls back to general.conversation after its timeout and the turn still completes")
    void slow_typesafe_falls_back_and_turn_completes() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse()
                .withStatus(200).withFixedDelay(GEMINI_HANG_MILLIS).withBody(ORDER_PLACE_INTENT)));
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "candidates": [{ "content": { "role": "model", "parts": [ { "text": "Hello there!" } ] } }] }
                                """)));

        long started = System.nanoTime();
        chatAsShopper(sessionId, "order 2 of NG-CHARGER-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hello there!"));

        assertThat(elapsedSince(started)).isLessThan(WITHIN_DEADLINE);
        // general.conversation offers no write tools
        gemini.verify(1, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH))
                .withRequestBody(notContaining("stage_order_draft")));
        typeSafe.verify(1, postRequestedFor(urlEqualTo(SYSTEM_ONE_PATH)).withRequestBody(containing("commerce.order.place")));
    }

    @Test
    @DisplayName("Given the TypeSafe breaker is open, when chatting, then the turn completes as general.conversation without waiting for or calling TypeSafe")
    void open_typesafe_breaker_falls_back_without_waiting() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        circuitBreakers.circuitBreaker(ModelCircuitBreakers.TYPESAFE).transitionToOpenState();
        typeSafe.stubFor(post(urlEqualTo(SYSTEM_ONE_PATH)).willReturn(aResponse()
                .withStatus(200).withFixedDelay(GEMINI_HANG_MILLIS).withBody(ORDER_PLACE_INTENT)));
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "candidates": [{ "content": { "role": "model", "parts": [ { "text": "Hello there!" } ] } }] }
                                """)));

        chatAsShopper(sessionId, "order 2 of NG-CHARGER-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hello there!"));

        // TypeSafe is never called, so its timeout is never waited for
        typeSafe.verify(0, postRequestedFor(urlEqualTo(SYSTEM_ONE_PATH)));
        gemini.verify(1, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH))
                .withRequestBody(notContaining("stage_order_draft")));
    }

    private static Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private ResultActions chatAsShopper(String sessionId, String message) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/assistant/chat")
                .with(jwt().jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("PERM_order.write"),
                                new SimpleGrantedAuthority("PERM_catalog.read")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}"));
    }
}
