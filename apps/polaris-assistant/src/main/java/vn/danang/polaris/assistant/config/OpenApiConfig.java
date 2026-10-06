package vn.danang.polaris.assistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:https://id.polaris.local/realms/polaris}")
    private String issuerUri;

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "keycloak-auth2-codeflow";

        return new OpenAPI()
            .info(new Info()
                .title("Polaris AI Assistant API")
                .version("1.0")
                .description("Autonomous AI Assistant microservice communicating via Model Context Protocol (MCP).")
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
