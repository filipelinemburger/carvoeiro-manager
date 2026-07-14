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
import br.com.carvoeiromanager.season.Season;
import br.com.carvoeiromanager.season.SeasonPlayerStats;
import br.com.carvoeiromanager.season.SeasonPlayerStatsRepository;
import br.com.carvoeiromanager.season.SeasonRepository;
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
    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonPlayerStatsRepository seasonPlayerStatsRepository;

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

    @Test
    void archivedStatsFromADifferentSaveGameAreNotLeakedIntoThisSavesResponse() {
        // Save game A hosts the archived Season + SeasonPlayerStats row.
        User userA = User.create("stats_iso_a_" + UUID.randomUUID(), "hash");
        userRepository.save(userA);
        SaveGameResponse saveA = saveGameService.createCareer(userA.getId());
        Competition competitionA = competitionRepository.findBySaveGameId(saveA.id()).get(0);

        // Save game B is the one actually being queried, by a different user entirely.
        User userB = User.create("stats_iso_b_" + UUID.randomUUID(), "hash");
        userRepository.save(userB);
        SaveGameResponse saveB = saveGameService.createCareer(userB.getId());
        Competition competitionB = competitionRepository.findBySaveGameId(saveB.id()).get(0);
        Fixture fixtureB = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveB.id(), competitionB.getId()).get(0);
        Player playerB = playerRepository.findByClubId(fixtureB.getHomeClubId()).get(0);

        // Archive a SeasonPlayerStats row under save game A's season, but keyed to save B's
        // player id — reproducing the exact collision scenario the isolation gap allowed:
        // season_player_stats has no saveGameId column of its own, only reaching one indirectly
        // through Season.saveGameId, so a row must never be trusted without checking that join.
        Season seasonA = Season.create(saveA.id(), competitionA.getId(), competitionA.getSeasonYear(),
                competitionA.getName(), UUID.randomUUID(), "Champion FC",
                saveA.managedClubId(), "Managed FC", 1);
        seasonRepository.save(seasonA);
        seasonPlayerStatsRepository.save(SeasonPlayerStats.create(seasonA.getId(), playerB.getId(),
                playerB.getName(), fixtureB.getHomeClubId(), "Some Club", 30, 2700, 10, 5, 12, 3, 0));

        PlayerStatsResponse response = playerStatsService.getStats(saveB.id(), playerB.getId(), userB.getId());

        assertThat(response.seasons()).noneMatch(l -> l.competitionName().equals(competitionA.getName())
                && l.seasonYear() == competitionA.getSeasonYear());
        assertThat(response.seasons()).isEmpty();
        assertThat(response.totals().matchesPlayed()).isZero();
        assertThat(response.totals().goals()).isZero();
    }
}
