package vn.danang.polaris.assistant.health;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import vn.danang.polaris.assistant.config.GeminiProperties;
import vn.danang.polaris.assistant.config.QwenProperties;

/** X9 for Qwen: probe of {@code GET {base-url}/models} over real HTTP, selection, and exclusion from readiness. */
class QwenHealthIndicatorIntegrationTest {

    private static final Path MODELS = Path
            .of("../../docs/development/plan/qwen-model-client-fixtures/models.response.json");
    private static final String SECRET = "sk-secret-health-key";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private QwenProperties properties;
    private QwenHealthIndicator indicator;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        properties = new QwenProperties();
        properties.setBaseUrl(wireMock.baseUrl() + "/v1");
        properties.setTimeoutSeconds(1);
        indicator = new QwenHealthIndicator(properties);
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("Given /models returns 200 with the fixture, when checked, then UP naming the provider; no Bearer without a key")
    void up_on_models_fixture() throws IOException {
        wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(Files.readString(MODELS))));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("provider", "qwen").containsEntry("httpStatus", 200);
        wireMock.verify(getRequestedFor(urlEqualTo("/v1/models")).withHeader("Authorization", absent()));
    }

    @Test
    @DisplayName("Given a key, when checked, then Bearer is sent and the key is neither logged nor reported")
    void bearer_only_with_key_and_key_never_exposed() {
        properties.setApiKey(SECRET);
        wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(401)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        wireMock.verify(getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET)));
        assertThat(health.getDetails().toString()).doesNotContain(SECRET);
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(SECRET));
    }

    @Test
    @DisplayName("Given a 503, when checked, then DOWN with the status")
    void down_on_error_status() {
        wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(503)));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("httpStatus", 503);
    }

    @Test
    @DisplayName("Given a response slower than timeout-seconds, when checked, then DOWN")
    void down_on_timeout() {
        wireMock.stubFor(get(urlEqualTo("/v1/models")).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Given a connection reset, when checked, then DOWN")
    void down_on_connection_reset() {
        wireMock.stubFor(get(urlEqualTo("/v1/models"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Given nothing listens on the port, when checked, then DOWN (connection refused)")
    void down_on_connection_refused() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        properties.setBaseUrl("http://localhost:" + freePort + "/v1");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("provider", "qwen");
    }

    @Test
    @DisplayName("Given a blank base URL, when checked, then DOWN without any request")
    void down_when_not_configured() {
        properties.setBaseUrl("");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("Only the selected provider contributes: qwen for provider=qwen, gemini otherwise")
    void only_selected_provider_contributes() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(QwenProperties.class)
                .withBean(GeminiProperties.class)
                .withUserConfiguration(QwenHealthIndicator.class, GeminiHealthIndicator.class);

        runner.withPropertyValues("polaris.ai.provider=qwen").run(ctx -> {
            assertThat(ctx).hasBean("qwen");
            assertThat(ctx).doesNotHaveBean("gemini");
        });
        runner.run(ctx -> {
            assertThat(ctx).hasBean("gemini");
            assertThat(ctx).doesNotHaveBean("qwen");
        });
    }

    @Test
    @DisplayName("The readiness group in the shipped application.yml includes neither qwen nor gemini")
    void not_in_readiness_group() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        // Test resources shadow main's application.yml on the classpath, so read main's file directly.
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();

        List<String> readiness = List
                .of(props.getProperty("management.endpoint.health.group.readiness.include").split(","));

        assertThat(readiness).contains("db", "polarisMcp");
        assertThat(readiness).doesNotContain("qwen", "gemini");
    }
}
