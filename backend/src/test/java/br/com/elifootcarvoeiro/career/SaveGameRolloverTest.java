package br.com.elifootcarvoeiro.career;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SaveGameRolloverTest {

    @Test
    void advanceToNextSeasonBumpsYearResetsRoundAndReopensTheSave() {
        // Given a finished save in season 2026 at round 18
        SaveGame save = SaveGame.create(UUID.randomUUID(), 2026);
        save.setCurrentRound(18);
        save.setStatus(SaveGameStatus.FINISHED);

        // When advancing to the next season
        save.advanceToNextSeason();

        // Then the year increments, the round resets, and the save is in progress again
        assertThat(save.getSeasonYear()).isEqualTo(2027);
        assertThat(save.getCurrentRound()).isZero();
        assertThat(save.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
    }
}
