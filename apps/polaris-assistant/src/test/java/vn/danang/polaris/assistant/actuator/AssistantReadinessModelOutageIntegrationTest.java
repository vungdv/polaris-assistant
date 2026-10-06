package vn.danang.polaris.assistant.actuator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.entity.AssistantSession;
import vn.danang.polaris.assistant.entity.DraftLine;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.RedisIntentManager;
import vn.danang.polaris.assistant.resilience.ModelCircuitBreakers;
import vn.danang.polaris.assistant.repository.AssistantSessionRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.tools.FakeOrderManagementMcp;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * With Gemini and TypeSafe DOWN, readiness stays UP, {@code /actuator/health} shows both as DOWN, and draft
 * confirm/cancel still work. The health settings are read from the production {@code application.yml}.
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext
@DisplayName("Polaris Assistant - readiness stays UP while the AI models are DOWN")
class AssistantReadinessModelOutageIntegrationTest {

    private static final String MAIN_CONFIG = "src/main/resources/application.yml";
    private static final String READINESS_INCLUDE = "management.endpoint.health.group.readiness.include";
    private static final String SHOW_COMPONENTS = "management.endpoint.health.show-components";
    private static final String GEMINI_MODEL_PATH = "/v1beta/models/gemini-3.6-flash";
    private static final List<DraftLine> LINES = List.of(
            DraftLine.of("NG-EARBUD-01", "Nova Wireless Earbuds", 1, new BigDecimal("99.70")));

    /** Plays both Gemini (always 503) and the Polaris Core MCP endpoint (healthy {@code tools/list}). */
    private static final WireMockServer upstream = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        upstream.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        PropertySource<?> main = new YamlPropertySourceLoader()
                .load("main-application.yml", new FileSystemResource(MAIN_CONFIG)).get(0);
        registry.add(READINESS_INCLUDE, () -> main.getProperty(READINESS_INCLUDE));
        registry.add(SHOW_COMPONENTS, () -> main.getProperty(SHOW_COMPONENTS));

        // the test application.yml switches these indicators off; this suite is about them
        registry.add("management.health.gemini.enabled", () -> true);
        registry.add("management.health.typeSafe.enabled", () -> true);
        registry.add("management.health.polarisMcp.enabled", () -> true);

        registry.add("polaris.ai.base-url", upstream::baseUrl);
        registry.add("polaris.ai.api-key", () -> "test-wiremock-api-key");
        registry.add("polaris.ai.model", () -> "gemini-3.6-flash");
        // port 1 refuses connections: TypeSafe is unreachable
        registry.add("polaris.typesafe.base-url", () -> "http://127.0.0.1:1");
        registry.add("polaris.mcp.core.url", () -> upstream.baseUrl() + "/mcp");
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private AssistantSessionRepository sessionRepository;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @MockitoBean
    private CurrentCustomerClient currentCustomerClient;

    @MockitoBean
    private RedisIntentManager redisIntentManager;

    private FakeOrderManagementMcp orderManagement;

    @BeforeEach
    void setUp() {
        upstream.resetAll();
        circuitBreakers.circuitBreaker(ModelCircuitBreakers.GEMINI).reset();
        upstream.stubFor(get(urlEqualTo(GEMINI_MODEL_PATH))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":{\"code\":503}}")));
        upstream.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/mcp"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"health\",\"result\":{\"tools\":[]}}")));

        orderManagement = new FakeOrderManagementMcp();
        DefaultIntentManager taxonomy = new DefaultIntentManager();
        when(redisIntentManager.listIntents()).thenReturn(taxonomy.listIntents());
        when(redisIntentManager.getIntent(anyString())).thenAnswer(inv -> taxonomy.getIntent(inv.getArgument(0)));
        when(polarisMcpClient.callTool(eq("place_order"), anyMap()))
                .thenAnswer(inv -> orderManagement.placeOrder(inv.getArgument(1)));
        when(currentCustomerClient.findCurrentCustomer(anyString()))
                .thenReturn(Optional.of(new CustomerRef(1L, "Alice Tran")));
    }

    @Test
    @DisplayName("The production readiness group leaves out gemini and typeSafe")
    void production_readiness_group_excludes_the_models() throws IOException {
        PropertySource<?> main = new YamlPropertySourceLoader()
                .load("main-application.yml", new FileSystemResource(MAIN_CONFIG)).get(0);
        List<String> members = List.of(String.valueOf(main.getProperty(READINESS_INCLUDE)).split("\\s*,\\s*"));

        assertThat(members).contains("readinessState", "db", "polarisMcp").doesNotContain("gemini", "typeSafe");
    }

    @Test
    @DisplayName("Given Gemini 503 and TypeSafe unreachable, readiness is 200 UP and lists only process, db and MCP")
    void readiness_stays_up_while_models_are_down() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness").with(shopper("ops")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.polarisMcp.status").value("UP"))
                .andExpect(jsonPath("$.components.gemini").doesNotExist())
                .andExpect(jsonPath("$.components.typeSafe").doesNotExist());
    }

    @Test
    @DisplayName("Given Gemini 503 and TypeSafe unreachable, /actuator/health shows both as DOWN components")
    void health_still_reports_the_models_as_down() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health").with(shopper("ops")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.gemini.status").value("DOWN"))
                .andExpect(jsonPath("$.components.typeSafe.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.polarisMcp.status").value("UP"))
                // statuses only: no exception messages or provider details leak
                .andExpect(jsonPath("$.components.gemini.details").doesNotExist());
        upstream.verify(getRequestedFor(urlEqualTo(GEMINI_MODEL_PATH)));

        // anonymous callers get the aggregate status only
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("Given the Gemini breaker is open, /actuator/health shows each breaker's state and readiness stays UP")
    void health_shows_breaker_states_without_affecting_readiness() throws Exception {
        circuitBreakers.circuitBreaker(ModelCircuitBreakers.GEMINI).transitionToOpenState();

        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health").with(shopper("ops")))
                .andExpect(jsonPath("$.components.circuitBreakers.components.gemini.status").value("CIRCUIT_OPEN"))
                .andExpect(jsonPath("$.components.circuitBreakers.components.typeSafe.status").value("UP"));
        mockMvc.perform(MockMvcRequestBuilders.get("/actuator/health/readiness").with(shopper("ops")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.circuitBreakers").doesNotExist());
    }

    @Test
    @DisplayName("Given Gemini is down, draft confirm and cancel still work and never call a model")
    void draft_confirm_and_cancel_work_while_gemini_is_down() throws Exception {
        // a session holds at most one open draft
        String confirmSession = session("alice");
        String cancelSession = session("alice");
        OrderDraft toConfirm = stageDraft(confirmSession);
        OrderDraft toCancel = stageDraft(cancelSession);
        upstream.resetRequests();

        mockMvc.perform(post("/api/v1/assistant/sessions/{s}/drafts/{d}/confirm", confirmSession, toConfirm.getId())
                        .with(shopper("alice")).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.widgets[0].type").value("ORDER_CONFIRMED"));
        mockMvc.perform(post("/api/v1/assistant/sessions/{s}/drafts/{d}/cancel", cancelSession, toCancel.getId())
                        .with(shopper("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(draftRepository.findById(toConfirm.getId()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(draftRepository.findById(toCancel.getId()).orElseThrow().getStatus()).isEqualTo(DraftStatus.CANCELLED);
        assertThat(orderManagement.ordersCreated()).isEqualTo(1);
        upstream.verify(0, anyRequestedFor(urlMatching("/v1beta/.*")));
    }

    private static RequestPostProcessor shopper(String subject) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(new SimpleGrantedAuthority("PERM_order.write"), new SimpleGrantedAuthority("PERM_catalog.read"));
    }

    private String session(String userId) {
        String id = UUID.randomUUID().toString();
        sessionRepository.saveAndFlush(AssistantSession.open(id, userId, Instant.now()));
        return id;
    }

    private OrderDraft stageDraft(String sessionId) {
        return draftRepository.saveAndFlush(OrderDraft.stage(sessionId, 1L, LINES, OrderDraft.DEFAULT_TTL, Instant.now()));
    }
}
