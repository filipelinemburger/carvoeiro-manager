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
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.TieResolver;
import br.com.carvoeiromanager.competition.TopScorerRow;
import br.com.carvoeiromanager.competition.TopScorersService;
import br.com.carvoeiromanager.match.FixtureAppearance;
import br.com.carvoeiromanager.match.FixtureAppearanceRepository;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.match.SetLineupRequest;
import br.com.carvoeiromanager.match.SetTacticsRequest;
import br.com.carvoeiromanager.match.LineupStarter;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.TrainingIntensity;
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
    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired LineupService lineupService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired LineupRepository lineupRepository;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SeasonStandingRepository seasonStandingRepository;
    @Autowired SeasonTopScorerRepository seasonTopScorerRepository;
    @Autowired FixtureAppearanceRepository fixtureAppearanceRepository;
    @Autowired SeasonPlayerStatsRepository seasonPlayerStatsRepository;
    @Autowired TopScorersService topScorersService;

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
        List<LineupStarter> xi = lineupService.bestXi(c.clubId(), null, null).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi, null), c.userId());
        finishSeason(c);

        // Scope to Catarinense: 12 members / 17 match-units; Criciúma (managed) is in both competitions
        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        UUID competitionId = competition.getId();
        int oldYear = competition.getSeasonYear();
        int totalRounds = competition.getTotalRounds();
        int clubCount = 12; // Catarinense member count
        List<Club> clubs = clubRepository.findBySaveGameId(c.id());
        // Champion is the knockout winner (Task 10), not the table leader — read it straight off
        // the final's tie, exactly like SeasonAdvanceServiceIT does, before rollover purges fixtures.
        List<CompetitionPhase> catarinensePhases = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(competitionId);
        CompetitionPhase finalPhase = catarinensePhases.get(catarinensePhases.size() - 1);
        List<Fixture> finalFixtures = fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), finalPhase.getId());
        UUID championClubId = TieResolver.winner(TieResolver.tiesOf(finalFixtures).get(0));
        String championName = clubs.stream()
                .filter(cl -> cl.getId().equals(championClubId))
                .findFirst().orElseThrow().getName();
        Map<UUID, Integer> agesBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .collect(Collectors.toMap(Player::getId, Player::getAge));
        // Contracts must not interfere with the retirement/newgen invariants under test.
        List<Player> allPlayers = playerRepository.findBySaveGameId(c.id());
        allPlayers.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(allPlayers);

        // Count only senior (non-prospect) AI-club players; the managed club is excluded because
        // managed retirees are no longer replaced by newgens (squad size can legitimately shrink).
        Map<UUID, Long> squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null && !p.isYouthProspect() && !p.getClubId().equals(c.clubId()))
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
        // Only the round-robin first phase materializes fixtures; the knockout phases stay
        // reserved until Task 6's PhaseResolver runs.
        assertThat(fixtures).hasSize(clubCount * (clubCount - 1) / 2); // 12 * 11 / 2 = 66
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
        // Both seeded test competitions are ones the player is IN, so the managed triple must stay
        // fully populated — the regression that matters now that background rows archive it null.
        assertThat(catSeason.getManagedClubPosition()).isNotNull().isBetween(1, clubCount);
        assertThat(catSeason.getManagedClubId()).isEqualTo(c.clubId());
        assertThat(catSeason.getManagedClubName()).isNotBlank();
        assertThat(seasonStandingRepository.findBySeasonIdOrderByPosition(catSeason.getId()))
                .hasSize(clubCount)
                .extracting(SeasonStanding::getPosition).first().isEqualTo(1);
        assertThat(seasonTopScorerRepository.findBySeasonIdOrderByRank(catSeason.getId())).isNotEmpty();

        // AI clubs' senior squad sizes should be unchanged (newgen-on-retirement still applies to them).
        Map<UUID, Long> squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> p.getClubId() != null && !p.isYouthProspect() && !p.getClubId().equals(c.clubId()))
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
                    assertThat(api.getCode()).isEqualTo(ErrorCode.SEASON_NOT_FINISHED);
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
        List<LineupStarter> xi = lineupService.bestXi(c.clubId(), null, null).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi, null), c.userId());

        // Designate the first starter as set-piece taker on all three FK columns
        UUID takerId = xi.get(0).playerId();
        lineupService.setTactics(c.id(), c.clubId(),
                new SetTacticsRequest("BALANCED", null, null, "MEDIUM", "ZONAL", takerId, takerId, takerId),
                c.userId());

        // Force the taker to age 37 so it becomes 38 at rollover (retirement probability == 1.0)
        Player taker = playerRepository.findById(takerId).orElseThrow();
        taker.setAge(37);
        playerRepository.save(taker);

        // Complete the season so rollover is allowed
        finishSeason(c);

        // Contracts must not interfere with the retirement/newgen invariants under test.
        // Pin all non-taker managed players to age 20 so only the forced retiree retires;
        // this isolates the one-out/no-replacement assertion under the new managed behavior.
        List<Player> allPlayersBeforeRollover = playerRepository.findBySaveGameId(c.id());
        allPlayersBeforeRollover.forEach(p -> {
            p.setContractYears(5);
            if (c.clubId().equals(p.getClubId()) && !p.getId().equals(takerId)) {
                p.setAge(20);
            }
        });
        playerRepository.saveAll(allPlayersBeforeRollover);

        // Record squad size (senior only) right before rollover — after age pinning.
        long squadSizeBefore = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> c.clubId().equals(p.getClubId()) && !p.isYouthProspect())
                .count();

        // When: rolling over (must NOT throw a FK violation)
        rolloverService.rollover(c.id(), c.userId());

        // Then: the retired taker has been deleted
        assertThat(playerRepository.findById(takerId)).isEmpty();

        // And: the managed club's senior squad shrank by exactly one (the taker; no newgen added).
        long squadSizeAfter = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> c.clubId().equals(p.getClubId()) && !p.isYouthProspect())
                .count();
        assertThat(squadSizeAfter).isEqualTo(squadSizeBefore - 1);

        // And: the managed lineup was cleared so the user must re-pick after rollover
        assertThat(lineupRepository.findBySaveGameIdAndClubId(c.id(), c.clubId())).isEmpty();
    }

    @Test
    void rolloverArchivesSeasonPlayerStatsAndPurgesFixtureAppearance() {
        Career c = createCareer();
        List<LineupStarter> xi = lineupService.bestXi(c.clubId(), null, null).stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .toList();
        lineupService.setLineup(c.id(), c.clubId(), new SetLineupRequest("4-3-3", xi, null), c.userId());

        Competition competition = competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionId(c.id(), competition.getId()).get(0);
        Player player = playerRepository.findByClubId(fixture.getHomeClubId()).get(0);
        fixtureAppearanceRepository.save(FixtureAppearance.create(
                fixture.getId(), player.getId(), fixture.getHomeClubId(), 90, 5));

        finishSeason(c);
        rolloverService.rollover(c.id(), c.userId());

        List<SeasonPlayerStats> archived = seasonPlayerStatsRepository.findByPlayerId(player.getId());
        assertThat(archived).isNotEmpty();
        assertThat(archived.get(0).getMinutesPlayed()).isGreaterThanOrEqualTo(90);

        assertThat(fixtureAppearanceRepository.findByPlayerId(player.getId())).isEmpty();
    }

    @Test
    void aTrainingAssignmentSurvivesRolloverAndTheProgressMeterResets() {
        // Given a managed player mid-way toward his next evolution. Training no longer applies at
        // rollover (it is a weekly in-season tick — see TrainingService.processWeek); rollover's
        // only training-related contract is: the standing assignment persists, the meter resets.
        Career c = createCareer();
        Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        trainee.setAge(24);
        trainee.setContractYears(5);
        trainee.setTrainingAssignment(Attribute.FINALIZACAO, TrainingIntensity.INTENSA);
        trainee.setTrainingProgress(25);
        playerRepository.save(trainee);

        List<Player> others = playerRepository.findBySaveGameId(c.id()).stream()
                .filter(p -> !p.getId().equals(trainee.getId())).toList();
        others.forEach(p -> p.setContractYears(5));
        playerRepository.saveAll(others);

        finishSeason(c);

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then the assignment is untouched and the partial progress was zeroed
        Player after = playerRepository.findById(trainee.getId()).orElseThrow();
        assertThat(after.getTrainingFocus()).isEqualTo(Attribute.FINALIZACAO);
        assertThat(after.getTrainingIntensity()).isEqualTo(TrainingIntensity.INTENSA);
        assertThat(after.getTrainingProgress()).isZero();
    }

    @Test
    void aManagedPlayerWithNoFocusKeepsItNullAfterRollover() {
        // Given a career whose managed players are never given a training assignment
        Career c = createCareer();

        finishSeason(c);

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then no managed-club player was auto-assigned a training focus
        List<Player> managed = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId());
        assertThat(managed).allMatch(p -> p.getTrainingFocus() == null);
    }

    /**
     * Picks the Catarinense's leading scorer who still has a club, so the release below is
     * deterministic rather than depending on which player a probe happens to select.
     */
    private TopScorerRow leadingClubbedScorer(Career c, Competition competition) {
        return topScorersService.getTopScorers(c.id(), competition.getId(), c.userId()).stream()
                .filter(row -> row.clubId() != null)
                .findFirst().orElseThrow();
    }

    private Competition catarinense(Career c) {
        return competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> comp.getName().contains("Catarinense"))
                .findFirst().orElseThrow();
    }

    private Season archivedCatarinense(Career c) {
        return seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id()).stream()
                .filter(s -> s.getCompetitionName().contains("Catarinense"))
                .findFirst().orElseThrow();
    }

    @Test
    void aTopScorerReleasedBeforeRolloverIsArchivedUnderTheClubThatReleasedHim() {
        // Given a finished season whose leading Catarinense scorer is then released mid-table:
        // he is club-less when the archive runs, which used to violate season_top_scorer's
        // NOT NULL club_id and abort the whole rollover.
        Career c = createCareer();
        finishSeason(c);
        Competition competition = catarinense(c);
        TopScorerRow top = leadingClubbedScorer(c, competition);
        Player scorer = playerRepository.findById(top.playerId()).orElseThrow();
        UUID releasingClubId = scorer.getClubId();
        String releasingClubName = clubRepository.findById(releasingClubId).orElseThrow().getName();
        scorer.releaseFrom(releasingClubId);
        playerRepository.save(scorer);
        assertThat(playerRepository.findById(top.playerId()).orElseThrow().isFreeAgent()).isTrue();

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then the rollover completed and history credits the goals to the club he scored them for
        Season catSeason = archivedCatarinense(c);
        SeasonTopScorer archived = seasonTopScorerRepository
                .findBySeasonIdOrderByRank(catSeason.getId()).stream()
                .filter(row -> row.getPlayerId().equals(top.playerId()))
                .findFirst().orElseThrow();
        assertThat(archived.getClubId()).isEqualTo(releasingClubId);
        assertThat(archived.getClubName()).isEqualTo(releasingClubName);
        assertThat(archived.getGoals()).isEqualTo(top.goals());
        assertThat(archived.getPlayerName()).isEqualTo(top.playerName());
    }

    @Test
    void aClublessScorerWithNoReleaseMarkIsOmittedFromTheArchiveInsteadOfBreakingTheRollover() {
        // Given a finished season whose leading scorer is club-less with NO release mark — not
        // reachable through gameplay today, but the archive must degrade instead of crashing.
        Career c = createCareer();
        finishSeason(c);
        Competition competition = catarinense(c);
        List<TopScorerRow> before = topScorersService.getTopScorers(c.id(), competition.getId(), c.userId());
        TopScorerRow orphan = before.stream().filter(row -> row.clubId() != null).findFirst().orElseThrow();
        Player scorer = playerRepository.findById(orphan.playerId()).orElseThrow();
        scorer.setClubId(null);
        playerRepository.save(scorer);
        assertThat(playerRepository.findById(orphan.playerId()).orElseThrow().getReleasedByClubId()).isNull();

        // When rolling over
        rolloverService.rollover(c.id(), c.userId());

        // Then the rollover completed, the unattributable row was dropped, and ranks stayed dense
        Season catSeason = archivedCatarinense(c);
        List<SeasonTopScorer> archived = seasonTopScorerRepository.findBySeasonIdOrderByRank(catSeason.getId());
        assertThat(archived).extracting(SeasonTopScorer::getPlayerId).doesNotContain(orphan.playerId());
        assertThat(archived).allMatch(row -> row.getClubId() != null);
        assertThat(archived).extracting(SeasonTopScorer::getRank)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, archived.size())
                        .boxed().toList());
    }
}
