package vn.danang.polaris.fulfilment;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

/**
 * Order REST client and its service-account token (F2 design §5): one {@code client_credentials} registration
 * shared by all partners. The authorized-client manager caches the token and requests a new one shortly before it
 * expires; the interceptor puts it on every claim call. The token endpoint is configured directly (no discovery),
 * so the app starts without Keycloak.
 */
@Configuration(proxyBeanMethods = false)
class OrderApiConfiguration {

    static final String REGISTRATION_ID = "polaris-fulfilment-emulator";

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(FulfilmentProperties properties) {
        FulfilmentProperties.Auth auth = properties.auth();
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(auth.clientId())
                .clientSecret(auth.clientSecret())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(auth.tokenUri())
                .build();
        var repository = new InMemoryClientRegistrationRepository(registration);
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(repository,
                new InMemoryOAuth2AuthorizedClientService(repository));
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        // Partners claim concurrently: serialize so a cold or expired token is fetched once, not once per partner.
        Object lock = new Object();
        return request -> {
            synchronized (lock) {
                return manager.authorize(request);
            }
        };
    }

    @Bean
    ClaimClient claimClient(FulfilmentProperties properties, OAuth2AuthorizedClientManager manager,
            RestClient.Builder restClientBuilder) {
        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        interceptor.setPrincipalResolver(request -> new AnonymousAuthenticationToken(REGISTRATION_ID, REGISTRATION_ID,
                List.copyOf(AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))));
        RestClient restClient = restClientBuilder
                .baseUrl(properties.orderApi().baseUrl())
                .requestInterceptor(interceptor)
                .build();
        return new RestClaimClient(restClient);
    }
}
