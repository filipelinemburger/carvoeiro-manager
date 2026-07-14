package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "Teste", Position.MID,
                24, "Brasil", 50, 50, 50, 50, 50, 50, 50);
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
}
