package br.com.elifootcarvoeiro.competition;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
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
class TopScorersServiceIT {

    @Autowired TopScorersService topScorersService;
    @Autowired StandingsService standingsService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired ClubRepository clubRepository;

    private record Career(UUID userId, SaveGameResponse save) { UUID id() { return save.id(); } }

    private Career createCareer() {
        User user = User.create("scorers_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void aggregatesGoalsAcrossPlayedFixturesSortedDescending() {
        // Given a career advanced through a full season
        Career c = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(c.id()).get(0).getId();
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }

        // When computing top scorers
        List<TopScorerRow> scorers = topScorersService.getTopScorers(c.id(), competitionId, c.userId());

        // Then every scorer has >=1 goal, the list is sorted descending, and the
        // total goals equals the league-wide goals-for (every goal attributed once)
        assertThat(scorers).isNotEmpty();
        assertThat(scorers).allMatch(s -> s.goals() >= 1);
        for (int i = 1; i < scorers.size(); i++) {
            assertThat(scorers.get(i - 1).goals()).isGreaterThanOrEqualTo(scorers.get(i).goals());
        }
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        int totalGoalsFor = standingsService.getStandings(c.id(), competitionId, c.userId()).stream()
                .mapToInt(StandingRow::goalsFor).sum();
        int totalScored = scorers.stream().mapToInt(TopScorerRow::goals).sum();
        assertThat(totalScored).isEqualTo(totalGoalsFor);
        assertThat(scorers.get(0).clubName()).isIn(clubs.stream().map(Club::getName).toList());
    }

    @Test
    void foreignUserCannotReadTopScorers() {
        Career owner = createCareer();
        Career intruder = createCareer();
        UUID competitionId = competitionRepository.findBySaveGameId(owner.id()).get(0).getId();
        assertThatThrownBy(() -> topScorersService.getTopScorers(owner.id(), competitionId, intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }
}
