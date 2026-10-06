package vn.danang.polaris.assistant.security;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import vn.danang.polaris.assistant.tools.PolarisMcpProperties;
import vn.danang.polaris.config.PolarisRoles;

/**
 * Accessor for user authentication state and security tokens in the current execution context.
 * Decouples Spring Security context inspection from downstream clients and transports.
 */
@Component
public class UserContext {
    private static final String PERMISSION_AUTHORITY_PREFIX = "PERM_";
    private static final Set<String> STAFF_AUTHORITIES = Set.of(
            roleAuthority(PolarisRoles.STAFF),
            roleAuthority(PolarisRoles.ADMIN),
            roleAuthority(PolarisRoles.PURCHASE_MANAGEMENT));

    private final PolarisMcpProperties properties;

    @Autowired
    public UserContext(PolarisMcpProperties properties) {
        this.properties = properties;
    }

    public UserContext() {
        this(null);
    }

    /**
     * Resolves the current Bearer token from Spring Security's {@link SecurityContextHolder}.
     * Checks for an authenticated user's JWT/OAuth2 token, then falls back to any configured
     * static service auth token in application properties.
     *
     * @return the resolved Bearer token string, or null if unauthenticated and no fallback exists
     */
    public String resolveBearerToken() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null) {
            Authentication auth = context.getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                return jwtAuth.getToken().getTokenValue();
            } else if (auth.getPrincipal() instanceof Jwt jwt) {
                return jwt.getTokenValue();
            } else if (auth.getCredentials() instanceof AbstractOAuth2Token oauth2Token) {
                return oauth2Token.getTokenValue();
            } else if (auth.getCredentials() instanceof String cred && !cred.isBlank()) {
                return cred;
            }
        }

        if (properties != null && properties.getCore() != null) {
            String fallbackToken = properties.getCore().getAuthToken();
            if (fallbackToken != null && !fallbackToken.isBlank()) {
                return fallbackToken.trim();
            }
        }

        return null;
    }

    /**
     * Resolves the bearer token of the <em>caller</em> only: unlike {@link #resolveBearerToken()} this never
     * falls back to the configured service token, so it is empty for anonymous callers. Use it wherever
     * Polaris Core must answer for the caller's own identity (e.g. {@code GET /api/v1/customers/me}).
     *
     * @return the caller's own OAuth2 access token, or empty if the caller is not a signed-in user
     */
    public Optional<String> resolveCallerBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        String token = null;
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            token = jwtAuth.getToken().getTokenValue();
        } else if (auth.getPrincipal() instanceof Jwt jwt) {
            token = jwt.getTokenValue();
        } else if (auth.getCredentials() instanceof AbstractOAuth2Token oauth2Token) {
            token = oauth2Token.getTokenValue();
        }
        return token != null && !token.isBlank() ? Optional.of(token) : Optional.empty();
    }

    /**
     * Indicates whether the caller holds a back-office role that may act on behalf of any customer.
     * Mirrors Order Management's staff set ({@code ROLE_STAFF}, {@code ROLE_ADMIN},
     * {@code ROLE_PURCHASE_MANAGEMENT}); Order Management re-checks this on every write.
     */
    public boolean isStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_AUTHORITIES::contains);
    }

    /**
     * Retrieves the identifier of the currently authenticated user, or empty if unauthenticated.
     *
     * @return optional containing the user identifier (JWT subject or principal name)
     */
    public Optional<String> getCurrentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                return Optional.ofNullable(jwtAuth.getToken().getSubject());
            } else if (auth.getPrincipal() instanceof Jwt jwt) {
                return Optional.ofNullable(jwt.getSubject());
            } else if (auth.getName() != null && !auth.getName().isBlank()) {
                return Optional.of(auth.getName());
            }
        }
        return Optional.empty();
    }

    /**
     * Indicates whether there is an active authentication in the current {@link SecurityContext}.
     *
     * @return true if an {@link Authentication} is present, false otherwise (e.g. unsecured execution)
     */
    public boolean isAuthenticated() {
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    /**
     * Checks whether the current caller holds the given permission.
     * Permissions are resolved from {@code PERM_}-prefixed granted authorities (e.g.
     * {@code PERM_order.read} grants permission {@code order.read}).
     *
     * @param permission the permission to check (without the {@code PERM_} prefix), or null/blank if none required
     * @return true if no permission is required or the caller holds it, false otherwise
     */
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return getPermissions().contains(permission);
    }

    /**
     * Resolves the set of permissions held by the caller in the current execution context.
     * Permissions are already mapped onto {@code PERM_}-prefixed {@link GrantedAuthority}s by
     * {@code SecurityConfig}'s {@code JwtAuthenticationConverter}, so this only needs to read
     * {@link Authentication#getAuthorities()}.
     *
     * @return the caller's permissions (with the {@code PERM_} authority prefix stripped)
     */
    public Set<String> getPermissions() {
        Set<String> permissions = new HashSet<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return permissions;
        }

        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (authority.getAuthority() == null) {
                continue;
            }
            String value = authority.getAuthority();
            if (value.startsWith(PERMISSION_AUTHORITY_PREFIX)) {
                permissions.add(value.substring(PERMISSION_AUTHORITY_PREFIX.length()));
            }
        }

        return permissions;
    }

    private static String roleAuthority(String role) {
        return "ROLE_" + role.toUpperCase().replace('-', '_');
    }
}
