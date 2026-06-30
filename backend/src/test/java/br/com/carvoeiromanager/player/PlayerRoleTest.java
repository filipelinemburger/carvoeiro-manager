package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerRoleTest {

    private Player outfield(Position pos, int pace, int fin, int pass, int tkl) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                pace, fin, pass, tkl, 10, 70, 75);
    }

    @Test
    void poacherWeightsFinishingForAttack() {
        Player p = outfield(Position.ATT, 50, 90, 40, 10);
        // 0.2*pace + 0.8*finishing = 0.2*50 + 0.8*90 = 82
        assertThat(PlayerRole.POACHER.attackContribution(p)).isEqualTo(82.0);
        assertThat(PlayerRole.POACHER.defenseContribution(p)).isEqualTo(0.0);
    }

    @Test
    void holdingMidWeightsTacklingForDefense() {
        Player p = outfield(Position.MID, 50, 50, 60, 80);
        // defense = 0.8 * tackling = 64
        assertThat(PlayerRole.HOLDING_MID.defenseContribution(p)).isEqualTo(64.0);
    }

    @Test
    void goalkeeperWeightsGoalkeepingForDefenseAndNothingForAttack() {
        // goalkeeping is the 5th weight slot; this is the only role that uses it
        Player gk = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.GK, 25, "BRA",
                50, 10, 30, 20, 85, 70, 75);
        // defense = 1.0 * goalkeeping = 85; attack weights are all zero
        assertThat(PlayerRole.GOALKEEPER.defenseContribution(gk)).isEqualTo(85.0);
        assertThat(PlayerRole.GOALKEEPER.attackContribution(gk)).isEqualTo(0.0);
    }

    @Test
    void rolesValidateAgainstPositionBand() {
        assertThat(PlayerRole.POACHER.isValidFor(Position.ATT)).isTrue();
        assertThat(PlayerRole.POACHER.isValidFor(Position.DEF)).isFalse();
    }

    @Test
    void defaultRolePerBand() {
        assertThat(PlayerRole.defaultFor(Position.GK)).isEqualTo(PlayerRole.GOALKEEPER);
        assertThat(PlayerRole.defaultFor(Position.DEF)).isEqualTo(PlayerRole.FULL_BACK);
        assertThat(PlayerRole.defaultFor(Position.MID)).isEqualTo(PlayerRole.BOX_TO_BOX);
        assertThat(PlayerRole.defaultFor(Position.ATT)).isEqualTo(PlayerRole.POACHER);
    }
}
