package vn.danang.polaris.assistant.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import vn.danang.polaris.assistant.config.GeminiProperties;
import vn.danang.polaris.assistant.config.ModelProviderProperties;
import vn.danang.polaris.assistant.config.QwenProperties;
import vn.danang.polaris.assistant.entity.AssistantMessage;
import vn.danang.polaris.assistant.entity.MessageRole;

/** F2.1-F2.4: provider selection and startup validation, in a real Spring context with both providers over HTTP. */
class ModelProviderSelectionIntegrationTest {

    private static final String GEMINI_PATH = "/v1beta/models/gemini-3.6-flash:generateContent";
    private static final String QWEN_PATH = "/v1/chat/completions";
    private static final String GEMINI_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"from gemini"}],"role":"model"}}]}""";
    private static final String QWEN_BODY = """
            {"id":"c1","model":"qwen3:4b","choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"from qwen"}}]}""";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Configuration
    static class Registries {
        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        @Bean
        RetryRegistry retryRegistry() {
            return RetryRegistry.ofDefaults();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ModelProviderProperties.class, GeminiProperties.class, QwenProperties.class,
                    GeminiAiModelClient.class, OpenAiCompatibleModelClient.class, Registries.class,
                    ModelClientConfiguration.class);

    @BeforeEach
    void stubBoth() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(GEMINI_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(GEMINI_BODY)));
        wireMock.stubFor(post(urlEqualTo(QWEN_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(QWEN_BODY)));
    }

    private ApplicationContextRunner configured(String... extra) {
        String[] common = {
                "polaris.ai.gemini.api-key=g-key",
                "polaris.ai.gemini.base-url=" + wireMock.baseUrl(),
                "polaris.ai.qwen.base-url=" + wireMock.baseUrl() + "/v1"};
        String[] all = new String[common.length + extra.length];
        System.arraycopy(common, 0, all, 0, common.length);
        System.arraycopy(extra, 0, all, common.length, extra.length);
        return runner.withPropertyValues(all);
    }

    @Test
    @DisplayName("F2.1 Given provider unset, when a call is made, then Gemini handles it and Qwen is not called")
    void f2_1_default_is_gemini() {
        configured().run(context -> {
            AssistantModelClient client = context.getBean(AssistantModelClient.class);

            assertThat(client.chat(List.of(user("hi")))).isEqualTo("from gemini");
            assertThat(client.turnDeadline()).isEqualTo(Duration.ofSeconds(25));
            wireMock.verify(1, postRequestedFor(urlEqualTo(GEMINI_PATH)));
            wireMock.verify(0, postRequestedFor(urlEqualTo(QWEN_PATH)));
        });
    }

    @Test
    @DisplayName("F2.2 Given provider=qwen, when a call is made, then the OpenAI-compatible client handles it and Gemini is not called")
    void f2_2_qwen_selected() {
        configured("polaris.ai.provider=qwen").run(context -> {
            AssistantModelClient client = context.getBean(AssistantModelClient.class);

            assertThat(client.chat(List.of(user("hi")))).isEqualTo("from qwen");
            assertThat(client.turnDeadline()).isEqualTo(Duration.ofSeconds(180));
            wireMock.verify(1, postRequestedFor(urlEqualTo(QWEN_PATH)));
            wireMock.verify(0, postRequestedFor(urlEqualTo(GEMINI_PATH)));
        });
    }

    @Test
    @DisplayName("F2.3 Given provider=foo, when the context starts, then startup fails naming the allowed values")
    void f2_3_unknown_provider() {
        configured("polaris.ai.provider=foo").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure()))
                    .contains("'foo'").contains("gemini").contains("qwen");
        });
    }

    @Test
    @DisplayName("F2.4 Given provider=qwen and a blank base-url, when the context starts, then startup fails naming QWEN_BASE_URL")
    void f2_4_missing_base_url() {
        runner.withPropertyValues("polaris.ai.provider=qwen", "polaris.ai.qwen.base-url=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("QWEN_BASE_URL");
        });
    }

    @Test
    @DisplayName("Given provider=gemini and no Qwen base-url, when the context starts, then it starts")
    void qwen_base_url_is_only_required_for_qwen() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    private static AssistantMessage user(String content) {
        AssistantMessage message = new AssistantMessage();
        message.setRole(MessageRole.USER);
        message.setContent(content);
        return message;
    }
}
