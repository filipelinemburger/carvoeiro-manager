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

    @Test
    void penaltyGoalMissAndFreeKickLines() {
        // Variant index = minute + type.ordinal() mod array length; assert membership, not index math.
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null)).isIn(
                "Pênalti! Otero converte com categoria!", "GOL DE PÊNALTI! Otero não desperdiça!");
        assertThat(LiveCommentary.of(EventType.PENALTY_MISSED, "Otero", null, 60, null)).isIn(
                "Pênalti perdido! Otero desperdiça — defesa do goleiro!",
                "Otero cobra o pênalti... e o goleiro defende!");
        assertThat(LiveCommentary.of(EventType.FREE_KICK_GOAL, "Otero", null, 60, null)).isIn(
                "GOL DE FALTA! Otero acerta o ângulo!", "Falta cobrada por Otero... GOL!");
    }

    @Test
    void cornerGoalNamesTakerAndScorer() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", "Batedor", 70, null))
                .isEqualTo("Escanteio de Batedor, Zagueiro sobe e cabeceia: GOL!");
    }

    @Test
    void cornerGoalFallsBackToOneNameWhenTakerMissingOrScoredHimself() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", null, 70, null))
                .isEqualTo("GOL de escanteio! Zagueiro aparece na área e marca!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Batedor", "Batedor", 70, null))
                .isEqualTo("GOL de escanteio! Batedor aparece na área e marca!");
    }
}
