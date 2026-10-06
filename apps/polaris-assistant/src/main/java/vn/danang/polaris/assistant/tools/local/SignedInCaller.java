package vn.danang.polaris.assistant.tools.local;

import java.util.Optional;

import vn.danang.polaris.assistant.security.UserContext;
import vn.danang.polaris.assistant.tools.ToolExecutionContext;

/**
 * The signed-in caller of a draft tool. The chat endpoint also admits anonymous callers, who all share the
 * user id {@code "anonymous"}; they must never own or act on a draft.
 */
record SignedInCaller(String userId, String bearerToken) {

    static final String ANONYMOUS = "anonymous";

    static Optional<SignedInCaller> resolve(ToolExecutionContext context, UserContext userContext) {
        String userId = context != null ? context.userId() : null;
        if (userId == null || userId.isBlank() || ANONYMOUS.equals(userId)) {
            return Optional.empty();
        }
        return userContext.resolveCallerBearerToken().map(token -> new SignedInCaller(userId, token));
    }
}
