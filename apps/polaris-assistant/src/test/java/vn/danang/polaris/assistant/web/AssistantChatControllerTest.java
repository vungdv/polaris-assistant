package vn.danang.polaris.assistant.web;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.assistant.ai.ModelProviderException;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;
import vn.danang.polaris.assistant.dto.ChatMessageRequest;
import vn.danang.polaris.assistant.dto.ChatMessageResponse;
import vn.danang.polaris.assistant.service.AssistantChatService;
import vn.danang.polaris.config.SecurityConfig;
import vn.danang.polaris.web.exception.GlobalExceptionHandler;

@WebMvcTest(AssistantChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AssistantChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantChatService chatService;

    // =========================================================================
    // 1. Happy path — standard conversation and authentication flows
    // =========================================================================
    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Given valid payload, when chat invoked, returns 200 OK with application/json")
        void returns_200_and_json_for_valid_payload() throws Exception {
            Instant now = Instant.parse("2026-09-20T10:00:00Z");
            ChatMessageResponse mockResponse = new ChatMessageResponse(
                    "session-123",
                    "ASSISTANT",
                    "Polaris is an ecommerce platform.",
                    now
            );

            when(chatService.sendMessage(any(ChatMessageRequest.class), eq("anonymous")))
                    .thenReturn(mockResponse);

            String json = """
                    {
                        "sessionId": "session-123",
                        "message": "What is Polaris?"
                    }
                    """;

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.sessionId").value("session-123"))
                    .andExpect(jsonPath("$.role").value("ASSISTANT"))
                    .andExpect(jsonPath("$.reply").value("Polaris is an ecommerce platform."))
                    .andExpect(jsonPath("$.createdAt").value("2026-09-20T10:00:00Z"));
        }

        @Test
        @DisplayName("Given payload without sessionId, when chat invoked, generates session and returns 200 OK")
        void generates_session_and_returns_200_when_session_id_omitted() throws Exception {
            ChatMessageResponse mockResponse = new ChatMessageResponse(
                    "auto-generated-session",
                    "ASSISTANT",
                    "Hello there!",
                    Instant.now()
            );

            when(chatService.sendMessage(any(ChatMessageRequest.class), eq("anonymous")))
                    .thenReturn(mockResponse);

            String json = """
                    {
                        "message": "Hello!"
                    }
                    """;

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.sessionId").value("auto-generated-session"))
                    .andExpect(jsonPath("$.role").value("ASSISTANT"))
                    .andExpect(jsonPath("$.reply").value("Hello there!"));
        }

        @Test
        @DisplayName("Given authenticated JWT, when chat invoked, forwards username to chatService")
        void forwards_authenticated_user_id_from_jwt() throws Exception {
            ChatMessageResponse mockResponse = new ChatMessageResponse(
                    "session-auth",
                    "ASSISTANT",
                    "Welcome back Alice!",
                    Instant.now()
            );

            when(chatService.sendMessage(any(ChatMessageRequest.class), eq("user-alice")))
                    .thenReturn(mockResponse);

            String json = """
                    {
                        "sessionId": "session-auth",
                        "message": "Show my past orders"
                    }
                    """;

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .with(SecurityMockMvcRequestPostProcessors.jwt()
                                    .jwt(jwt -> jwt.subject("user-alice")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value("session-auth"))
                    .andExpect(jsonPath("$.reply").value("Welcome back Alice!"));

            verify(chatService).sendMessage(any(ChatMessageRequest.class), eq("user-alice"));
        }

        @Test
        @DisplayName("Given null principal in direct call, when chat invoked, defaults to 'anonymous' user ID")
        void defaults_to_anonymous_user_when_principal_is_null() {
            AssistantChatService mockService = mock(AssistantChatService.class);
            AssistantChatController controller = new AssistantChatController(mockService);

            ChatMessageRequest request = new ChatMessageRequest("Hello AI");
            ChatMessageResponse expectedResponse = new ChatMessageResponse(
                    request.sessionId(),
                    "ASSISTANT",
                    "Hello!",
                    Instant.now()
            );

            when(mockService.sendMessage(eq(request), eq("anonymous"))).thenReturn(expectedResponse);

            ResponseEntity<ChatMessageResponse> response = controller.chat(request, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(expectedResponse);
            verify(mockService).sendMessage(eq(request), eq("anonymous"));
        }
    }

    // =========================================================================
    // 2. Invalid input — validation errors and RFC 7807 problem details
    // =========================================================================
    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @ParameterizedTest(name = "Payload \"{0}\" rejected with 400 Bad Request")
        @ValueSource(strings = {
                "{\"sessionId\": \"session-123\", \"message\": \"\"}",
                "{\"sessionId\": \"session-123\", \"message\": \"     \"}",
                "{\"sessionId\": \"session-123\", \"message\": null}",
                "{\"sessionId\": \"session-123\"}"
        })
        @DisplayName("Given empty, whitespace, null, or missing message, returns 400 ProblemDetail")
        void rejects_blank_or_missing_message_with_400_problem_detail(String jsonPayload) throws Exception {
            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonPayload))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                    .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

            verifyNoInteractions(chatService);
        }

        @ParameterizedTest(name = "Malformed payload \"{0}\" rejected with 400")
        @ValueSource(strings = {
                "",
                "{ \"message\": "
        })
        @DisplayName("Given empty or malformed JSON payload, returns 400 ProblemDetail")
        void rejects_empty_or_malformed_json_body_with_400_problem_detail(String invalidPayload) throws Exception {
            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidPayload))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                    .andExpect(jsonPath("$.title").value("Malformed Request Payload"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").value("Required request body is missing or malformed."))
                    .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("Given null request in direct invocation, throws IllegalArgumentException")
        void rejects_null_request_in_direct_call_with_illegal_argument_exception() {
            AssistantChatService mockService = mock(AssistantChatService.class);
            AssistantChatController controller = new AssistantChatController(mockService);

            Principal principal = () -> "test-user";

            assertThatThrownBy(() -> controller.chat(null, principal))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Message content must not be blank.");

            verifyNoInteractions(mockService);
        }
    }

    // =========================================================================
    // 3. Edge cases — protocol boundaries and exception translations
    // =========================================================================
    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Given message with invisible Unicode characters, returns 400 ProblemDetail")
        void rejects_invisible_unicode_message_with_400_problem_detail() throws Exception {
            String json = """
                    {
                        "sessionId": "session-unicode",
                        "message": "\\u200B\\u200C\\u200D"
                    }
                    """;

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/bad-request"))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").value("Message content must not be blank."))
                    .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("Given unsupported media type, returns 415 ProblemDetail")
        void rejects_unsupported_content_type_with_415_problem_detail() throws Exception {
            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("Hello AI"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/unsupported-media-type"))
                    .andExpect(jsonPath("$.title").value("Unsupported Media Type"))
                    .andExpect(jsonPath("$.status").value(415))
                    .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("Given domain state conflict from service, returns 409 ProblemDetail")
        void handles_domain_state_conflict_with_409_problem_detail() throws Exception {
            when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                    .thenThrow(new IllegalStateException("AI model provider connection refused"));

            String json = """
                    {
                        "sessionId": "session-error",
                        "message": "Find chargers in catalog"
                    }
                    """;

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/conflict"))
                    .andExpect(jsonPath("$.title").value("Order State Conflict"))
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("Given unexpected runtime exception from service, returns 500 ProblemDetail")
        void handles_unexpected_internal_exception_with_500_problem_detail() throws Exception {
            when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                    .thenThrow(new RuntimeException("Unexpected runtime error"));

            String json = """
                    {
                        "sessionId": "session-err",
                        "message": "Tell me a joke"
                    }
                    """;

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/internal-error"))
                    .andExpect(jsonPath("$.title").value("Internal Server Error"))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.detail").value("Internal server error during processing."))
                    .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"));
        }
    }

    // =========================================================================
    // 4. Model unavailable — 503 Problem Details with Retry-After (plan A1)
    // =========================================================================
    @Nested
    @DisplayName("4. Model unavailable")
    class ModelUnavailable {

        private static final String JSON = """
                {
                    "sessionId": "session-down",
                    "message": "Find chargers"
                }
                """;

        @Test
        @DisplayName("Given the model is unavailable with a Retry-After hint, returns 503 Problem Details with Retry-After and no provider error text")
        void returns_503_problem_detail_with_retry_after() throws Exception {
            when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                    .thenThrow(new ModelUnavailableException("Gemini returned HTTP 429: SECRET-PROVIDER-DETAIL",
                            new java.io.IOException("SECRET-CAUSE"), Duration.ofSeconds(30)));

            String body = mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                    .andExpect(jsonPath("$.type").value("https://polaris.local/errors/assistant-unavailable"))
                    .andExpect(jsonPath("$.title").value("Assistant Temporarily Unavailable"))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.detail").value("The assistant is temporarily unavailable. Please try again shortly."))
                    .andExpect(jsonPath("$.instance").value("/api/v1/assistant/chat"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("SECRET", "Gemini", "429");
        }

        @Test
        @DisplayName("Given the model is unavailable without a Retry-After hint, returns 503 Problem Details without Retry-After")
        void returns_503_without_retry_after_when_unknown() throws Exception {
            when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                    .thenThrow(new ModelUnavailableException("Gemini request I/O error", new java.io.IOException("reset")));

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }

        @Test
        @DisplayName("Given a sub-second Retry-After hint, returns Retry-After rounded up to at least 1 second")
        void rounds_sub_second_retry_after_up() throws Exception {
            when(chatService.sendMessage(any(ChatMessageRequest.class), any()))
                    .thenThrow(new ModelUnavailableException("Gemini returned HTTP 429", new ModelProviderException(429), Duration.ofMillis(1500)));

            mockMvc.perform(post("/api/v1/assistant/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"));
        }
    }
}
