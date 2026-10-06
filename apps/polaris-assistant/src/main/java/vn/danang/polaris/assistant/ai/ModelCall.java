package vn.danang.polaris.assistant.ai;

import jakarta.annotation.Nullable;

/**
 * What happened on the wire for one model provider call, carried on the call's result so callers (and
 * observability) can see it without the provider client reporting it anywhere itself.
 *
 * @param requestModel the model the request asked for
 * @param responseModel the model version the provider says answered, if reported
 * @param responseId the provider's response id, if reported
 * @param usage token counts reported by the provider
 * @param finishReason why the model stopped ({@code stop}, {@code tool_calls}), null when the call failed
 * @param failure why the call failed (transport error, non-2xx {@link ModelProviderException}), null on success
 */
public record ModelCall(
        String requestModel,
        @Nullable String responseModel,
        @Nullable String responseId,
        ModelTokenUsage usage,
        @Nullable String finishReason,
        @Nullable Throwable failure
) {
    public static ModelCall succeeded(String requestModel, @Nullable String responseModel, @Nullable String responseId,
            ModelTokenUsage usage, String finishReason) {
        return new ModelCall(requestModel, responseModel, responseId, usage, finishReason, null);
    }

    public static ModelCall failed(String requestModel, Throwable failure) {
        return new ModelCall(requestModel, null, null, ModelTokenUsage.NONE, null, failure);
    }

    public boolean isFailed() {
        return failure != null;
    }
}
