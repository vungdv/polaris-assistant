package vn.danang.polaris.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class QwenPropertiesIntegrationTest {

    @Test
    @DisplayName("Given no overrides, then Qwen's default systemPrompt equals Gemini's (plan 7.8)")
    void system_prompt_defaults_are_equal() {
        assertThat(new QwenProperties().getSystemPrompt()).isEqualTo(new GeminiProperties().getSystemPrompt());
    }

    @Test
    @DisplayName("Given no overrides, then Qwen uses its own larger deadline and timeout and Gemini's are unchanged")
    void qwen_has_its_own_larger_defaults() {
        QwenProperties qwen = new QwenProperties();
        GeminiProperties gemini = new GeminiProperties();

        assertThat(qwen.getModel()).isEqualTo("qwen3:4b");
        assertThat(qwen.getBaseUrl()).isEmpty();
        assertThat(qwen.getApiKey()).isEmpty();
        assertThat(qwen.getTimeoutSeconds()).isEqualTo(120).isGreaterThan(gemini.getTimeoutSeconds());
        assertThat(qwen.getTurnDeadline()).isEqualTo(Duration.ofSeconds(180)).isGreaterThan(gemini.getTurnDeadline());
        assertThat(gemini.getTimeoutSeconds()).isEqualTo(30);
        assertThat(gemini.getTurnDeadline()).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    @DisplayName("Given the main application.yml and the QWEN_* env var names, then every Qwen field binds")
    void env_var_names_resolve() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(QwenProperties.class)
                .withPropertyValues(
                        "spring.config.import=file:src/main/resources/application.yml",
                        "QWEN_API_KEY=k", "QWEN_BASE_URL=http://localhost:11434/v1", "QWEN_MODEL=m",
                        "QWEN_TIMEOUT_SECONDS=7", "QWEN_TURN_DEADLINE=9s")
                .run(context -> {
                    QwenProperties qwen = context.getBean(QwenProperties.class);
                    assertThat(qwen.getApiKey()).isEqualTo("k");
                    assertThat(qwen.getBaseUrl()).isEqualTo("http://localhost:11434/v1");
                    assertThat(qwen.getModel()).isEqualTo("m");
                    assertThat(qwen.getTimeoutSeconds()).isEqualTo(7);
                    assertThat(qwen.getTurnDeadline()).isEqualTo(Duration.ofSeconds(9));
                });
    }

    @Test
    @DisplayName("Given the main application.yml and no QWEN_* env vars, then the documented defaults apply")
    void yaml_defaults() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(QwenProperties.class)
                .withPropertyValues("spring.config.import=file:src/main/resources/application.yml")
                .run(context -> {
                    QwenProperties qwen = context.getBean(QwenProperties.class);
                    assertThat(qwen.getModel()).isEqualTo("qwen3:4b");
                    assertThat(qwen.getBaseUrl()).isEmpty();
                    assertThat(qwen.getTimeoutSeconds()).isEqualTo(120);
                    assertThat(qwen.getTurnDeadline()).isEqualTo(Duration.ofSeconds(180));
                });
    }
}
