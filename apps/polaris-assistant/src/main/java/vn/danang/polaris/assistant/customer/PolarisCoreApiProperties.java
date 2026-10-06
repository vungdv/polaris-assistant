package vn.danang.polaris.assistant.customer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Location of Polaris Core's published REST contract (Order Management), used for the few calls that are
 * not model tools, such as resolving the caller's own customer.
 */
@ConfigurationProperties(prefix = "polaris.core.api")
@Getter
@Setter
public class PolarisCoreApiProperties {

    private String baseUrl = "http://localhost:8080";
    private int timeoutSeconds = 10;
}
