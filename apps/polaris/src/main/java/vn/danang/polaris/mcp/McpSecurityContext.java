package vn.danang.polaris.mcp;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Carries the authenticated caller from the servlet request into MCP tool handlers.
 * <p>
 * The MCP SDK may run tool handlers off the request thread, where Spring's thread-bound
 * {@link SecurityContextHolder} is empty. The transport calls {@link #extractor()} on the request thread
 * (after the security filter chain has validated the bearer token) and the handler reads the caller back
 * from the {@link McpTransportContext} with {@link #authentication(McpTransportContext)}.
 */
public final class McpSecurityContext {

    static final String AUTHENTICATION_KEY = "polaris.authentication";

    private McpSecurityContext() {
    }

    /**
     * Transport context extractor that captures the current request's {@link Authentication}.
     */
    public static McpTransportContextExtractor<HttpServletRequest> extractor() {
        return request -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null
                    ? McpTransportContext.create(Map.of(AUTHENTICATION_KEY, authentication))
                    : McpTransportContext.EMPTY;
        };
    }

    /**
     * Returns the caller captured for this MCP request, or null if none was captured.
     */
    @Nullable
    public static Authentication authentication(@Nullable McpTransportContext context) {
        if (context == null) {
            return null;
        }
        return context.get(AUTHENTICATION_KEY) instanceof Authentication authentication ? authentication : null;
    }
}
