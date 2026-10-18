package vn.danang.polaris.assistant.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binding contract for the split model properties: {@code polaris.ai.provider} selects, {@code polaris.ai.gemini.*}
 * configures Gemini, and the env var names of existing ConfigMaps keep resolving.
 */
class ModelPropertiesBindingTest {

    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ModelProviderProperties.class, GeminiProperties.class);

    @Test
    @DisplayName("Given no configuration, when the context starts, then provider is gemini and Gemini defaults apply")
    void defaults() {
        bare.run(context -> {
            assertThat(context.getBean(ModelProviderProperties.class).getProvider()).isEqualTo("gemini");
            GeminiProperties gemini = context.getBean(GeminiProperties.class);
            assertThat(gemini.getModel()).isEqualTo("gemini-3.6-flash");
            assertThat(gemini.getBaseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
            assertThat(gemini.getApiKey()).isEmpty();
            assertThat(gemini.getTimeoutSeconds()).isEqualTo(30);
            assertThat(gemini.getTurnDeadline()).isEqualTo(Duration.ofSeconds(25));
            assertThat(gemini.getSystemPrompt()).startsWith("You are Polaris Assistant");
        });
    }

    @Test
    @DisplayName("Given polaris.ai.provider=qwen, when bound, then the selector reads qwen and Gemini settings are untouched")
    void provider_value_is_bound_independently_of_gemini_block() {
        bare.withPropertyValues("polaris.ai.provider=qwen", "polaris.ai.gemini.model=m1").run(context -> {
            assertThat(context.getBean(ModelProviderProperties.class).getProvider()).isEqualTo("qwen");
            assertThat(context.getBean(GeminiProperties.class).getModel()).isEqualTo("m1");
        });
    }

    @Test
    @DisplayName("Given polaris.ai.gemini.* keys, when bound, then every Gemini field is populated")
    void gemini_block_binds_all_fields() {
        bare.withPropertyValues(
                "polaris.ai.gemini.api-key=k",
                "polaris.ai.gemini.model=m",
                "polaris.ai.gemini.base-url=http://x",
                "polaris.ai.gemini.timeout-seconds=7",
                "polaris.ai.gemini.turn-deadline=9s",
                "polaris.ai.gemini.system-prompt=sp").run(context -> {
            GeminiProperties gemini = context.getBean(GeminiProperties.class);
            assertThat(gemini.getApiKey()).isEqualTo("k");
            assertThat(gemini.getModel()).isEqualTo("m");
            assertThat(gemini.getBaseUrl()).isEqualTo("http://x");
            assertThat(gemini.getTimeoutSeconds()).isEqualTo(7);
            assertThat(gemini.getTurnDeadline()).isEqualTo(Duration.ofSeconds(9));
            assertThat(gemini.getSystemPrompt()).isEqualTo("sp");
        });
    }

    @Test
    @DisplayName("Given the old flat polaris.ai.* keys, when bound, then Gemini ignores them")
    void old_flat_keys_no_longer_bind() {
        bare.withPropertyValues("polaris.ai.model=legacy").run(context ->
                assertThat(context.getBean(GeminiProperties.class).getModel()).isEqualTo("gemini-3.6-flash"));
    }

    @Test
    @DisplayName("Given the main application.yml and the existing env var names, when bound, then ConfigMaps keep working")
    void existing_env_var_names_still_resolve() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(ModelProviderProperties.class, GeminiProperties.class)
                .withPropertyValues(
                        "spring.config.import=file:src/main/resources/application.yml",
                        "POLARIS_AI_API_KEY=legacy-key",
                        "POLARIS_AI_MODEL=env-model",
                        "POLARIS_AI_BASE_URL=http://env",
                        "POLARIS_AI_TIMEOUT_SECONDS=11",
                        "POLARIS_AI_TURN_DEADLINE=12s",
                        "POLARIS_AI_PROVIDER=qwen")
                .run(context -> {
                    assertThat(context.getBean(ModelProviderProperties.class).getProvider()).isEqualTo("qwen");
                    GeminiProperties gemini = context.getBean(GeminiProperties.class);
                    assertThat(gemini.getApiKey()).isEqualTo("legacy-key");
                    assertThat(gemini.getModel()).isEqualTo("env-model");
                    assertThat(gemini.getBaseUrl()).isEqualTo("http://env");
                    assertThat(gemini.getTimeoutSeconds()).isEqualTo(11);
                    assertThat(gemini.getTurnDeadline()).isEqualTo(Duration.ofSeconds(12));
                });
    }
}
