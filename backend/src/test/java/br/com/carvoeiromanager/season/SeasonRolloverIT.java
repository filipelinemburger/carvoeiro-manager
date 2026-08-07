package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11: rollover must regenerate season state (fixtures, season_event rows) while leaving
 * {@code competition_phase} rows untouched — phases are competition structure, not season state.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonRolloverIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired br.com.carvoeiromanager.competition.CompetitionRepository competitionRepository;
    @Autowired CompetitionPhaseRepository phaseRepository;
    @Autowired FixtureRepository fixtureRepository;

    private record Career(UUID userId, UUID saveGameId) { }

    private Career createCareer() {
        User user = User.create("phaseroll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save.id());
    }

    private void finishSeason(Career c) {
        int guard = 0;
        while (saveGameRepository.findById(c.saveGameId()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.saveGameId(), c.userId());
        }
    }

    @Test
    void rolloverKeepsPhasesAndRegeneratesOnlyFixturesAndEvents() {
        Career c = createCareer();
        finishSeason(c);

        UUID catarinenseId = competitionRepository.findBySaveGameId(c.saveGameId()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .map(Competition::getId)
                .findFirst().orElseThrow();

        List<UUID> before = phaseRepository.findByCompetitionIdOrderByOrdinalAsc(catarinenseId)
                .stream().map(CompetitionPhase::getId).toList();
        assertThat(before).hasSize(4); // Primeira Fase, Quartas, Semifinal, Final

        rolloverService.rollover(c.saveGameId(), c.userId());

        List<UUID> after = phaseRepository.findByCompetitionIdOrderByOrdinalAsc(catarinenseId)
                .stream().map(CompetitionPhase::getId).toList();
        assertThat(after).isEqualTo(before);

        // The new season's first phase is materialized again, the knockout reserved again.
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.saveGameId(), after.get(0))).hasSize(66);
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.saveGameId(), after.get(1))).isEmpty();
    }
}
