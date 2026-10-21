package br.com.carvoeiromanager.board;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectiveTargetsTest {

    @ParameterizedTest(name = "Série A rank {0} → finish {1}º or better")
    @CsvSource({"1, 4", "3, 4", "4, 6", "8, 6", "9, 12", "14, 12", "15, 16", "20, 16"})
    void serieATargets(int rank, int target) {
        assertThat(ObjectiveTargets.league(1, rank, 20)).isEqualTo(new LeagueTarget(target, null));
    }

    @ParameterizedTest(name = "Série B rank {0} → finish {1}º or better")
    @CsvSource({"1, 4", "3, 4", "4, 8", "7, 8", "8, 10", "13, 10", "14, 16", "20, 16"})
    void serieBTargets(int rank, int target) {
        assertThat(ObjectiveTargets.league(2, rank, 20)).isEqualTo(new LeagueTarget(target, null));
    }

    @ParameterizedTest(name = "Série C rank {0} → reach {1}")
    @CsvSource({"1, FINAL", "2, FINAL", "3, SEMIFINAL", "5, SEMIFINAL", "6, QUARTERFINAL", "10, QUARTERFINAL"})
    void serieCStageTargets(int rank, Stage stage) {
        assertThat(ObjectiveTargets.league(3, rank, 20)).isEqualTo(new LeagueTarget(null, stage));
    }

    @ParameterizedTest(name = "Série C rank {0} → first phase 14º or better")
    @CsvSource({"11", "20"})
    void serieCWeakestBandIsAFirstPhasePosition(int rank) {
        assertThat(ObjectiveTargets.league(3, rank, 20)).isEqualTo(new LeagueTarget(14, null));
    }

    @ParameterizedTest(name = "Catarinense rank {0} → {1}")
    @CsvSource({"1, CHAMPION", "2, CHAMPION", "3, FINAL", "4, FINAL", "5, SEMIFINAL", "6, SEMIFINAL",
            "7, QUARTERFINAL", "12, QUARTERFINAL"})
    void stateTargets(int rank, Stage stage) {
        assertThat(ObjectiveTargets.state(rank, 12)).isEqualTo(stage);
    }

    @Test
    void bandsScaleWithTheFieldSize() {
        // A 10-club Série B: the G-4 band becomes ranks 1–2 and its target 2nd
        assertThat(ObjectiveTargets.league(2, 2, 10)).isEqualTo(new LeagueTarget(2, null));
        // the weakest band's target becomes ceil(16 · 10 / 20) = 8th
        assertThat(ObjectiveTargets.league(2, 10, 10)).isEqualTo(new LeagueTarget(8, null));
    }

    @Test
    void anUnknownTierHasNoTargets() {
        assertThatThrownBy(() -> ObjectiveTargets.league(4, 1, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expectedRankOrdersByStrengthThenName() {
        UUID b = UUID.randomUUID();
        UUID z = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        Map<UUID, Double> strength = Map.of(b, 50.0, z, 60.0, a, 60.0);
        Map<UUID, String> names = Map.of(b, "B", z, "Z", a, "A");

        assertThat(ObjectiveTargets.expectedRank(a, strength, names)).isEqualTo(1);
        assertThat(ObjectiveTargets.expectedRank(z, strength, names)).isEqualTo(2);
        assertThat(ObjectiveTargets.expectedRank(b, strength, names)).isEqualTo(3);
    }
}
