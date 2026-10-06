package vn.danang.polaris.assistant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.ai.ToolCall;
import vn.danang.polaris.assistant.customer.CurrentCustomerClient;
import vn.danang.polaris.assistant.customer.CustomerRef;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.DraftStatus;
import vn.danang.polaris.assistant.entity.OrderDraft;
import vn.danang.polaris.assistant.intent.DefaultIntentManager;
import vn.danang.polaris.assistant.intent.IntentClassification;
import vn.danang.polaris.assistant.intent.IntentClassifier;
import vn.danang.polaris.assistant.intent.RedisIntentManager;
import vn.danang.polaris.assistant.repository.AssistantMessageRepository;
import vn.danang.polaris.assistant.repository.OrderDraftRepository;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * A staging chat turn end to end through HTTP, security, the real intent/policy/tool pipeline and
 * PostgreSQL: the model's {@code stage_order_draft} call yields an {@code ORDER_DRAFT} widget and a
 * persisted draft, and no order is placed. Only the external seams are mocked (model, intent classifier,
 * Polaris Core MCP and {@code /customers/me}).
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssistantOrderStagingIntegrationTest {

    private static final String QUOTE = """
        {"orderable": true, "totalAmount": 49.80, "lines": [
          {"sku": "NG-CHARGER-01", "name": "Nova 65W Fast Charger", "requestedQuantity": 2,
           "unitPrice": 24.90, "availableQuantity": 200, "lineTotal": 49.80, "problem": null}]}
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderDraftRepository draftRepository;

    @Autowired
    private AssistantMessageRepository messageRepository;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @MockitoBean
    private CurrentCustomerClient currentCustomerClient;

    @MockitoBean
    private IntentClassifier intentClassifier;

    @MockitoBean
    private RedisIntentManager redisIntentManager;

    private final ToolCall stageCall = new ToolCall("stage_order_draft",
            Map.of("items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 2))));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        DefaultIntentManager taxonomy = new DefaultIntentManager();
        when(redisIntentManager.listIntents()).thenReturn(taxonomy.listIntents());
        when(redisIntentManager.getIntent(anyString())).thenAnswer(inv -> taxonomy.getIntent(inv.getArgument(0)));
        when(intentClassifier.classify(anyString(), anyList(), any()))
                .thenReturn(new IntentClassification("commerce.order.place", 0.99));

        when(polarisMcpClient.listAvailableTools()).thenReturn(List.of(
                Tool.builder("search_available_products", Map.of()).build(),
                Tool.builder("place_order", Map.of()).build(),
                Tool.builder("quote_order", Map.of()).build()));
        Map<String, Object> structured = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(QUOTE, Map.class);
        when(polarisMcpClient.callTool(eq("quote_order"), anyMap())).thenReturn(new CallToolResult(
                List.of(TextContent.builder("Quote").build()), false, structured, Map.of()));
        when(currentCustomerClient.findCurrentCustomer(anyString())).thenReturn(Optional.of(new CustomerRef(7L, "Alice Tran")));

        when(assistantModelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(new ModelResponse("", List.of(stageCall)))
                .thenReturn(new ModelResponse("Here is your draft. Click Submit Order to place it.", List.of()));
    }

    @Test
    @DisplayName("Given a signed-in shopper with order.write, when the model stages a draft, then the reply carries an ORDER_DRAFT widget, the draft is persisted and no order is placed")
    @SuppressWarnings("unchecked")
    void staging_turn_returns_order_draft_widget_and_places_no_order() throws Exception {
        String sessionId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .with(jwt().jwt(j -> j.subject("alice"))
                                .authorities(new SimpleGrantedAuthority("PERM_order.write"),
                                        new SimpleGrantedAuthority("PERM_catalog.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"order 2 of NG-CHARGER-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Here is your draft. Click Submit Order to place it."))
                .andExpect(jsonPath("$.widgets.length()").value(1))
                .andExpect(jsonPath("$.widgets[0].type").value("ORDER_DRAFT"))
                .andExpect(jsonPath("$.widgets[0].payload.draftId").isNotEmpty())
                .andExpect(jsonPath("$.widgets[0].payload.customerId").value(7))
                .andExpect(jsonPath("$.widgets[0].payload.customerName").value("Alice Tran"))
                .andExpect(jsonPath("$.widgets[0].payload.total").value(49.80))
                .andExpect(jsonPath("$.widgets[0].payload.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.widgets[0].payload.items[0].sku").value("NG-CHARGER-01"))
                .andExpect(jsonPath("$.widgets[0].payload.items[0].quantity").value(2));

        // The model was offered the draft tools
        ArgumentCaptor<List<Tool>> offered = ArgumentCaptor.forClass(List.class);
        verify(assistantModelClient, org.mockito.Mockito.atLeastOnce())
                .generateResponse(anyList(), offered.capture(), any(ModelRequestContext.class));
        assertThat(offered.getValue()).extracting(Tool::name).contains("stage_order_draft", "discard_order_draft");

        // A draft awaits confirmation; nothing was ordered
        OrderDraft draft = draftRepository.findOpenDraft(sessionId).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(DraftStatus.WAITING_CONFIRMATION);
        assertThat(draft.getCustomerId()).isEqualTo(7L);
        assertThat(draft.getTotalAmount()).isEqualByComparingTo(new BigDecimal("49.80"));
        verify(polarisMcpClient, never()).callTool(eq("place_order"), anyMap());

        // The card is persisted on the reply message
        List<AssistantMessage> history = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        AssistantMessage reply = history.getLast();
        assertThat(reply.getWidgetType()).isEqualTo("ORDER_DRAFT");
        assertThat(reply.getWidgetPayload()).contains(draft.getId()).contains("\"total\":49.80");
    }

    private org.springframework.test.web.servlet.ResultActions chatAsShopper(String sessionId, String message) throws Exception {
        return mockMvc.perform(post("/api/v1/assistant/chat")
                .with(jwt().jwt(j -> j.subject("alice"))
                        .authorities(new SimpleGrantedAuthority("PERM_order.write"),
                                new SimpleGrantedAuthority("PERM_catalog.read")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}"));
    }

    @Test
    @DisplayName("Given stage then stage again in one model batch, then they run in order and the one card returned is the draft that is open")
    void stage_twice_in_one_turn_returns_card_of_open_draft() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        ToolCall restage = new ToolCall("stage_order_draft",
                Map.of("items", List.of(Map.of("sku", "NG-CHARGER-01", "quantity", 3))));
        when(assistantModelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(new ModelResponse("", List.of(stageCall, restage)))
                .thenReturn(new ModelResponse("Updated your draft.", List.of()));

        String body = chatAsShopper(sessionId, "actually make it 3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgets.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        OrderDraft open = draftRepository.findOpenDraft(sessionId).orElseThrow();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(body, "$.widgets[0].payload.draftId")).isEqualTo(open.getId());
        assertThat(draftRepository.findAll()).filteredOn(d -> d.belongsTo(sessionId)).hasSize(2)
                .filteredOn(d -> d.getStatus() == DraftStatus.CANCELLED).hasSize(1);
    }

    @Test
    @DisplayName("Given stage then discard in one model batch, then no card is returned, no draft stays open and no order is placed")
    void stage_then_discard_in_one_turn_returns_no_card() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        when(assistantModelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(new ModelResponse("", List.of(stageCall, new ToolCall("discard_order_draft", Map.of()))))
                .thenReturn(new ModelResponse("Okay, I dropped it.", List.of()));

        chatAsShopper(sessionId, "stage it and then drop it")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widgets.length()").value(0));

        assertThat(draftRepository.findOpenDraft(sessionId)).isEmpty();
        assertThat(draftRepository.findAll()).filteredOn(d -> d.belongsTo(sessionId))
                .singleElement().extracting(OrderDraft::getStatus).isEqualTo(DraftStatus.CANCELLED);
        verify(polarisMcpClient, never()).callTool(eq("place_order"), anyMap());
    }

    @Test
    @DisplayName("Given an anonymous caller, when the model tries to stage, then the action is denied and nothing is quoted or staged")
    void anonymous_caller_cannot_stage() throws Exception {
        String sessionId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"order 2 of NG-CHARGER-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.startsWith("Action denied")))
                .andExpect(jsonPath("$.widgets.length()").value(0));

        assertThat(draftRepository.findOpenDraft(sessionId)).isEmpty();
        verify(polarisMcpClient, never()).callTool(anyString(), anyMap());
    }
}
