package vn.danang.polaris.assistant.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Nullable;

/**
 * Immutable context describing the agent turn and intent state surrounding a model inference call: the 1-based
 * ReAct loop iteration, the resolved intent and its confidence, the number of tools offered, the chat session
 * ({@code gen_ai.conversation.id}, may be null) and, optionally, the turn's deadline.
 */
public final class ModelRequestContext {

    private final int iteration;
    private final String intentId;
    private final double intentConfidence;
    private final int toolsOfferedCount;
    @Nullable
    private final String conversationId;
    @Nullable
    private final Instant deadline;

    /** A context whose calls must not wait past {@code deadline}, when the turn's time budget runs out. */
    public ModelRequestContext(int iteration, String intentId, double intentConfidence, int toolsOfferedCount,
            @Nullable String conversationId, Instant deadline) {
        this.iteration = iteration;
        this.intentId = intentId;
        this.intentConfidence = intentConfidence;
        this.toolsOfferedCount = toolsOfferedCount;
        this.conversationId = conversationId;
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /** A context without a turn deadline. */
    public ModelRequestContext(int iteration, String intentId, double intentConfidence, int toolsOfferedCount,
            @Nullable String conversationId) {
        this.iteration = iteration;
        this.intentId = intentId;
        this.intentConfidence = intentConfidence;
        this.toolsOfferedCount = toolsOfferedCount;
        this.conversationId = conversationId;
        this.deadline = null;
    }

    public ModelRequestContext(int iteration, String intentId, double intentConfidence, int toolsOfferedCount) {
        this(iteration, intentId, intentConfidence, toolsOfferedCount, null);
    }

    public static ModelRequestContext empty() {
        return new ModelRequestContext(1, "unknown", 1.0, 0);
    }

    public int iteration() {
        return iteration;
    }

    public String intentId() {
        return intentId;
    }

    public double intentConfidence() {
        return intentConfidence;
    }

    public int toolsOfferedCount() {
        return toolsOfferedCount;
    }

    @Nullable
    public String conversationId() {
        return conversationId;
    }

    /** When the turn's time budget runs out; the call must not wait past it. Empty means no turn deadline. */
    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelRequestContext that
                && iteration == that.iteration
                && Double.compare(intentConfidence, that.intentConfidence) == 0
                && toolsOfferedCount == that.toolsOfferedCount
                && Objects.equals(intentId, that.intentId)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(deadline, that.deadline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(iteration, intentId, intentConfidence, toolsOfferedCount, conversationId, deadline);
    }

    @Override
    public String toString() {
        return "ModelRequestContext[iteration=" + iteration + ", intentId=" + intentId + ", intentConfidence="
                + intentConfidence + ", toolsOfferedCount=" + toolsOfferedCount + ", conversationId="
                + conversationId + ", deadline=" + deadline + "]";
    }
}
