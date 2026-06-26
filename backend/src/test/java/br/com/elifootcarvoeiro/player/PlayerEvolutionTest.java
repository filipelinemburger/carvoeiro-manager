package br.com.elifootcarvoeiro.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerEvolutionTest {

    private Player aPlayer() {
        // create(saveGameId, clubId, name, position, age, nationality, pace, fin, pas, tac, gk, sta, overall)
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "Tester", Position.MID,
                25, "Brasil", 60, 55, 70, 65, 10, 72, 66);
    }

    @Test
    void evolveAttributesReplacesAllSixAttributesAndOverall() {
        // Given a player
        Player p = aPlayer();

        // When evolving its attributes
        p.evolveAttributes(62, 57, 68, 63, 12, 70, 64);

        // Then every attribute and overall reflect the new values
        assertThat(p.getPace()).isEqualTo(62);
        assertThat(p.getFinishing()).isEqualTo(57);
        assertThat(p.getPassing()).isEqualTo(68);
        assertThat(p.getTackling()).isEqualTo(63);
        assertThat(p.getGoalkeeping()).isEqualTo(12);
        assertThat(p.getStamina()).isEqualTo(70);
        assertThat(p.getOverall()).isEqualTo(64);
    }

    @Test
    void resetSeasonStateZeroesFormCardsAndSuspension() {
        // Given a player carrying season state
        Player p = aPlayer();
        p.applyForm(3);
        p.addYellowCard();
        p.addSuspension(2);

        // When resetting season state
        p.resetSeasonState();

        // Then form, yellow cards and suspension are all zero (player eligible again)
        assertThat(p.getForm()).isZero();
        assertThat(p.getYellowCards()).isZero();
        assertThat(p.getSuspensionRemaining()).isZero();
        assertThat(p.isEligible()).isTrue();
    }
}
