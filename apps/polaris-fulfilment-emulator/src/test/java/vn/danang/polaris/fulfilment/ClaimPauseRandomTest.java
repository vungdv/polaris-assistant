package vn.danang.polaris.fulfilment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The runtime image is a plain JRE, so the claim-pause generator must come from {@code java.base}. Tests run on a full
 * JDK where {@code jdk.random} is present, so this asserts the module rather than relying on a failure to appear.
 */
class ClaimPauseRandomTest {

    private static RandomGenerator bean(String... seedProperty) {
        var source = new MapConfigurationPropertySource();
        if (seedProperty.length > 0) {
            source.put("polaris.fulfilment.random-seed", seedProperty[0]);
        }
        var properties = new Binder(source).bind("polaris.fulfilment", FulfilmentProperties.class)
                .orElseGet(() -> new Binder(source).bindOrCreate("polaris.fulfilment", FulfilmentProperties.class));
        return new FulfilmentConfiguration().claimPauseRandom(properties);
    }

    @Test
    @DisplayName("unseeded generator is served by java.base, available on a plain JRE")
    void unseededIsJavaBase() {
        assertThat(bean().getClass().getModule().getName()).isEqualTo("java.base");
    }

    @Test
    @DisplayName("seeded generator is served by java.base and is deterministic")
    void seededIsJavaBaseAndDeterministic() {
        assertThat(bean("7").getClass().getModule().getName()).isEqualTo("java.base");
        assertThat(bean("7").nextLong()).isEqualTo(bean("7").nextLong());
    }
}
