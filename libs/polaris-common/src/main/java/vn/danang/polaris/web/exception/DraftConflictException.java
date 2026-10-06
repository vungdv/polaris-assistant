package vn.danang.polaris.web.exception;

/**
 * A draft change kept losing to a concurrent change of the same session's drafts (optimistic-lock or
 * "one open draft per session" conflict), so it was rolled back without effect.
 */
public class DraftConflictException extends RuntimeException {

    public DraftConflictException(String sessionId, Throwable cause) {
        super("The order draft of session " + sessionId + " was changed concurrently.", cause);
    }
}
