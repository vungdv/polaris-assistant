package vn.danang.polaris.outbox;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class IntegrationEventTest {

    @Test
    void acceptsCompleteEvent() {
        assertThatNoException().isThrownBy(() -> new IntegrationEvent("t.v1", "/s", "d", "k", "data"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankAttributes(String blank) {
        assertThatThrownBy(() -> new IntegrationEvent(blank, "/s", "d", "k", "data")).hasMessageContaining("type");
        assertThatThrownBy(() -> new IntegrationEvent("t", blank, "d", "k", "data")).hasMessageContaining("source");
        assertThatThrownBy(() -> new IntegrationEvent("t", "/s", blank, "k", "data")).hasMessageContaining("destination");
        assertThatThrownBy(() -> new IntegrationEvent("t", "/s", "d", blank, "data")).hasMessageContaining("key");
    }

    @Test
    void rejectsNullData() {
        assertThatThrownBy(() -> new IntegrationEvent("t", "/s", "d", "k", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("data");
    }
}
