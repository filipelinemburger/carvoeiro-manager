package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerMoraleTest {

    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 25, "BRA",
                70, 70, 70, 70, 10, 70, 70);
    }

    @Test
    void moraleStartsNeutralAndClampsToPlusMinusFive() {
        Player p = player();
        assertThat(p.getMorale()).isZero();
        p.applyMorale(10);
        assertThat(p.getMorale()).isEqualTo(5);
        p.applyMorale(-20);
        assertThat(p.getMorale()).isEqualTo(-5);
    }

    @Test
    void resetSeasonStateZeroesMorale() {
        Player p = player();
        p.applyMorale(4);
        p.resetSeasonState();
        assertThat(p.getMorale()).isZero();
    }
}
