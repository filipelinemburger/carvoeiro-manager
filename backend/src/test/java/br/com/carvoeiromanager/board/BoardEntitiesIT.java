package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class BoardEntitiesIT {

    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired BoardStateRepository stateRepository;
    @Autowired BoardObjectiveRepository objectiveRepository;
    @Autowired BoardConfidenceChangeRepository changeRepository;

    private UUID newSave() {
        User user = User.create("board_ent_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return saveGameService.createCareer(user.getId()).id();
    }

    @Test
    void stateObjectiveAndChangeRoundTrip() {
        UUID saveId = newSave();
        stateRepository.deleteById(saveId); // career creation may already have written one
        objectiveRepository.deleteAll(objectiveRepository.findBySaveGameIdOrderBySeasonYearAsc(saveId));

        BoardState state = BoardState.create(saveId, 42, false, 2026);
        state.issueUltimatum();
        state.countMatchSinceUltimatum();
        stateRepository.save(state);
        BoardObjective objective = BoardObjective.create(saveId, 2026, ObjectiveKind.STATE, UUID.randomUUID(),
                null, null, Stage.SEMIFINAL);
        objectiveRepository.save(objective);
        changeRepository.save(BoardConfidenceChange.create(saveId, LocalDate.of(2026, 2, 1), 1,
                BoardChangeReason.MATCH_RESULT, -3, 39, "{\"opponent\":\"Avaí\"}"));

        BoardState loaded = stateRepository.findById(saveId).orElseThrow();
        assertThat(loaded.getConfidence()).isEqualTo(42);
        assertThat(loaded.isUltimatumActive()).isTrue();
        assertThat(loaded.getMatchesSinceUltimatum()).isEqualTo(1);
        assertThat(loaded.canSack(2026)).isFalse();
        BoardObjective loadedObjective = objectiveRepository.findById(objective.getId()).orElseThrow();
        assertThat(loadedObjective.getTargetStage()).isEqualTo(Stage.SEMIFINAL);
        assertThat(loadedObjective.getOutcome()).isEqualTo(ObjectiveOutcome.PENDING);
        assertThat(changeRepository.findTop8BySaveGameIdOrderBySeqDesc(saveId))
                .extracting(BoardConfidenceChange::getReason).contains(BoardChangeReason.MATCH_RESULT);
    }

    @Test
    void aSeasonHasAtMostOneObjectiveOfEachKind() {
        UUID saveId = newSave();
        objectiveRepository.deleteAll(objectiveRepository.findBySaveGameIdOrderBySeasonYearAsc(saveId));
        objectiveRepository.saveAndFlush(BoardObjective.create(saveId, 2026, ObjectiveKind.FINANCE, null, null, null, null));

        assertThatThrownBy(() -> objectiveRepository.saveAndFlush(
                BoardObjective.create(saveId, 2026, ObjectiveKind.FINANCE, null, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anObjectiveResolvesOnlyOnce() {
        BoardObjective objective = BoardObjective.create(UUID.randomUUID(), 2026, ObjectiveKind.FINANCE,
                null, null, null, null);
        objective.resolve(ObjectiveOutcome.MET);

        assertThatThrownBy(() -> objective.resolve(ObjectiveOutcome.FAILED)).isInstanceOf(IllegalStateException.class);
    }
}
