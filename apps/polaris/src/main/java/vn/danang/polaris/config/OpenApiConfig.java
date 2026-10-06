package vn.danang.polaris.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
@EnableWebSecurity
public class OpenApiConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:http://localhost:8081/realms/your-realm}")
    private String issuerUri;

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "keycloak-auth2-codeflow";

        return new OpenAPI()
            .info(new Info()
                .title("Customer Support Demo")
                .version("1.0")
                .description("REST API for customer support")
            )
            .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
            .components(new Components()
                .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                    .type(SecurityScheme.Type.OAUTH2)
                    .flows(new OAuthFlows()
                        .authorizationCode(new OAuthFlow()
                            .authorizationUrl(issuerUri + "/protocol/openid-connect/auth")
                            .tokenUrl(issuerUri + "/protocol/openid-connect/token")
                            .scopes(new Scopes()
                                .addString("openid", "OpenID Connect")
                                .addString("profile", "Profile info")
                            )
                        )
                    )
                )
            );
    }
}