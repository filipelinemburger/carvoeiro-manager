package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.match.SetTacticsRequest;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
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
    @Autowired SeasonAdvanceService seasonAdvanceService;
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
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
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

        // Scope to Catarinense: 10 members / 18 rounds; Criciúma (managed) is in both competitions
        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        UUID competitionId = competition.getId();
        int oldYear = competition.getSeasonYear();
        int totalRounds = competition.getTotalRounds();
        int clubCount = 10; // Catarinense member count
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        List<StandingRow> finalTable = standingsService.getStandings(c.id(), competitionId, c.userId());
        String championName = finalTable.get(0).clubName();
        Map<UUID, Integer> agesBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Player::getId, Player::getAge));
        // Contracts must not interfere with the retirement/newgen invariants under test.
        List<Player> allPlayers = playerRepository.findBySaveGameId(c.id());
        allPlayers.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(allPlayers);

        Map<UUID, Long> squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null)
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

        // Competition rolled forward; fixtures regenerated and all unplayed (scoped to Catarinense)
        Competition rolled = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        assertThat(rolled.getSeasonYear()).isEqualTo(oldYear + 1);
        assertThat(rolled.getTotalRounds()).isEqualTo(totalRounds);
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), competitionId);
        assertThat(fixtures).hasSize(clubCount * (clubCount - 1)); // 10 * 9 = 90
        assertThat(fixtures).allMatch(f -> !f.isPlayed());
        assertThat(fixtures).allMatch(f -> f.getHomeGoals() == null && f.getAwayGoals() == null);

        // Archive captured the finished season (one archive entry per competition, so 2 total)
        List<Season> archived = seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id());
        assertThat(archived).hasSize(2); // Catarinense + Série B
        Season catSeason = archived.stream()
                .filter(s -> s.getCompetitionName().contains("Catarinense"))
                .findFirst().orElseThrow();
        assertThat(catSeason.getSeasonYear()).isEqualTo(oldYear);
        assertThat(catSeason.getChampionClubName()).isEqualTo(championName);
        assertThat(catSeason.getManagedClubPosition()).isBetween(1, clubCount);
        assertThat(seasonStandingRepository.findBySeasonIdOrderByPosition(catSeason.getId()))
                .hasSize(clubCount)
                .extracting(SeasonStanding::getPosition).first().isEqualTo(1);
        assertThat(seasonTopScorerRepository.findBySeasonIdOrderByRank(catSeason.getId())).isNotEmpty();

        // Players: every club's squad size is unchanged; carried players aged +1 and have clean state
        Map<UUID, Long> squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null)
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
                .filter(p -> p.getClubId() != null && p.getClubId().equals(c.clubId()))
                .count();

        // Complete the season so rollover is allowed
        finishSeason(c);

        // Contracts must not interfere with the retirement/newgen invariants under test.
        List<Player> allPlayersBeforeRollover = playerRepository.findBySaveGameId(c.id());
        allPlayersBeforeRollover.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(allPlayersBeforeRollover);

        // When: rolling over (must NOT throw a FK violation)
        rolloverService.rollover(c.id(), c.userId());

        // Then: the retired taker has been deleted
        assertThat(playerRepository.findById(takerId)).isEmpty();

        // And: the managed club's squad size is unchanged (one retired, one newgen added)
        long squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> c.clubId().equals(p.getClubId()))
                .count();
        assertThat(squadSizeAfter).isEqualTo(squadSizeBefore);

        // And: the managed lineup was cleared so the user must re-pick after rollover
        assertThat(lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId())).isEmpty();
    }
}
