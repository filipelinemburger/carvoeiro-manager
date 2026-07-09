package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCommentaryTest {

    @Test
    void userTacticsChangeKeepsTheGenericText() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, null))
                .isEqualTo("Mudança tática do treinador.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "PUSH;O adversário se lança ao ataque.",
            "ALL_OUT;Tudo ou nada do adversário: 4-2-4 ofensivo!",
            "PROTECT;O adversário recua para segurar o resultado.",
            "PARK_THE_BUS;Adversário fecha-se na defesa: 5-4-1.",
            "NORMAL;O adversário reorganiza a equipe.",
    })
    void aiTacticsChangeGetsPostureSpecificText(String detail, String expected) {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, detail))
                .isEqualTo(expected);
    }

    @Test
    void nonTacticsEventsIgnoreTheDetailParameter() {
        assertThat(LiveCommentary.of(EventType.SUBSTITUTION, "Sai", "Entra", 70, null))
                .isEqualTo("Substituição: entra Entra, sai Sai.");
    }
}
