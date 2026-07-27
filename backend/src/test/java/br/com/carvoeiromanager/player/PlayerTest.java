package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "Teste", Position.MID,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
    }

    @Test
    void constructedPlayersPositionsStartsWithTheDetailedPositionAndAnyAlternatesAreAdjacent() {
        for (Position position : Position.values()) {
            for (int i = 0; i < 200; i++) {
                Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "Teste", position,
                        24, "Brasil", 50, 50, 50, 50, 50, 50, 50);

                List<DetailedPosition> positions = p.positions();

                assertThat(positions.get(0)).isEqualTo(p.getDetailedPosition());
                assertThat(positions.size()).isEqualTo(1 + p.getAltPositions().size());
                for (DetailedPosition alt : p.getAltPositions()) {
                    assertThat(p.getDetailedPosition().adjacent()).as("alternate %s of primary %s", alt,
                            p.getDetailedPosition()).contains(alt);
                }
            }
        }
    }

    @Test
    void setPositionsReplacesPrimaryAndAlternatesAndClearingAlternatesNullsTheColumn() {
        Player p = player();

        p.setPositions(DetailedPosition.ZAGUEIRO, List.of(DetailedPosition.LATERAL_DIREITO, DetailedPosition.VOLANTE));

        assertThat(p.getDetailedPosition()).isEqualTo(DetailedPosition.ZAGUEIRO);
        assertThat(p.getAltPositions()).containsExactly(DetailedPosition.LATERAL_DIREITO, DetailedPosition.VOLANTE);
        assertThat(p.positions()).containsExactly(DetailedPosition.ZAGUEIRO,
                DetailedPosition.LATERAL_DIREITO, DetailedPosition.VOLANTE);

        p.setPositions(DetailedPosition.GOLEIRO, List.of());

        assertThat(p.getAltPositions()).isEmpty();
        assertThat(p.positions()).containsExactly(DetailedPosition.GOLEIRO);
    }

    @Test
    void defaultsToNoTrainingFocusAndNormalIntensity() {
        Player p = player();

        assertThat(p.getTrainingFocus()).isNull();
        assertThat(p.getTrainingIntensity()).isEqualTo(TrainingIntensity.NORMAL);
    }

    @Test
    void settingATrainingAssignmentPersistsBothFields() {
        Player p = player();

        p.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.INTENSA);

        assertThat(p.getTrainingFocus()).isEqualTo(Attribute.FINALIZACAO);
        assertThat(p.getTrainingIntensity()).isEqualTo(TrainingIntensity.INTENSA);
    }

    @Test
    void settingANullIntensityFallsBackToNormal() {
        Player p = player();

        p.setTrainingAssignment(Attribute.DESARME, null);

        assertThat(p.getTrainingIntensity()).isEqualTo(TrainingIntensity.NORMAL);
    }

    @Test
    void settingANullFocusClearsIt() {
        Player p = player();
        p.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);

        p.setTrainingAssignment(null, TrainingIntensity.LEVE);

        assertThat(p.getTrainingFocus()).isNull();
    }

    @Test
    void resetSeasonStateZeroesTrainingProgress() {
        Player p = player();
        p.setTrainingProgress(25);

        p.resetSeasonState();

        assertThat(p.getTrainingProgress()).isZero();
    }
}
