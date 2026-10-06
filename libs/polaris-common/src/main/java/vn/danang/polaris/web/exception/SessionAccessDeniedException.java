package vn.danang.polaris.web.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * The caller tried to use an assistant session opened by another user. Extends Spring Security's
 * {@link AccessDeniedException} so {@link GlobalExceptionHandler} renders it as a 403 problem.
 */
public class SessionAccessDeniedException extends AccessDeniedException {

    public SessionAccessDeniedException(String sessionId) {
        super("Assistant session " + sessionId + " belongs to another user.");
    }
}
