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
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.KnockoutTie;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.competition.TieResolver;
import br.com.carvoeiromanager.match.LineupService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Background competitions — divisions the managed club has no {@code competition_club} row in —
 * are simulated silently as the calendar passes their dates, and never surface through the advance.
 *
 * <p>The seed carries no background competition yet (Série A arrives in a later slice), so each
 * test builds one: a 20-club league drawn from the save's own seeded clubs, minus the managed club.
 * Reusing seeded clubs rather than bare rows matters — the resolver really simulates these rounds,
 * so the clubs need squads and finances.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class BackgroundCompetitionIT {

    private static final int BACKGROUND_CLUB_COUNT = 20;
    private static final String BACKGROUND_COMPETITION_NAME = "Background League";
    /** Covers a whole season's calendar (managed rounds + transfer stops) with room to spare. */
    private static final int ADVANCE_GUARD = 500;

    // A phased background competition, shaped like Série C but small enough to sweep in one call:
    // 8 clubs, a single round-robin first phase, then a two-legged semifinal and final.
    private static final String PHASED_COMPETITION_NAME = "Background Cup";
    private static final int PHASED_CLUB_COUNT = 8;
    private static final int PHASED_QUALIFIERS = 4;
    private static final int PHASED_FIRST_PHASE_ROUNDS = PHASED_CLUB_COUNT - 1;
    private static final int PHASED_KNOCKOUT_ROUNDS_PER_PHASE = 2;
    /** 7 first-phase rounds + 2 semifinal legs + 2 final legs. */
    private static final int PHASED_UNITS =
            PHASED_FIRST_PHASE_ROUNDS + 2 * PHASED_KNOCKOUT_ROUNDS_PER_PHASE;
    private static final int PHASED_SEMIFINAL_FIXTURES = 4; // 2 ties x 2 legs
    private static final int PHASED_FINAL_FIXTURES = 2;     // 1 tie x 2 legs
    /** 7 x 4 first-phase fixtures, plus the bracket's 6. */
    private static final int PHASED_TOTAL_FIXTURES =
            PHASED_FIRST_PHASE_ROUNDS * (PHASED_CLUB_COUNT / 2)
                    + PHASED_SEMIFINAL_FIXTURES + PHASED_FINAL_FIXTURES;

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired StandingsService standingsService;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired LineupService lineupService;
    @Autowired SeasonRolloverService seasonRolloverService;
    @Autowired BackgroundCompetitionService backgroundCompetitionService;
    @Autowired SeasonCalendarGenerator seasonCalendarGenerator;
    @Autowired SeasonRepository seasonRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    private record Career(UUID userId, UUID id, UUID backgroundCompetitionId) { }

    /**
     * A fresh career plus one background competition, with the whole calendar regenerated so the
     * background rounds are dated across the managed season's window exactly as a seeded one
     * would be.
     */
    private Career createCareer() {
        User user = User.create("background_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();
        UUID managedClubId = saveGameRepository.findById(saveGameId).orElseThrow().getManagedClubId();

        // A league the managed club is not a member of — that absence is the whole definition of
        // "background". Ordered by short name so the field (and therefore the schedule) is stable.
        List<Club> members = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(club -> !club.getId().equals(managedClubId))
                .sorted(Comparator.comparing(Club::getShortName))
                .limit(BACKGROUND_CLUB_COUNT)
                .toList();
        assertThat(members).hasSize(BACKGROUND_CLUB_COUNT);
        Competition background = Competition.create(saveGameId, BACKGROUND_COMPETITION_NAME, "LEAGUE",
                response.seasonYear(), 2 * (BACKGROUND_CLUB_COUNT - 1));
        competitionRepository.save(background);
        members.forEach(club -> competitionClubRepository.save(
                CompetitionClub.create(saveGameId, background.getId(), club.getId())));

        // createCareer already generated a calendar without this competition; throw it away and
        // regenerate so managed and background rounds are dated together.
        fixtureRepository.deleteAll(fixtureRepository.findBySaveGameId(saveGameId));
        seasonEventRepository.deleteAll(seasonEventRepository.findBySaveGameId(saveGameId));
        seasonCalendarGenerator.generate(saveGameId, response.seasonYear(),
                competitionRepository.findBySaveGameId(saveGameId), managedClubId);

        return new Career(user.getId(), saveGameId, background.getId());
    }

    /**
     * A fresh career plus one <em>phased</em> background competition — the shape Série C has and
     * the shape nothing exercised before: a competition the player is not in whose knockout rounds
     * are reserved (dated, fixture-less) at generation and can only be filled in by
     * {@link br.com.carvoeiromanager.competition.PhaseResolver}.
     */
    private Career createPhasedCareer() {
        User user = User.create("phased_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse response = saveGameService.createCareer(user.getId());
        UUID saveGameId = response.id();
        UUID managedClubId = saveGameRepository.findById(saveGameId).orElseThrow().getManagedClubId();

        List<Club> members = clubRepository.findBySaveGameId(saveGameId).stream()
                .filter(club -> !club.getId().equals(managedClubId))
                .sorted(Comparator.comparing(Club::getShortName))
                .limit(PHASED_CLUB_COUNT)
                .toList();
        assertThat(members).hasSize(PHASED_CLUB_COUNT);

        Competition cup = Competition.create(saveGameId, PHASED_COMPETITION_NAME, "CUP",
                response.seasonYear(), PHASED_UNITS);
        competitionRepository.save(cup);
        members.forEach(club -> competitionClubRepository.save(
                CompetitionClub.create(saveGameId, cup.getId(), club.getId())));

        competitionPhaseRepository.save(CompetitionPhase.create(saveGameId, cup.getId(), 0,
                "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, PHASED_FIRST_PHASE_ROUNDS, PHASED_QUALIFIERS));
        competitionPhaseRepository.save(CompetitionPhase.create(saveGameId, cup.getId(), 1,
                "Semifinal", PhaseKind.KNOCKOUT, 2, PHASED_KNOCKOUT_ROUNDS_PER_PHASE, null));
        competitionPhaseRepository.save(CompetitionPhase.create(saveGameId, cup.getId(), 2,
                "Final", PhaseKind.KNOCKOUT, 2, PHASED_KNOCKOUT_ROUNDS_PER_PHASE, null));

        // As in createCareer: throw away the calendar built before this competition existed and
        // regenerate, so its rounds are dated across the managed season's window.
        fixtureRepository.deleteAll(fixtureRepository.findBySaveGameId(saveGameId));
        seasonEventRepository.deleteAll(seasonEventRepository.findBySaveGameId(saveGameId));
        seasonCalendarGenerator.generate(saveGameId, response.seasonYear(),
                competitionRepository.findBySaveGameId(saveGameId), managedClubId);

        return new Career(user.getId(), saveGameId, cup.getId());
    }

    private List<SeasonEvent> backgroundMatchEvents(Career c) {
        return seasonEventRepository.findBySaveGameId(c.id()).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> c.backgroundCompetitionId().equals(e.getCompetitionId()))
                .toList();
    }

    /**
     * Everything the player is present for: their own rounds plus every TRANSFER stop.
     *
     * <p>Derived from membership rather than "not the competition this test built", because the
     * seed now ships Série A as a background competition too — an exclusion list of one would
     * silently count Série A's rounds as the player's and make the guard below vacuous.
     */
    private List<SeasonEvent> managedEvents(Career c) {
        UUID managedClubId = saveGameRepository.findById(c.id()).orElseThrow().getManagedClubId();
        return seasonEventRepository.findBySaveGameId(c.id()).stream()
                .filter(e -> e.getType() != SeasonEventType.MATCH
                        || backgroundCompetitionService.isManaged(e.getCompetitionId(), managedClubId))
                .toList();
    }

    /**
     * Every fixture of every competition the player is not in — this test's own Background League
     * and the seeded Série A alike. Scoping this to one competition would let a leak from the
     * other slip through unnoticed.
     */
    private Set<UUID> backgroundFixtureIds(Career c) {
        UUID managedClubId = saveGameRepository.findById(c.id()).orElseThrow().getManagedClubId();
        return competitionRepository.findBySaveGameId(c.id()).stream()
                .filter(comp -> !backgroundCompetitionService.isManaged(comp.getId(), managedClubId))
                .flatMap(comp -> fixtureRepository
                        .findBySaveGameIdAndCompetitionId(c.id(), comp.getId()).stream())
                .map(Fixture::getId)
                .collect(Collectors.toSet());
    }

    /** Drives the career all the way to FINISHED, failing rather than looping forever. */
    private void advanceToFinish(Career c) {
        int advances = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && advances++ < ADVANCE_GUARD) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        assertThat(advances).isLessThan(ADVANCE_GUARD);
    }

    @Test
    void anAdvanceResolvesEveryBackgroundRoundDueByItsDate() {
        Career c = createCareer();
        seasonAdvanceService.advance(c.id(), c.userId());

        LocalDate now = saveGameRepository.findById(c.id()).orElseThrow().getCurrentDate();
        List<SeasonEvent> backgroundEvents = backgroundMatchEvents(c);

        assertThat(backgroundEvents.stream().filter(e -> !e.getEventDate().isAfter(now)))
                .isNotEmpty()
                .allMatch(SeasonEvent::isProcessed);
        assertThat(backgroundEvents.stream().filter(e -> e.getEventDate().isAfter(now)))
                .isNotEmpty()
                .noneMatch(SeasonEvent::isProcessed);
    }

    @Test
    void backgroundResultsNeverAppearInTheAdvanceResponse() {
        Career c = createCareer();
        AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());

        // The advance stops only on the player's own matches. Every result it reports must belong
        // to a competition the managed club plays in.
        Set<UUID> backgroundFixtureIds = backgroundFixtureIds(c);
        assertThat(r.results()).isNotEmpty();
        assertThat(r.results()).noneMatch(res -> backgroundFixtureIds.contains(res.fixtureId()));
    }

    /**
     * The leak this guards against is positional, so a single advance cannot see it: managed
     * rounds land on multiples of five days while background rounds are spread proportionally
     * (0, 7, 15, 22 ...), so the first background event the "next event" selector could wrongly
     * pop sits between the FIRST and SECOND managed matchday. Driving the career forward is the
     * only way to reach it — hence a whole season here rather than one advance.
     */
    @Test
    void noAdvanceAnywhereInTheSeasonStopsOnABackgroundRound() {
        Career c = createCareer();
        Set<UUID> backgroundFixtureIds = backgroundFixtureIds(c);

        // Guard against a vacuous pass: the calendar must actually contain a background round
        // dated on a day the managed calendar has nothing of its own on. Without one, the
        // unscoped selector would never have leaked and this test would prove nothing.
        Set<LocalDate> managedDates = managedEvents(c).stream()
                .map(SeasonEvent::getEventDate).collect(Collectors.toSet());
        LocalDate lastManagedDate = managedDates.stream().max(LocalDate::compareTo).orElseThrow();
        assertThat(backgroundMatchEvents(c).stream()
                .map(SeasonEvent::getEventDate)
                .filter(d -> !d.isAfter(lastManagedDate))
                .filter(d -> !managedDates.contains(d)))
                .isNotEmpty();

        int advances = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && advances++ < ADVANCE_GUARD) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());

            assertThat(r.competitionName()).isNotEqualTo(BACKGROUND_COMPETITION_NAME);
            assertThat(r.results()).noneMatch(res -> backgroundFixtureIds.contains(res.fixtureId()));
        }

        assertThat(advances).isLessThan(ADVANCE_GUARD);
    }

    /**
     * The terminal path: {@code advance()} now skips background events entirely, so the save flips
     * to FINISHED on the last managed matchday while background rounds are, in principle, still
     * on the queue. That is safe only because the calendar pins the last background round exactly
     * on the managed finale's date, so the {@code resolveDue} call inside that final advance
     * drains all of them.
     *
     * <p>The drain is asserted directly, at the bottom: every background event processed, every
     * background fixture played, none dated past the finale. Those are the load-bearing assertions —
     * {@code noMoreMatchEvents} is scoped to managed competitions, so the season would now finish on
     * the finale whether or not the background rounds had been drained, and {@code last.type()}
     * being MATCH no longer proves anything about them.
     */
    @Test
    void theFinalManagedMatchdayDrainsEveryRemainingBackgroundRound() {
        Career c = createCareer();

        AdvanceResponse last = null;
        int advances = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && advances++ < ADVANCE_GUARD) {
            last = seasonAdvanceService.advance(c.id(), c.userId());
        }

        assertThat(advances).isLessThan(ADVANCE_GUARD);
        assertThat(saveGameRepository.findById(c.id()).orElseThrow().getStatus())
                .isEqualTo(SaveGameStatus.FINISHED);
        assertThat(last).isNotNull();
        assertThat(last.type()).isEqualTo("MATCH");
        assertThat(last.seasonFinished()).isTrue();
        assertThat(last.finance().prize()).isGreaterThan(BigDecimal.ZERO);

        // No background round outlives the finale — neither as an event nor as an unplayed fixture.
        LocalDate finale = saveGameRepository.findById(c.id()).orElseThrow().getCurrentDate();
        assertThat(backgroundMatchEvents(c))
                .isNotEmpty()
                .allMatch(SeasonEvent::isProcessed)
                .allMatch(e -> !e.getEventDate().isAfter(finale));
        assertThat(fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), c.backgroundCompetitionId()))
                .isNotEmpty()
                .allMatch(Fixture::isPlayed);
    }

    /**
     * Rollover has to survive a save containing a competition the player is not in. The background
     * competition still gets rebuilt like any other — skipping its archive must not also skip the
     * purge of last season's played fixtures, or the new season would start with stale results.
     */
    @Test
    void aSeasonWithABackgroundCompetitionStillRollsOver() {
        Career c = createCareer();
        advanceToFinish(c);

        seasonRolloverService.rollover(c.id(), c.userId());

        assertThat(saveGameRepository.findById(c.id()).orElseThrow().getStatus())
                .isEqualTo(SaveGameStatus.IN_PROGRESS);
        assertThat(fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), c.backgroundCompetitionId()))
                .isNotEmpty()
                .noneMatch(Fixture::isPlayed);
    }

    /**
     * The season history is the player's career record. A competition they had no club in has no
     * champion-and-my-position story to tell — and {@code Season.managedClubPosition} is NOT NULL
     * with no honest value to put there — so no row is archived for it at all.
     */
    @Test
    void noSeasonRowIsArchivedForABackgroundCompetition() {
        Career c = createCareer();
        advanceToFinish(c);

        seasonRolloverService.rollover(c.id(), c.userId());

        // NOTE: SeasonRepository has no plain findBySaveGameId -- use the ordered finder.
        assertThat(seasonRepository.findBySaveGameIdOrderBySeasonYearDesc(c.id()))
                .isNotEmpty()
                .noneMatch(s -> s.getCompetitionId().equals(c.backgroundCompetitionId()));
    }

    @Test
    void aResolvedBackgroundRoundIsActuallyPlayed() {
        Career c = createCareer();
        seasonAdvanceService.advance(c.id(), c.userId());

        List<Fixture> round0 = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                c.id(), c.backgroundCompetitionId(), 0);
        assertThat(round0).hasSize(BACKGROUND_CLUB_COUNT / 2).allMatch(Fixture::isPlayed);
        assertThat(round0).allSatisfy(f -> {
            assertThat(f.getHomeGoals()).isNotNull();
            assertThat(f.getAwayGoals()).isNotNull();
        });
    }

    /**
     * A phased background competition must play its knockout phases, not just its round-robin.
     *
     * <p>Before {@code resolveDue} called {@link br.com.carvoeiromanager.competition.PhaseResolver},
     * a competition the player was not in had its reserved knockout rounds marked processed with
     * zero fixtures: the bracket never materialized, so there was no champion and nothing for the
     * promotion slice to read. {@code PhaseResolver} had exactly one production call site and it
     * was passed the MANAGED event.
     *
     * <p>The whole calendar is swept in a <b>single</b> {@code resolveDue} call on purpose. That
     * puts the last first-phase round and every reserved knockout round in one {@code due} list
     * inside one transaction, which is what pins the two things the fix depends on:
     * <ul>
     *   <li><b>ordering</b> — the (date, competition, round) sort must place each phase's rounds
     *       ahead of the reserved rounds of the phase after it, so materialization happens before
     *       the loop reaches the round that needs the fixtures;</li>
     *   <li><b>flush visibility</b> — the fixtures {@code PhaseResolver} persists must be found by
     *       the next iteration's {@code findBySaveGameIdAndCompetitionIdAndRound} in the same
     *       transaction.</li>
     * </ul>
     * Driving the season advance by advance would exercise neither: the proportional dating spaces
     * these rounds days apart, so each would land in its own call and its own transaction.
     */
    @Test
    void oneSweepMaterializesAndPlaysAPhasedBackgroundCompetitionsWholeBracket() {
        Career c = createPhasedCareer();
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        LocalDate lastBackgroundDate = backgroundMatchEvents(c).stream()
                .map(SeasonEvent::getEventDate)
                .max(LocalDate::compareTo).orElseThrow();

        int resolved = backgroundCompetitionService.resolveDue(save, lastBackgroundDate);

        // Every round of this competition, first phase and bracket alike. (The trimmed test seed
        // ships no other background division, so this count is exactly its own.)
        assertThat(resolved).isEqualTo(PHASED_UNITS);

        List<CompetitionPhase> phases =
                competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(c.backgroundCompetitionId());
        assertThat(phases).hasSize(3);

        List<Fixture> all = fixtureRepository
                .findBySaveGameIdAndCompetitionId(c.id(), c.backgroundCompetitionId());
        assertThat(all).hasSize(PHASED_TOTAL_FIXTURES).allMatch(Fixture::isPlayed);

        List<Fixture> semis = fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(1).getId());
        List<Fixture> finalLegs = fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(2).getId());
        assertThat(semis).hasSize(PHASED_SEMIFINAL_FIXTURES).allMatch(Fixture::isPlayed);
        assertThat(finalLegs).hasSize(PHASED_FINAL_FIXTURES).allMatch(Fixture::isPlayed);

        // The bracket is seeded from the first phase's table, not from an arbitrary set of clubs.
        List<StandingRow> table = standingsService.computeStandings(
                save, c.backgroundCompetitionId(), phases.get(0).getId(), null);
        assertThat(table).hasSize(PHASED_CLUB_COUNT);
        Set<UUID> semifinalists = semis.stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getHomeClubId(), f.getAwayClubId()))
                .collect(Collectors.toSet());
        assertThat(semifinalists).isEqualTo(table.subList(0, PHASED_QUALIFIERS).stream()
                .map(StandingRow::clubId).collect(Collectors.toSet()));

        // ...and the chain ends in a decided champion, which is what promotion will read.
        List<KnockoutTie> finalTies = TieResolver.tiesOf(finalLegs);
        assertThat(finalTies).hasSize(1);
        UUID champion = TieResolver.winner(finalTies.get(0));
        assertThat(champion).isIn(finalLegs.get(0).getHomeClubId(), finalLegs.get(0).getAwayClubId());

        // Still idempotent with phase resolution in the loop: nothing due, nothing re-materialized.
        assertThat(backgroundCompetitionService.resolveDue(save, lastBackgroundDate)).isZero();
        assertThat(fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), c.backgroundCompetitionId()))
                .hasSize(PHASED_TOTAL_FIXTURES);
    }

    /**
     * A phased background competition must survive rollover: season 2 has to reserve its knockout
     * rounds again and materialize a fresh bracket from the new season's table.
     *
     * <p>Nothing covered this. Both full-seed ITs stop at FINISHED, and every {@code *RolloverIT}
     * runs on the trimmed test seed, which ships no phased background division at all. The
     * mechanism is indirect enough to be worth pinning: rollover purges fixtures and season events
     * but leaves {@code competition_phase} rows alone, and {@code SeasonCalendarGenerator} reads
     * those live rows — so the reservation happens again only because the phases were never
     * transient in the first place. A rollover that "cleaned up" phases along with the fixtures
     * would leave a competition that quietly degrades into a bare round-robin in season 2, with no
     * champion for promotion to read and nothing failing loudly.
     */
    @Test
    void aPhasedBackgroundCompetitionsBracketReMaterializesAfterRollover() {
        // Given a season in which the whole bracket was played out
        Career c = createPhasedCareer();
        advanceToFinish(c);
        List<CompetitionPhase> phases =
                competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(c.backgroundCompetitionId());
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(2).getId()))
                .as("season 1 must have produced a final for this test to be about season 2")
                .hasSize(PHASED_FINAL_FIXTURES);

        // When the season rolls over
        seasonRolloverService.rollover(c.id(), c.userId());

        // Then season 2 opens with the phases intact and the knockout rounds reserved again:
        // dated, unprocessed, fixture-less — the state PhaseResolver is the only thing that fills.
        // The SAME phase rows, not a rebuilt set — every fixture lookup below uses the ids
        // captured before rollover, and identical ids are what makes those lookups meaningful.
        assertThat(competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(c.backgroundCompetitionId()))
                .extracting(CompetitionPhase::getId)
                .containsExactlyElementsOf(phases.stream().map(CompetitionPhase::getId).toList());
        assertThat(backgroundMatchEvents(c))
                .hasSize(PHASED_UNITS)
                .noneMatch(SeasonEvent::isProcessed);
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(1).getId())).isEmpty();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(2).getId())).isEmpty();

        // And sweeping the new season rebuilds and plays the bracket exactly as season 1 did.
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();
        LocalDate lastBackgroundDate = backgroundMatchEvents(c).stream()
                .map(SeasonEvent::getEventDate).max(LocalDate::compareTo).orElseThrow();
        assertThat(backgroundCompetitionService.resolveDue(save, lastBackgroundDate))
                .isEqualTo(PHASED_UNITS);

        assertThat(fixtureRepository.findBySaveGameIdAndCompetitionId(c.id(), c.backgroundCompetitionId()))
                .hasSize(PHASED_TOTAL_FIXTURES)
                .allMatch(Fixture::isPlayed);

        // Seeded from season 2's own table, not from a stale season-1 one.
        List<Fixture> semis = fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(1).getId());
        List<StandingRow> table = standingsService.computeStandings(
                save, c.backgroundCompetitionId(), phases.get(0).getId(), null);
        Set<UUID> semifinalists = semis.stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getHomeClubId(), f.getAwayClubId()))
                .collect(Collectors.toSet());
        assertThat(semifinalists).isEqualTo(table.subList(0, PHASED_QUALIFIERS).stream()
                .map(StandingRow::clubId).collect(Collectors.toSet()));

        // ...and ends in a decided champion again.
        List<Fixture> finalLegs = fixtureRepository.findBySaveGameIdAndPhaseId(c.id(), phases.get(2).getId());
        List<KnockoutTie> finalTies = TieResolver.tiesOf(finalLegs);
        assertThat(finalTies).hasSize(1);
        assertThat(TieResolver.winner(finalTies.get(0)))
                .isIn(finalLegs.get(0).getHomeClubId(), finalLegs.get(0).getAwayClubId());
    }

    @Test
    void resolvingIsIdempotent() {
        Career c = createCareer();
        seasonAdvanceService.advance(c.id(), c.userId());
        SaveGame save = saveGameRepository.findById(c.id()).orElseThrow();

        int again = backgroundCompetitionService.resolveDue(save, save.getCurrentDate());

        assertThat(again).isZero();
    }

    /**
     * A background round must not age the player's squad.
     *
     * <p>Simulating a round runs a save-wide "didn't play" sweep that ticks injuries, burns
     * suspensions and drifts form for everyone outside the round's XIs. That sweep is meant to
     * happen once per advance, earned by the player's own match. When background rounds ran it
     * too, a benched injured player lost one match of injury for their own matchday and another
     * for every background round resolved alongside it — so an injury of two cleared in a single
     * advance, and it got worse the more divisions the world contained.
     */
    @Test
    void aBackgroundRoundDoesNotTickTheManagedSquadsInjuries() {
        Career c = createCareer();
        UUID managedClubId = saveGameRepository.findById(c.id()).orElseThrow().getManagedClubId();

        // Someone outside the XI, so they sit out the round rather than playing in it.
        List<Player> xi = lineupService.bestXi(managedClubId);
        Player benched = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> xi.stream().noneMatch(x -> x.getId().equals(p.getId())))
                .findFirst().orElseThrow();
        benched.addInjury(3);
        playerRepository.save(benched);

        // Advance until the player's own match is played — the advance that also resolves every
        // background round due by that date.
        AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
        for (int guard = 0; !"MATCH".equals(r.type()) && guard < ADVANCE_GUARD; guard++) {
            r = seasonAdvanceService.advance(c.id(), c.userId());
        }
        assertThat(r.type()).isEqualTo("MATCH");

        // Exactly one matchday served, no matter how many background rounds resolved with it.
        assertThat(playerRepository.findById(benched.getId()).orElseThrow().getInjuryMatches())
                .as("one advance must cost exactly one match of injury")
                .isEqualTo(2);
    }
}
