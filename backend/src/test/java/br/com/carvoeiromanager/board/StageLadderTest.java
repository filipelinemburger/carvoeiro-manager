package br.com.carvoeiromanager.board;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageLadderTest {

    @ParameterizedTest(name = "knockout {0} of {1} is {2}")
    @CsvSource({"0, 3, QUARTERFINAL", "1, 3, SEMIFINAL", "2, 3, FINAL", "0, 1, FINAL", "0, 2, SEMIFINAL"})
    void knockoutPhasesMapToStagesFromTheFinalBackwards(int index, int count, Stage stage) {
        assertThat(StageLadder.stageOfKnockout(index, count)).isEqualTo(stage);
    }

    @Test
    void winningAPhaseReachesTheNextStageAndWinningTheFinalIsTheTitle() {
        assertThat(StageLadder.stageAfterWinning(0, 3)).isEqualTo(Stage.SEMIFINAL);
        assertThat(StageLadder.stageAfterWinning(2, 3)).isEqualTo(Stage.CHAMPION);
    }

    @Test
    void moreThanThreeKnockoutPhasesHaveNoStageNames() {
        assertThatThrownBy(() -> StageLadder.stageOfKnockout(0, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extend Stage");
    }
}
