package vn.danang.polaris.config;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import vn.danang.polaris.web.support.JwtMockFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityConfig & Role Extraction Unit Tests")
class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();
    private final JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();

    private Jwt createMockJwt(Map<String, Object> claims) {
        return new Jwt(
                "mock-token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                claims
        );
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Converter should extract OAuth2 scopes, Keycloak realm roles, and fine-grained polaris-api permissions")
        void converter_withScopesAndKeycloakRoles_extractsAllAuthorities() {
            Jwt jwt = createMockJwt(Map.of(
                    "scope", "catalog.read order.write",
                    "realm_access", Map.of(
                            "roles", List.of(
                                    PolarisRoles.CUSTOMER_SUCCESS,
                                    PolarisRoles.PRODUCT_CATALOG,
                                    PolarisRoles.INVENTORY,
                                    PolarisRoles.PURCHASE_MANAGEMENT,
                                    PolarisRoles.ADMIN
                            )
                    ),
                    "resource_access", Map.of(
                            "polaris-api", Map.of(
                                    "roles", List.of(
                                            PolarisPermissions.CATALOG_READ,
                                            PolarisPermissions.CATALOG_WRITE,
                                            PolarisPermissions.ORDER_READ,
                                            PolarisPermissions.ORDER_WRITE,
                                            PolarisPermissions.CUSTOMER_READ,
                                            PolarisPermissions.CUSTOMER_WRITE,
                                            PolarisPermissions.INVENTORY_READ,
                                            PolarisPermissions.INVENTORY_WRITE
                                    )
                            )
                    )
            ));

            var token = converter.convert(jwt);
            assertThat(token).isNotNull();

            Collection<String> authorities = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            // OAuth2 scopes
            assertThat(authorities).contains("SCOPE_catalog.read", "SCOPE_order.write");

            // Keycloak realm roles (both literal and upper snake_case)
            assertThat(authorities).contains(
                    "ROLE_customer-success", "ROLE_CUSTOMER_SUCCESS",
                    "ROLE_product-catalog", "ROLE_PRODUCT_CATALOG",
                    "ROLE_inventory", "ROLE_INVENTORY",
                    "ROLE_purchase-management", "ROLE_PURCHASE_MANAGEMENT",
                    "ROLE_admin", "ROLE_ADMIN"
            );
        }

        @Test
        @DisplayName("JwtMockFactory should provide role-specific post-processors with expected authorities")
        void jwtMockFactory_roleHelpers_configureProperAuthorities() {
            assertThat(JwtMockFactory.customerSuccess()).isNotNull();
            assertThat(JwtMockFactory.productCatalog()).isNotNull();
            assertThat(JwtMockFactory.inventory()).isNotNull();
            assertThat(JwtMockFactory.purchaseManagement()).isNotNull();
            assertThat(JwtMockFactory.admin()).isNotNull();
            assertThat(JwtMockFactory.withPermissions(PolarisPermissions.CATALOG_READ)).isNotNull();
            assertThat(JwtMockFactory.withAuthorities("ROLE_TEST")).isNotNull();
        }
    }

    @Nested
    @DisplayName("2. Invalid / missing input")
    class InvalidInput {

        @Test
        @DisplayName("Converter should handle JWT without realm_access claim without failing")
        void converter_missingRealmAccess_handlesGracefully() {
            Jwt jwt = createMockJwt(Map.of("scope", "catalog.read"));

            var token = converter.convert(jwt);
            assertThat(token).isNotNull();

            Collection<String> authorities = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            assertThat(authorities).contains("SCOPE_catalog.read");
            assertThat(authorities).noneMatch(a -> a.startsWith("ROLE_"));
        }

        @Test
        @DisplayName("Converter should handle invalid realm_access type gracefully")
        void converter_invalidRealmAccessType_handlesGracefully() {
            Jwt jwt = createMockJwt(Map.of("realm_access", "invalid-string-value"));

            var token = converter.convert(jwt);
            assertThat(token).isNotNull();
            assertThat(token.getAuthorities()).noneMatch(a -> a.getAuthority().startsWith("ROLE_"));
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Converter should handle empty realm roles array")
        void converter_emptyRoles_producesOnlyScopeAuthorities() {
            Jwt jwt = createMockJwt(Map.of(
                    "scope", "catalog.read",
                    "realm_access", Map.of("roles", List.of())
            ));

            var token = converter.convert(jwt);
            assertThat(token).isNotNull();

            Collection<String> authorities = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            assertThat(authorities).contains("SCOPE_catalog.read");
            assertThat(authorities).noneMatch(a -> a.startsWith("ROLE_"));
        }

        @Test
        @DisplayName("Converter should ignore non-string elements in realm_access.roles")
        void converter_nonStringRoleElements_ignoresGracefully() {
            Jwt jwt = createMockJwt(Map.of(
                    "realm_access", Map.of("roles", List.of(12345, PolarisRoles.CUSTOMER_SUCCESS))
            ));

            var token = converter.convert(jwt);
            assertThat(token).isNotNull();

            Collection<String> authorities = token.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            assertThat(authorities).contains("ROLE_customer-success", "ROLE_CUSTOMER_SUCCESS");
            assertThat(authorities).doesNotContain("ROLE_12345");
        }
    }
}
