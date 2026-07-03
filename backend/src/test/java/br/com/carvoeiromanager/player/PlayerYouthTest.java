package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerYouthTest {
    private Player player() {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", Position.MID, 16, "Brasil",
                50, 50, 50, 50, 10, 50, 55);
    }

    @Test
    void playersAreNotProspectsByDefault() {
        Player p = player();
        assertThat(p.isYouthProspect()).isFalse();
        assertThat(p.getPotential()).isZero();
    }

    @Test
    void markAsProspectSetsFlagAndPotential() {
        Player p = player();
        p.markAsProspect(82);
        assertThat(p.isYouthProspect()).isTrue();
        assertThat(p.getPotential()).isEqualTo(82);
    }

    @Test
    void promoteFromYouthClearsTheFlag() {
        Player p = player();
        p.markAsProspect(82);
        p.promoteFromYouth();
        assertThat(p.isYouthProspect()).isFalse();
        assertThat(p.getPotential()).isEqualTo(82); // potential is retained, just no longer a prospect
    }
}
