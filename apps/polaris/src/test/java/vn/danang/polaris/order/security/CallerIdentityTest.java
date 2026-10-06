package vn.danang.polaris.order.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@DisplayName("CallerIdentity Unit Tests")
class CallerIdentityTest {

    private static Jwt jwt(Object emailVerified) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("sub-1")
                .claim("email", "alice.tran@example.com")
                .claim("email_verified", emailVerified)
                .build();
    }

    @Test
    @DisplayName("shopper JWT maps subject, verified email and no staff flag")
    void from_shopperJwt() {
        CallerIdentity caller = CallerIdentity.from(
                new JwtAuthenticationToken(jwt(true), AuthorityUtils.createAuthorityList("ROLE_shopper", "PERM_order.write")));

        assertThat(caller).isEqualTo(new CallerIdentity("sub-1", "alice.tran@example.com", true, false));
    }

    @Test
    @DisplayName("email_verified=false is carried through")
    void from_unverifiedEmail() {
        CallerIdentity caller = CallerIdentity.from(new JwtAuthenticationToken(jwt(false), AuthorityUtils.NO_AUTHORITIES));

        assertThat(caller.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("back-office roles are staff")
    void from_staffRoles() {
        for (String role : new String[] {"ROLE_STAFF", "ROLE_ADMIN", "ROLE_PURCHASE_MANAGEMENT"}) {
            CallerIdentity caller = CallerIdentity.from(
                    new JwtAuthenticationToken(jwt(true), AuthorityUtils.createAuthorityList(role)));
            assertThat(caller.staff()).as(role).isTrue();
        }
    }

    @Test
    @DisplayName("order.write permission and non-ordering back-office roles do not make a caller staff")
    void from_permissionIsNotStaff() {
        CallerIdentity caller = CallerIdentity.from(
                new TestingAuthenticationToken("user", null, "PERM_order.write", "ROLE_INVENTORY", "ROLE_CUSTOMER_SUCCESS"));

        assertThat(caller.staff()).isFalse();
        assertThat(caller.subject()).isEqualTo("user");
        assertThat(caller.email()).isNull();
    }

    @Test
    @DisplayName("missing or anonymous authentication yields no identity")
    void from_unauthenticated() {
        assertThat(CallerIdentity.from(null)).isNull();
        assertThat(CallerIdentity.from(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isNull();
    }
}
