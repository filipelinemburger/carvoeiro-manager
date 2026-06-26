package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameResponse;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.StandingRow;
import br.com.elifootcarvoeiro.competition.StandingsService;
import br.com.elifootcarvoeiro.match.LineupService;
import br.com.elifootcarvoeiro.match.LineupRepository;
import br.com.elifootcarvoeiro.match.SetLineupRequest;
import br.com.elifootcarvoeiro.match.SetTacticsRequest;
import br.com.elifootcarvoeiro.match.LineupStarter;
import br.com.elifootcarvoeiro.match.RoundSimulationService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonRolloverServiceIT {

    @Autowired SeasonRolloverService rolloverService;
    @Autowired StandingsService standingsService;
    @Autowired RoundSimulationService roundSimulationService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonStandingRepository seasonStandingRepository;
    @Autowired SeasonTopScorerRepository seasonTopScorerRepository;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("roll_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private void finishSeason(Career c) {
        int totalRounds = competitionRepository.findBySaveGameId(c.id()).get(0).getTotalRounds();
        for (int i = 0; i < totalRounds; i++) {
            roundSimulationService.simulateNextRound(c.id(), c.userId());
        }
    }

    @Test
    void rollingOverArchivesTheSeasonAndStartsAFreshOne() {
        // Given a finished season with a saved managed lineup
        Career c = createCareer();
        List<LineupStarter> xi = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi), c.userId());
        finishSeason(c);

        Competition competition = competitionRepository.findBySaveGameId(c.id()).get(0);
        UUID competitionId = competition.getId();
        int oldYear = competition.getSeasonYear();
        int totalRounds = competition.getTotalRounds();
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        int clubCount = clubs.size();
        List<StandingRow> finalTable = standingsService.getStandings(c.id(), competitionId, c.userId());
        String championName = finalTable.get(0).clubName();
        Map<UUID, Integer> agesBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Player::getId, Player::getAge));
        Map<UUID, Long> squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.groupingBy(Player::getClubId, Collectors.counting()));

        // When rolling over
        RolloverResponse response = rolloverService.rollover(c.id(), c.userId());

        // Then the save advanced to a fresh season
        assertThat(response.seasonYear()).isEqualTo(oldYear + 1);
        assertThat(response.currentRound()).isZero();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        assertThat(save.getSeasonYear()).isEqualTo(oldYear + 1);
        assertThat(save.getCurrentRound()).isZero();
        assertThat(save.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);

        // Competition rolled forward; fixtures regenerated and all unplayed
        Competition rolled = competitionRepository.findBySaveGameId(c.id()).get(0);
        assertThat(rolled.getSeasonYear()).isEqualTo(oldYear + 1);
        assertThat(rolled.getTotalRounds()).isEqualTo(totalRounds);
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), competitionId);
        assertThat(fixtures).hasSize(clubCount * (clubCount - 1));
        assertThat(fixtures).allMatch(f -> !f.isPlayed());
        assertThat(fixtures).allMatch(f -> f.getHomeGoals() == null && f.getAwayGoals() == null);

        // Archive captured the finished season
        List<Season> archived = seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id());
        assertThat(archived).hasSize(1);
        Season season = archived.get(0);
        assertThat(season.getSeasonYear()).isEqualTo(oldYear);
        assertThat(season.getChampionClubName()).isEqualTo(championName);
        assertThat(season.getManagedClubPosition()).isBetween(1, clubCount);
        assertThat(seasonStandingRepository.findBySeasonIdOrderByPosition(season.getId()))
                .hasSize(clubCount)
                .extracting(SeasonStanding::getPosition).first().isEqualTo(1);
        assertThat(seasonTopScorerRepository.findBySeasonIdOrderByRank(season.getId())).isNotEmpty();

        // Players: every club's squad size is unchanged; carried players aged +1 and have clean state
        Map<UUID, Long> squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.groupingBy(Player::getClubId, Collectors.counting()));
        assertThat(squadSizeAfter).isEqualTo(squadSizeBefore);
        List<Player> after = playerRepository.findBySaveGameId(c.id());
        assertThat(after).allMatch(p -> p.getForm() == 0 && p.getYellowCards() == 0
                && p.getSuspensionRemaining() == 0);
        // Carried-over players (still present) are exactly one year older
        Map<UUID, Player> afterById = after.stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        agesBefore.forEach((id, ageBefore) -> {
            Player still = afterById.get(id);
            if (still != null) {
                assertThat(still.getAge()).isEqualTo(ageBefore + 1);
            }
        });

        // Managed lineup was cleared so the user re-picks the XI
        assertThat(lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId())).isEmpty();
    }

    @Test
    void rollingOverAnUnfinishedSeasonIsRejected() {
        // Given a fresh (not finished) career
        Career c = createCareer();

        // When attempting to roll over, then a 422 SEASON_NOT_FINISHED is thrown
        assertThatThrownBy(() -> rolloverService.rollover(c.id(), c.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("SEASON_NOT_FINISHED");
                    assertThat(api.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void foreignUserCannotRollOver() {
        Career owner = createCareer();
        Career intruder = createCareer();
        finishSeason(owner);
        assertThatThrownBy(() -> rolloverService.rollover(owner.id(), intruder.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void rollingOverRetiresAManagedTakerWithoutFkViolation() {
        // Given: a career with a full lineup and a set-piece taker guaranteed to retire
        Career c = createCareer();

        // Build the managed XI and set it as the active lineup
        List<LineupStarter> xi = lineupService.bestXi(c.clubId()).stream()
                .map(p -> new LineupStarter(p.getId(), PlayerRole.defaultFor(p.getPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi), c.userId());

        // Designate the first starter as set-piece taker on all three FK columns
        UUID takerId = xi.get(0).playerId();
        lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", "MEDIUM", "ZONAL", takerId, takerId, takerId),
                c.userId());

        // Force the taker to age 37 so it becomes 38 at rollover (retirement probability == 1.0)
        Player taker = playerRepository.findById(takerId).orElseThrow();
        taker.setAge(37);
        playerRepository.save(taker);

        // Record squad size before rollover so we can verify one-out/one-in invariant
        long squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId().equals(c.clubId()))
                .count();

        // Complete the season so rollover is allowed
        finishSeason(c);

        // When: rolling over (must NOT throw a FK violation)
        rolloverService.rollover(c.id(), c.userId());

        // Then: the retired taker has been deleted
        assertThat(playerRepository.findById(takerId)).isEmpty();

        // And: the managed club's squad size is unchanged (one retired, one newgen added)
        long squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId().equals(c.clubId()))
                .count();
        assertThat(squadSizeAfter).isEqualTo(squadSizeBefore);

        // And: the managed lineup was cleared so the user must re-pick after rollover
        assertThat(lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId())).isEmpty();
    }
}
