package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsCalculator;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class RoundSimulationServiceIT {

    @Autowired
    RoundSimulationService roundSimulationService;

    @Autowired
    SaveGameService saveGameService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    SaveGameRepository saveGameRepository;

    @Autowired
    ClubRepository clubRepository;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    FixtureRepository fixtureRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
    }

    private Career createCareer() {
        User user = User.create("sim_test_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        return new Career(user.getId(), save);
    }

    @Test
    void advancingOnceSimulatesAllRound1FixturesAndIncreasesCurrentRound() {
        // Given a fresh career
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID userId = career.userId();
        int clubCount = clubRepository.findBySaveGameId(saveGameId).size(); // 10

        // When advancing to round 1
        SimulateRoundResponse response = roundSimulationService.simulateNextRound(saveGameId, userId);

        // Then
        assertThat(response.round()).isEqualTo(1);
        assertThat(response.results()).hasSize(clubCount / 2); // 5 fixtures in round 1
        assertThat(response.seasonFinished()).isFalse();

        // All first-round fixtures (DB round index 0) are played with goals set
        // Fixtures are stored with 0-based indices (0..totalRounds-1); the first advance simulates round 0.
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> firstRound = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, competition.getId(), 0);
        assertThat(firstRound).hasSize(clubCount / 2);
        assertThat(firstRound).allMatch(Fixture::isPlayed);
        assertThat(firstRound).allMatch(f -> f.getHomeGoals() != null && f.getAwayGoals() != null);

        // SaveGame currentRound is now 1
        SaveGame saved = saveGameRepository.findById(saveGameId).orElseThrow();
        assertThat(saved.getCurrentRound()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
    }

    @Test
    void advancingThroughAllRoundsFinishesTheSeason() {
        // Given a fresh career with 10 clubs = 18 total rounds
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID userId = career.userId();

        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        int totalRounds = competition.getTotalRounds(); // 18
        int clubCount = clubRepository.findBySaveGameId(saveGameId).size(); // 10

        // When advancing through all rounds
        SimulateRoundResponse lastResponse = null;
        for (int i = 1; i <= totalRounds; i++) {
            lastResponse = roundSimulationService.simulateNextRound(saveGameId, userId);
        }

        // Then the last advance marks seasonFinished
        assertThat(lastResponse).isNotNull();
        assertThat(lastResponse.round()).isEqualTo(totalRounds);
        assertThat(lastResponse.seasonFinished()).isTrue();

        // SaveGame status is FINISHED
        SaveGame saved = saveGameRepository.findById(saveGameId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SaveGameStatus.FINISHED);
        assertThat(saved.getCurrentRound()).isEqualTo(totalRounds);

        // Every fixture is played
        List<Fixture> allFixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(
                saveGameId, competition.getId());
        assertThat(allFixtures).allMatch(Fixture::isPlayed);
        assertThat(allFixtures).allMatch(f -> f.getHomeGoals() != null && f.getAwayGoals() != null);

        // Standings: each club played exactly (clubCount - 1) * 2 / clubCount... no:
        // total fixtures = clubCount * (clubCount - 1) = 90, each club plays 18 games
        List<br.com.elifootcarvoeiro.club.Club> clubs = clubRepository.findBySaveGameId(saveGameId);
        StandingsCalculator calculator = new StandingsCalculator();
        List<StandingRow> standings = calculator.compute(clubs, allFixtures);

        assertThat(standings).hasSize(clubCount);
        assertThat(standings).allMatch(row -> row.played() == totalRounds);

        // Total GF across all clubs == total GA (zero-sum)
        int totalGF = standings.stream().mapToInt(StandingRow::goalsFor).sum();
        int totalGA = standings.stream().mapToInt(StandingRow::goalsAgainst).sum();
        assertThat(totalGF).isEqualTo(totalGA);
    }

    @Test
    void advancingWhenSeasonFinishedThrowsConflict() {
        // Given a career fully advanced
        Career career = createCareer();
        UUID saveGameId = career.id();
        UUID userId = career.userId();

        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        int totalRounds = competition.getTotalRounds();

        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(saveGameId, userId);
        }

        // When trying to advance again
        assertThatThrownBy(() -> roundSimulationService.simulateNextRound(saveGameId, userId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo("SEASON_FINISHED");
                    assertThat(apiEx.getStatus().value()).isEqualTo(409);
                });
    }
}
