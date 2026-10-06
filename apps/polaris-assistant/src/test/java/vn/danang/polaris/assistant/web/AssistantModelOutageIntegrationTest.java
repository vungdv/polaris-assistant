package vn.danang.polaris.assistant.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpHeaders;
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
import com.github.tomakehurst.wiremock.http.Fault;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.MessageRole;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentClassifier;
import vn.danang.polaris.assistant.intent.RedisIntentManager;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.resilience.ModelCircuitBreakers;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * Gemini outages end to end: HTTP, security, the real
 * {@code GeminiAiModelClient} against a WireMock-stubbed Gemini, the real intent/policy/tool pipeline and
 * PostgreSQL. A failing Gemini call must answer {@code 503} Problem Details (with {@code Retry-After} when Gemini
 * gave one), never a {@code 200} with error text, and must never write error text to the conversation history.
 * Transient failures are retried once, an open circuit breaker fails fast, and a request Gemini rejects (4xx other
 * than 429) answers {@code 500}.
 * <p>
 * The context points Gemini at this class's WireMock server, so no other test can reuse it: it is closed after
 * the class (with its PostgreSQL container) instead of lingering in the context cache until JVM shutdown.
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class AssistantModelOutageIntegrationTest {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/gemini-3.6-flash:generateContent";
    private static final String PROVIDER_ERROR_TEXT = "SECRET-PROVIDER-ERROR-TEXT";

    private static final WireMockServer gemini = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        gemini.start();
    }

    private static final String QUOTE = """
        {"orderable": true, "totalAmount": 49.80, "lines": [
          {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "requestedQuantity": 2,
           "unitPrice": 24.90, "availableQuantity": 200, "lineTotal": 49.80, "problem": null}]}
        """;

    @DynamicPropertySource
    static void geminiProperties(DynamicPropertyRegistry registry) {
        registry.add("polaris.ai.base-url", gemini::baseUrl);
        registry.add("polaris.ai.api-key", () -> "test-wiremock-api-key");
        registry.add("polaris.ai.model", () -> "gemini-3.6-flash");
        registry.add("polaris.ai.timeout-seconds", () -> 5);
    }

    @AfterAll
    static void stopGemini() {
        gemini.stop();
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
    private IntentClassifier intentClassifier;

    @MockitoBean
    private RedisIntentManager redisIntentManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        gemini.resetAll();
        circuitBreakers.circuitBreaker(ModelCircuitBreakers.GEMINI).reset();

        DefaultIntentManager taxonomy = new DefaultIntentManager();
        when(redisIntentManager.listIntents()).thenReturn(taxonomy.listIntents());
        when(redisIntentManager.getIntent(anyString())).thenAnswer(inv -> taxonomy.getIntent(inv.getArgument(0)));
        when(intentClassifier.classify(anyString(), anyList(), any()))
                .thenReturn(new IntentClassification("commerce.order.place", 0.99));

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
    @DisplayName("Given Gemini answers 429 with Retry-After, when chatting, then 503 Problem Details with Retry-After, no provider text, and nothing in history")
    void rate_limited_gemini_returns_503_with_retry_after_and_persists_nothing() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "30")
                        .withBody("{ \"error\": { \"code\": 429, \"message\": \"" + PROVIDER_ERROR_TEXT + "\" } }")));

        String body = chatAsShopper(sessionId, "hello")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/assistant-unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PROVIDER_ERROR_TEXT);
        assertThat(messageRepository.findBySessionIdOrderByIdAsc(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Given Gemini keeps answering 503, when chatting, then one retry, then 503 Problem Details and nothing in history")
    void gemini_server_error_returns_503_and_persists_nothing() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(503).withBody(PROVIDER_ERROR_TEXT)));

        String body = chatAsShopper(sessionId, "hello")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PROVIDER_ERROR_TEXT);
        gemini.verify(2, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(messageRepository.findBySessionIdOrderByIdAsc(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Given Gemini answers 503 once and then replies, when chatting, then the retry succeeds and the reply is 200")
    void transient_gemini_error_is_retried_and_turn_succeeds() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .inScenario("transient").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503).withBody(PROVIDER_ERROR_TEXT))
                .willSetStateTo("recovered"));
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .inScenario("transient").whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "candidates": [{ "content": { "role": "model", "parts": [ { "text": "Hello there!" } ] } }] }
                                """)));

        chatAsShopper(sessionId, "hello")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Hello there!"));

        gemini.verify(2, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(messageRepository.findBySessionIdOrderByIdAsc(sessionId)).extracting(AssistantMessage::getContent)
                .containsExactly("hello", "Hello there!");
    }

    @Test
    @DisplayName("Given Gemini rejects our request with 400, when chatting, then 500 Problem Details without Retry-After, no retry and nothing in history")
    void rejected_request_returns_500_without_retry_after() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(400).withBody(PROVIDER_ERROR_TEXT)));

        String body = chatAsShopper(sessionId, "hello")
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/assistant-error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PROVIDER_ERROR_TEXT);
        gemini.verify(1, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
        assertThat(messageRepository.findBySessionIdOrderByIdAsc(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Given failing Gemini calls opened the breaker, when chatting, then 503 with Retry-After until half-open, without calling Gemini")
    void open_breaker_returns_503_without_calling_gemini() throws Exception {
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withStatus(500).withBody(PROVIDER_ERROR_TEXT)));
        // the test configuration opens the breaker at 50 % failures over 4 calls
        for (int i = 0; i < 4; i++) {
            chatAsShopper(UUID.randomUUID().toString(), "hello").andExpect(status().isServiceUnavailable());
        }
        assertThat(circuitBreakers.circuitBreaker(ModelCircuitBreakers.GEMINI).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        gemini.resetRequests();

        String retryAfter = chatAsShopper(UUID.randomUUID().toString(), "hello")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/assistant-unavailable"))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 30);
        gemini.verify(0, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));
    }

    @Test
    @DisplayName("Given Gemini resets the connection, when chatting, then 503 Problem Details without the exception text")
    void unreachable_gemini_returns_503_without_leaking_exception_text() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        String body = chatAsShopper(sessionId, "hello")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("connection").doesNotContain("IOException", "Failed to communicate");
        assertThat(messageRepository.findBySessionIdOrderByIdAsc(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("Given Gemini stages a draft and then fails (R5), when chatting, then 503, the draft stays open and the tool turns are persisted without error text")
    void gemini_failure_after_tool_ran_persists_tool_turns_and_returns_503() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .inScenario("r5").whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "candidates": [{ "content": { "role": "model", "parts": [
                                  { "functionCall": { "name": "stage_order_draft",
                                      "args": { "items": [ { "sku": "NG-CHARGER-01", "quantity": 2 } ] } } }
                                ] } }] }
                                """))
                .willSetStateTo("tool-ran"));
        gemini.stubFor(post(urlEqualTo(GENERATE_CONTENT_PATH))
                .inScenario("r5").whenScenarioStateIs("tool-ran")
                .willReturn(aResponse().withStatus(500).withBody(PROVIDER_ERROR_TEXT)));

        String body = chatAsShopper(sessionId, "order 2 of NG-CHARGER-01")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PROVIDER_ERROR_TEXT);
        // the 500 after the tool ran is retried once
        gemini.verify(3, postRequestedFor(urlEqualTo(GENERATE_CONTENT_PATH)));

        // The staged draft survives the outage
        assertThat(draftRepository.findOpenDraft(sessionId)).hasValueSatisfying(
                draft -> assertThat(draft.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION));

        // The executed tool turns are remembered; no reply and no error text are
        List<AssistantMessage> history = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        assertThat(history).extracting(AssistantMessage::getRole)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL);
        assertThat(history.get(1).getToolCallId()).isEqualTo("stage_order_draft");
        assertThat(history).extracting(AssistantMessage::getContent)
                .noneMatch(text -> text != null && (text.contains(PROVIDER_ERROR_TEXT)
                        || text.contains("Unable to get response") || text.contains("Failed to communicate")));
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
