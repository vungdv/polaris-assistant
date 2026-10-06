package vn.danang.polaris.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "polaris.typesafe")
@Getter
@Setter
public class AssistantTypeSafeProperties {

    private String apiKey = "";
    private String model = "jev-latest";
    private String baseUrl = "https://api.typesafe.ai";
    private int timeoutSeconds = 3;
}
