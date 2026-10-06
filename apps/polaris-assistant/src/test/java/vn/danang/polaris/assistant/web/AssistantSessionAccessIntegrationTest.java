package vn.danang.polaris.assistant.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.ai.ModelRequestContext;
import vn.danang.polaris.assistant.ai.ModelResponse;
import vn.danang.polaris.assistant.intent.ResolvedIntent;
import vn.danang.polaris.assistant.service.IntentResolutionFacade;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;

/**
 * End-to-end through HTTP, security and PostgreSQL: a persisted session can be continued by its
 * owner and is refused with a 403 problem for any other caller (IDOR).
 */
@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssistantSessionAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    @MockitoBean
    private IntentResolutionFacade intentResolutionFacade;

    @BeforeEach
    void setUp() {
        when(intentResolutionFacade.resolve(anyString(), anyList()))
                .thenReturn(new ResolvedIntent("general.conversation", 1.0, true, List.of()));
        when(assistantModelClient.generateResponse(anyList(), anyList(), any(ModelRequestContext.class)))
                .thenReturn(new ModelResponse("Hello!", List.of()));
    }

    private static MockHttpServletRequestBuilder chatAs(String subject, String sessionId, String message) {
        return post("/api/v1/assistant/chat")
                .with(jwt().jwt(j -> j.subject(subject)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + sessionId + "\",\"message\":\"" + message + "\"}");
    }

    @Test
    @DisplayName("Given alice's session, when alice continues it, then 200 OK")
    void owner_can_continue_session() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        mockMvc.perform(chatAs("alice", sessionId, "Hi")).andExpect(status().isOk());

        mockMvc.perform(chatAs("alice", sessionId, "Again"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));
    }

    @Test
    @DisplayName("Given alice's session, when mallory posts to it, then 403 Forbidden problem")
    void other_user_gets_403_problem() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        mockMvc.perform(chatAs("alice", sessionId, "Hi")).andExpect(status().isOk());

        mockMvc.perform(chatAs("mallory", sessionId, "Show me alice's chat"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/forbidden"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Given a session id longer than 64 characters, when posted, then 400 validation problem")
    void rejects_oversized_session_id() throws Exception {
        mockMvc.perform(chatAs("alice", "s".repeat(65), "Hi"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Session id must be at most 64 characters."));
    }
}
