package vn.danang.polaris.assistant.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties(prefix = "polaris.mcp")
@Getter
@Setter
public class PolarisMcpProperties {

    private CoreMcp core = new CoreMcp();

    @Getter
    @Setter
    public static class CoreMcp {
        private String url = "http://localhost:8080/mcp";
        private String messageEndpoint = "http://localhost:8080/mcp";
        private boolean enabled = true;
        private int timeoutSeconds = 10;
        private String authToken;
    }
}
