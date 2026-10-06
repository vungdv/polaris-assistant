package vn.danang.polaris.order.security;

import java.util.Set;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import jakarta.annotation.Nullable;
import vn.danang.polaris.config.PolarisRoles;

/**
 * Server-side view of the authenticated caller, used to bind orders to the shopper's own customer
 * account (PRD-003 FR-10, anti-IDOR). Built only from the verified bearer token, never from request arguments.
 *
 * @param subject       JWT {@code sub} claim (identity-provider user ID)
 * @param email         JWT {@code email} claim, may be null
 * @param emailVerified JWT {@code email_verified} claim; the email fallback is only used when true
 * @param staff         true if the caller holds a back-office role and may act for any customer
 */
public record CallerIdentity(String subject, String email, boolean emailVerified, boolean staff) {

    /**
     * Authorities that allow acting on behalf of any customer. The realm has no single "staff" role, so the
     * back-office role that places and manages orders (purchase-management) counts as staff alongside ROLE_STAFF.
     */
    static final Set<String> STAFF_AUTHORITIES = Set.of(
            roleAuthority(PolarisRoles.STAFF),
            roleAuthority(PolarisRoles.ADMIN),
            roleAuthority(PolarisRoles.PURCHASE_MANAGEMENT));

    /**
     * Derives the caller identity from a Spring Security authentication.
     *
     * @return the identity, or null if the caller is not authenticated
     */
    @Nullable
    public static CallerIdentity from(@Nullable Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        boolean staff = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(STAFF_AUTHORITIES::contains);

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return new CallerIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
                    staff);
        }
        return new CallerIdentity(authentication.getName(), null, false, staff);
    }

    private static String roleAuthority(String role) {
        return "ROLE_" + role.toUpperCase().replace('-', '_');
    }
}
