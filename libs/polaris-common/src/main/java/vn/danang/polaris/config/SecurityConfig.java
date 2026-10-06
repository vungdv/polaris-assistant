package vn.danang.polaris.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // required for @PreAuthorize on controller methods
public class SecurityConfig {

    private static final String RESOURCE_SERVER_CLIENT_ID = "polaris-api";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // disable csrf for api as it uses jwt, not the cookies.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**", "/api/**", "/mcp", "/mcp/**", "/chat/**", "/actuator/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/h2-console/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/chat",
                                "/chat/**",
                                "/static/**",
                                "/favicon.ico",
                                "/api/v1/assistant/**",
                                "/actuator/**"
                        ).permitAll()
                        // permission enforcement per endpoint now happens via @PreAuthorize("hasAuthority('PERM_...')")
                        // on the controller methods themselves, so we just require authentication here.
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * example of a jwt payload:
     * {
     *   "exp": 1758616700,
     *   "iat": 1758616400,
     *   "jti": "3f1a9c2e-1b7d-4e9a-9c11-8e2f6a0d4b77",
     *   "iss": "https://keycloak.polaris.local/realms/polaris",
     *   "aud": "account",
     *   "sub": "a6e1f9c0-2d3b-4a11-9f8e-7c5d1b3a2e60",
     *   "typ": "Bearer",
     *   "azp": "polaris-app",
     *   "sid": "d92f1e4a-6b3c-4d5e-8f1a-2c9b0e7d4a13",
     *   "acr": "1",
     *   "allowed-origins": ["https://polaris.local"],
     *
     *   "realm_access": {
     *     "roles": [
     *       "offline_access",
     *       "uma_authorization",
     *       "product-catalog",
     *       "product_catalog",
     *       "default-roles-polaris"
     *     ]
     *   },
     *
     *   "resource_access": {
     *     "polaris-api": {
     *       "roles": ["catalog.read", "catalog.write"]
     *     },
     *     "account": {
     *       "roles": ["view-profile", "manage-account"]
     *     }
     *   },
     *
     *   "scope": "openid profile email",
     *   "email_verified": true,
     *   "preferred_username": "productcatalog",
     *   "given_name": "Product",
     *   "family_name": "Catalog",
     *   "email": "productcatalog@novagadgets.local"
     * }
     *   @PreAuthorize("hasAuthority('PERM_customer.read')")
     *   @GetMapping("/api/v1/customers/{id}")
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new HashSet<>();

            // OAuth2 scopes -> SCOPE_<scope>
            Collection<GrantedAuthority> scopeAuthorities = defaultConverter.convert(jwt);
            if (scopeAuthorities != null) {
                authorities.addAll(scopeAuthorities);
            }

            // 1. realm_access.roles -> ROLE_<role> & ROLE_<ROLE> (business/org role)
            Object realmAccessObj = jwt.getClaims().get("realm_access");
            if (realmAccessObj instanceof Map<?, ?> realmAccess) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof Collection<?> roles) {
                    for (Object role : roles) {
                        if (role instanceof String roleStr) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleStr));
                            authorities.add(new SimpleGrantedAuthority(
                                    "ROLE_" + roleStr.toUpperCase().replace('-', '_')));
                        }
                    }
                }
            }

            // 2. resource_access.polaris-api.roles -> PERM_<permission>
            Object resourceAccessObj = jwt.getClaims().get("resource_access");
            if (resourceAccessObj instanceof Map<?, ?> resourceAccess) {
                Object clientEntryObj = resourceAccess.get(RESOURCE_SERVER_CLIENT_ID);
                if (clientEntryObj instanceof Map<?, ?> clientEntry) {
                    Object rolesObj = clientEntry.get("roles");
                    if (rolesObj instanceof Collection<?> roles) {
                        for (Object role : roles) {
                            if (role instanceof String roleStr) {
                                authorities.add(new SimpleGrantedAuthority("PERM_" + roleStr));
                            }
                        }
                    }
                }
            }

            return authorities;
        });
        return converter;
    }
}