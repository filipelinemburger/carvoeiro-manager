package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class CompetitionPhaseIT {

    @Autowired
    private CompetitionPhaseRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SaveGameRepository saveGameRepository;

    /**
     * Same lightweight User + SaveGame pattern used by FixtureRepositoryIT in this package —
     * competition_phase.save_game_id has a real FK to save_games(id), so a random UUID would
     * violate it, and save_games.user_id in turn has a real FK to users(id).
     */
    private SaveGame createSaveGame() {
        User user = User.create("phase_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2025);
        return saveGameRepository.save(save);
    }

    @Test
    void phasesAreReadBackInOrdinalOrder() {
        // Given: three phases of one competition, saved out of order.
        // NOTE: competition_phase.save_game_id has a FK to save_games, so a random UUID
        // would violate it. Create a real save first, the way other ITs in this package do.
        UUID saveGameId = createSaveGame().getId();
        UUID competitionId = UUID.randomUUID();   // no FK on this column
        repository.save(CompetitionPhase.create(
                saveGameId, competitionId, 2, "Final", PhaseKind.KNOCKOUT, 2, 2, null));
        repository.save(CompetitionPhase.create(
                saveGameId, competitionId, 0, "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, 11, 8));
        repository.save(CompetitionPhase.create(
                saveGameId, competitionId, 1, "Semifinal", PhaseKind.KNOCKOUT, 2, 2, null));

        // When
        List<CompetitionPhase> phases = repository.findByCompetitionIdOrderByOrdinalAsc(competitionId);

        // Then
        assertThat(phases).extracting(CompetitionPhase::getName)
                .containsExactly("Primeira Fase", "Semifinal", "Final");
        assertThat(phases.get(0).getKind()).isEqualTo(PhaseKind.ROUND_ROBIN);
        assertThat(phases.get(0).getQualifiers()).isEqualTo(8);
        assertThat(phases.get(2).getQualifiers()).isNull();
    }
}
