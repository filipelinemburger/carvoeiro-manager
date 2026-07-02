package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventTypeTest {
    @Test
    void definesLiveOnlyEventTypes() {
        assertThat(EventType.valueOf("CHANCE")).isNotNull();
        assertThat(EventType.valueOf("INJURY")).isNotNull();
        assertThat(EventType.valueOf("SUBSTITUTION")).isNotNull();
    }
}
