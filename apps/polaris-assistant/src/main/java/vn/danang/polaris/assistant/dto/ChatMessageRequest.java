package vn.danang.polaris.assistant.dto;

import java.util.UUID;
import java.util.regex.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload to send a chat message to the assistant")
public record ChatMessageRequest(

        @Schema(
                description = "Optional session identifier. If omitted, a session is automatically generated.",
                example = "123e4567-e89b-12d3-a456-426614174000",
                maxLength = MAX_SESSION_ID_LENGTH
        )
        String sessionId,

        @NotBlank(message = "Message content must not be blank.")
        @Schema(
                description = "User natural language message",
                example = "Update status of the order ORD-1001?"
        )
        String message

) {

    // Strips invisible/format & control Unicode characters (e.g. zero-width space U+200B)
    // that could otherwise slip past isBlank() checks while looking empty.
    private static final Pattern INVISIBLE_OR_CONTROL = Pattern.compile("[\\p{Cf}\\p{Cc}]");

    // Matches assistant_sessions.id.
    public static final int MAX_SESSION_ID_LENGTH = 64;

    public ChatMessageRequest(String message) {
        this(null, message);
    }

    public ChatMessageRequest {
        if (message == null) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }

        message = INVISIBLE_OR_CONTROL.matcher(message).replaceAll("").trim();

        if (message.isEmpty()) {
            throw new IllegalArgumentException("Message content must not be blank.");
        }

        if (sessionId != null && sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("Session id must be at most " + MAX_SESSION_ID_LENGTH + " characters.");
        }

        sessionId = (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString()
                : sessionId;
    }

    public static  ChatMessageRequest of(String message){
        return new ChatMessageRequest("", message);
    }

    public static  ChatMessageRequest of(String sessionId, String message){
        return new ChatMessageRequest(sessionId, message);
    }
}