package vn.danang.polaris.assistant.actuator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.danang.polaris.assistant.PolarisAssistantApp;
import vn.danang.polaris.assistant.TestcontainersConfiguration;
import vn.danang.polaris.assistant.ai.AssistantModelClient;
import vn.danang.polaris.assistant.tools.PolarisMcpClient;
import vn.danang.polaris.web.support.JwtMockFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = PolarisAssistantApp.class)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Polaris Assistant - Actuator Health & Probes Integration Tests")
class AssistantActuatorHealthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantModelClient assistantModelClient;

    @MockitoBean
    private PolarisMcpClient polarisMcpClient;

    // =========================================================================
    // 1. Happy path — Unauthenticated Probe & Health Ingress
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("GET /actuator/health/liveness without authentication returns 200 OK with UP status")
        void livenessProbe_unauthenticated_returnsStatusUpAnd200() throws Exception {
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("GET /actuator/health/readiness without authentication returns 200 OK with UP status")
        void readinessProbe_unauthenticated_returnsStatusUpAnd200() throws Exception {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("GET /actuator/health without authentication returns 200 OK with aggregate UP status")
        void aggregateHealth_unauthenticated_returnsStatusUpAnd200() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    // =========================================================================
    // 2. Invalid input & Unsupported Methods
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("POST /actuator/health/liveness is rejected with 405 Method Not Allowed")
        void livenessProbe_postMutation_returnsMethodNotAllowed() throws Exception {
            mockMvc.perform(post("/actuator/health/liveness")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("POST /actuator/health/readiness is rejected with 405 Method Not Allowed")
        void readinessProbe_postMutation_returnsMethodNotAllowed() throws Exception {
            mockMvc.perform(post("/actuator/health/readiness")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    // =========================================================================
    // 3. Edge cases — Authenticated access
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("GET /actuator/health/liveness with valid JWT bearer token also returns 200 OK UP")
        void livenessProbe_authenticated_returnsStatusUpAnd200() throws Exception {
            mockMvc.perform(get("/actuator/health/liveness")
                            .with(JwtMockFactory.customerSuccess()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("GET /actuator/health/readiness with valid JWT bearer token also returns 200 OK UP")
        void readinessProbe_authenticated_returnsStatusUpAnd200() throws Exception {
            mockMvc.perform(get("/actuator/health/readiness")
                            .with(JwtMockFactory.customerSuccess()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }
    }
}
