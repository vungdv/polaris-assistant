package vn.danang.polaris.assistant.web;

import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.context.annotation.Import;

import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.assistant.ai.AssistantModelClient;

@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AssistantOpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    // =========================================================================
    // 1. Happy path — OpenAPI contract retrieval
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("GET /v3/api-docs returns 200 OK with title and assistant chat endpoint documentation")
        void returns_valid_openapi_specification_with_chat_endpoint() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(containsString("Polaris AI Assistant API")))
                    .andExpect(content().string(containsString("/api/v1/assistant/chat")));
        }
    }

    // =========================================================================
    // 2. Invalid input — unsupported methods & error handling
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("POST /v3/api-docs mutation attempt without CSRF is rejected with 403 Forbidden")
        void rejects_unauthorized_mutation_attempt_on_api_docs() throws Exception {
            mockMvc.perform(post("/v3/api-docs"))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 3. Edge cases & Security/RFC 7807 contracts
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("GET /v3/api-docs declares keycloak security requirement and RFC 7807 problem details media type")
        void declares_oauth2_security_scheme_and_rfc7807_problem_details_contracts() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(containsString("keycloak-auth2-codeflow")))
                    .andExpect(content().string(containsString("application/problem+json")));
        }
    }
}
