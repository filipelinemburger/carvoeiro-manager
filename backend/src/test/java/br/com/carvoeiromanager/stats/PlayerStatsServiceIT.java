package br.com.carvoeiromanager.stats;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.match.FixtureAppearance;
import br.com.carvoeiromanager.match.FixtureAppearanceRepository;
import br.com.carvoeiromanager.match.MatchEvent;
import br.com.carvoeiromanager.match.MatchEventRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
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
class PlayerStatsServiceIT {

    @Autowired PlayerStatsService playerStatsService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired FixtureAppearanceRepository fixtureAppearanceRepository;
    @Autowired MatchEventRepository matchEventRepository;

    @Test
    void currentSeasonStatsAggregateAppearancesAndEventsForOnePlayer() {
        User user = User.create("stats_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();

        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId());
        Fixture fixture = fixtures.get(0);
        Club club = clubRepository.findById(fixture.getHomeClubId()).orElseThrow();
        Player player = playerRepository.findByClubId(club.getId()).get(0);

        fixtureAppearanceRepository.save(
                FixtureAppearance.create(fixture.getId(), player.getId(), club.getId(), 90, 4));
        matchEventRepository.save(
                MatchEvent.create(fixture.getId(), 30, "GOAL", player.getId(), club.getId()));
        matchEventRepository.save(
                MatchEvent.create(fixture.getId(), 55, "YELLOW_CARD", player.getId(), club.getId()));

        PlayerStatsResponse response = playerStatsService.getStats(saveGameId, player.getId(), user.getId());

        assertThat(response.seasons()).isNotEmpty();
        SeasonStatsLine line = response.seasons().stream()
                .filter(l -> l.competitionName().equals(competition.getName()))
                .findFirst().orElseThrow();
        assertThat(line.matchesPlayed()).isEqualTo(1);
        assertThat(line.minutesPlayed()).isEqualTo(90);
        assertThat(line.goals()).isEqualTo(1);
        assertThat(line.fouls()).isEqualTo(4);
        assertThat(line.yellowCards()).isEqualTo(1);

        assertThat(response.totals().goals()).isEqualTo(1);
        assertThat(response.totals().minutesPlayed()).isEqualTo(90);
    }

    @Test
    void aPlayerWithNoAppearancesGetsAnEmptySeasonListAndZeroTotals() {
        User user = User.create("stats_empty_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        UUID saveGameId = save.id();
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).get(0);
        Player player = playerRepository.findByClubId(fixture.getHomeClubId()).get(0);

        PlayerStatsResponse response = playerStatsService.getStats(saveGameId, player.getId(), user.getId());

        assertThat(response.seasons()).isEmpty();
        assertThat(response.totals().matchesPlayed()).isZero();
        assertThat(response.totals().goals()).isZero();
    }
}
