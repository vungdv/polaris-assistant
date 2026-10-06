package vn.danang.polaris.assistant.ai;

/**
 * Token counts a model provider reported for one call.
 *
 * @param input prompt tokens, including any served from cache
 * @param output generated tokens, excluding thinking
 * @param reasoning thinking tokens
 * @param cachedInput prompt tokens served from cache (already counted in {@code input})
 */
public record ModelTokenUsage(long input, long output, long reasoning, long cachedInput) {

    public static final ModelTokenUsage NONE = new ModelTokenUsage(0, 0, 0, 0);
}
