package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link PhaseResolver#resolveIfPhaseComplete}: materializing a knockout phase's
 * fixtures from the standings of the round-robin phase before it.
 *
 * <p>Each scenario builds its own minimal 8-club, single-phase-then-knockout competition
 * directly through the repositories (rather than the full seeded Catarinense career, which
 * {@link br.com.carvoeiromanager.season.SeasonAdvanceServiceIT} already exercises end-to-end) so
 * results — and therefore the standings table used to seed the bracket — are fully controlled.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PhaseResolverTest {

    @Autowired PhaseResolver phaseResolver;
    @Autowired StandingsService standingsService;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;

    private static final int MEMBERS = 8;
    private static final int ROUNDS = MEMBERS - 1; // single round-robin over 8 clubs = 7 rounds

    /** Everything a test needs about one freshly-built scenario. */
    private record Scenario(SaveGame save, UUID competitionId, UUID firstPhaseId, UUID quarterFinalPhaseId,
                            UUID semiFinalPhaseId, SeasonEvent lastFirstPhaseEvent, SeasonEvent midFirstPhaseEvent,
                            SeasonEvent firstQuarterFinalEvent, SeasonEvent lastQuarterFinalEvent) {
        UUID saveGameId() { return save.getId(); }
    }

    /**
     * Builds a competition with 8 clubs, a ROUND_ROBIN first phase (7 rounds, qualifiers = 8)
     * fully played with deterministic results (lower seed index always wins), followed by a
     * KNOCKOUT quarterfinal phase (2 reserved rounds, no fixtures yet) — mirroring what
     * {@code SeasonCalendarGenerator} produces for the Catarinense, just at a smaller scale.
     *
     * <p>Results are rigged so club 0 beats everyone, club 1 beats everyone but club 0, and so
     * on down to club 7 who loses every match — a strict points ordering with no tiebreak
     * ambiguity, so standings-derived seeding is fully predictable.
     */
    private Scenario buildScenario(String label) {
        User user = User.create(label + "_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2025);
        saveGameRepository.save(save);
        UUID saveGameId = save.getId();

        List<UUID> clubIds = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            Club club = Club.create(saveGameId, label + " Club " + i, "C" + i);
            clubRepository.save(club);
            clubIds.add(club.getId());
        }

        Competition competition = Competition.create(saveGameId, label + " Cup", "CUP", 2025, ROUNDS + 2);
        competitionRepository.save(competition);
        for (UUID clubId : clubIds) {
            competitionClubRepository.save(CompetitionClub.create(saveGameId, competition.getId(), clubId));
        }

        CompetitionPhase firstPhase = CompetitionPhase.create(saveGameId, competition.getId(), 0,
                "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, ROUNDS, MEMBERS);
        competitionPhaseRepository.save(firstPhase);
        CompetitionPhase quarterFinalPhase = CompetitionPhase.create(saveGameId, competition.getId(), 1,
                "Quartas de Final", PhaseKind.KNOCKOUT, 2, 2, null);
        competitionPhaseRepository.save(quarterFinalPhase);
        CompetitionPhase semiFinalPhase = CompetitionPhase.create(saveGameId, competition.getId(), 2,
                "Semifinal", PhaseKind.KNOCKOUT, 2, 2, null);
        competitionPhaseRepository.save(semiFinalPhase);

        LocalDate seasonStart = LocalDate.of(2025, 1, 15);
        FixtureScheduler scheduler = new FixtureScheduler();
        List<List<Fixture>> byRound = new ArrayList<>();
        for (int r = 0; r < ROUNDS; r++) {
            byRound.add(new ArrayList<>());
        }
        for (ScheduledFixture s : scheduler.singleRoundRobin(MEMBERS)) {
            UUID home = clubIds.get(s.homeIndex());
            UUID away = clubIds.get(s.awayIndex());
            LocalDate date = seasonStart.plusDays((long) s.round() * 5);
            Fixture fx = Fixture.create(saveGameId, competition.getId(), s.round(), home, away, date);
            fx.setPhaseId(firstPhase.getId());
            // Deterministic result: the lower seed index always wins 2-0.
            if (s.homeIndex() < s.awayIndex()) {
                fx.recordResult(2, 0);
            } else {
                fx.recordResult(0, 2);
            }
            fixtureRepository.save(fx);
            byRound.get(s.round()).add(fx);
        }

        List<SeasonEvent> firstPhaseEvents = new ArrayList<>();
        for (int r = 0; r < ROUNDS; r++) {
            LocalDate date = seasonStart.plusDays((long) r * 5);
            SeasonEvent event = SeasonEvent.createPhased(saveGameId, date, competition.getId(),
                    firstPhase.getId(), r, 0);
            seasonEventRepository.save(event);
            firstPhaseEvents.add(event);
        }
        // Every round but the last is already processed; the last one is what triggers resolution.
        for (int r = 0; r < ROUNDS - 1; r++) {
            firstPhaseEvents.get(r).markProcessed();
            seasonEventRepository.save(firstPhaseEvents.get(r));
        }
        SeasonEvent lastFirstPhaseEvent = firstPhaseEvents.get(ROUNDS - 1);
        SeasonEvent midFirstPhaseEvent = firstPhaseEvents.get(2);

        // Reserved quarterfinal rounds: dated, phase-stamped, no fixtures — exactly what
        // SeasonCalendarGenerator leaves behind for a KNOCKOUT phase at generation time.
        List<SeasonEvent> quarterFinalEvents = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            LocalDate date = seasonStart.plusDays((long) (ROUNDS + r) * 5);
            SeasonEvent event = SeasonEvent.createPhased(saveGameId, date, competition.getId(),
                    quarterFinalPhase.getId(), ROUNDS + r, 0);
            seasonEventRepository.save(event);
            quarterFinalEvents.add(event);
        }

        // Reserved semifinal rounds, same idea, dated after the quarterfinal's two rounds.
        for (int r = 0; r < 2; r++) {
            LocalDate date = seasonStart.plusDays((long) (ROUNDS + 2 + r) * 5);
            seasonEventRepository.save(SeasonEvent.createPhased(saveGameId, date, competition.getId(),
                    semiFinalPhase.getId(), ROUNDS + 2 + r, 0));
        }

        return new Scenario(save, competition.getId(), firstPhase.getId(), quarterFinalPhase.getId(),
                semiFinalPhase.getId(), lastFirstPhaseEvent, midFirstPhaseEvent,
                quarterFinalEvents.get(0), quarterFinalEvents.get(1));
    }

    /** Groups a phase's fixtures into ties (ordered by tieOrder) and renders each as a short
     * club-name signature, so two independently-built scenarios can be compared without relying
     * on randomly-generated club or tie ids. */
    private List<String> pairingSignature(UUID saveGameId, UUID phaseId) {
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, phaseId);
        return fixtures.stream()
                .collect(java.util.stream.Collectors.groupingBy(Fixture::getTieId))
                .values().stream()
                .sorted(Comparator.comparing(leg -> leg.get(0).getTieOrder()))
                .map(legs -> legs.stream()
                        .sorted(Comparator.comparing(Fixture::getLeg))
                        .map(f -> shortName(f.getHomeClubId()) + "-" + shortName(f.getAwayClubId()))
                        .collect(java.util.stream.Collectors.joining("/")))
                .toList();
    }

    private String shortName(UUID clubId) {
        return clubRepository.findById(clubId).orElseThrow().getShortName();
    }

    @Test
    void materializesQuarterfinalsFromTheFirstPhaseTable() {
        // Given: the first phase is fully played; the QF events are reserved and empty
        Scenario s = buildScenario("materialize");

        // When
        boolean resolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());

        // Then: 4 ties = 8 fixtures across the two reserved QF rounds
        assertThat(resolved).isTrue();
        List<Fixture> qf = fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.quarterFinalPhaseId());
        assertThat(qf).hasSize(8);
        assertThat(qf).extracting(Fixture::getTieId).doesNotContainNull();
        assertThat(new HashSet<>(qf.stream().map(Fixture::getTieId).toList())).hasSize(4);
    }

    @Test
    void seedsTopAgainstBottomWithTheHigherSeedHostingTheSecondLeg() {
        Scenario s = buildScenario("seeding");
        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());

        // The phase is already fully played by the time resolution runs, so the seeding table
        // is the unbounded (asOfDate = null) final standings for that phase.
        LocalDate asOf = null;
        List<StandingRow> table = standingsService.computeStandings(
                s.save(), s.competitionId(), s.firstPhaseId(), asOf);
        UUID first = table.get(0).clubId();
        UUID eighth = table.get(7).clubId();

        List<Fixture> tie = fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.quarterFinalPhaseId())
                .stream()
                .filter(f -> f.getHomeClubId().equals(first) || f.getAwayClubId().equals(first))
                .sorted(Comparator.comparing(Fixture::getLeg)).toList();

        assertThat(tie).hasSize(2);
        // Leg 1 away for the top seed, leg 2 at home
        assertThat(tie.get(0).getHomeClubId()).isEqualTo(eighth);
        assertThat(tie.get(0).getAwayClubId()).isEqualTo(first);
        assertThat(tie.get(1).getHomeClubId()).isEqualTo(first);
        assertThat(tie.get(1).getAwayClubId()).isEqualTo(eighth);
    }

    /**
     * The managed path ({@code SeasonAdvanceService.recordMatchRound}) resolves BEFORE it marks
     * the event processed; the background loop
     * ({@code BackgroundCompetitionService.resolveDue}) resolves AFTER. Both must work, and they
     * do because {@code phaseComplete} accepts an event that is either already processed or the
     * one just handled. Pinned here because tightening that disjunction to "not processed" would
     * leave the managed path green and silently kill every background bracket.
     */
    @Test
    void resolvesEvenWhenTheTriggeringEventIsAlreadyMarkedProcessed() {
        // Short label on purpose: buildScenario stamps it into a username, and user.username is
        // VARCHAR(50) with a 36-char UUID already taking most of it.
        Scenario s = buildScenario("preproc");
        s.lastFirstPhaseEvent().markProcessed();
        seasonEventRepository.save(s.lastFirstPhaseEvent());

        boolean resolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());

        assertThat(resolved).isTrue();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.quarterFinalPhaseId()))
                .hasSize(8);
    }

    @Test
    void resolvingTwiceDoesNotDuplicateFixtures() {
        Scenario s = buildScenario("idempotent");
        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());
        boolean second = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());

        assertThat(second).isFalse();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.quarterFinalPhaseId()))
                .hasSize(8);
    }

    @Test
    void doesNothingWhileThePhaseIsStillRunning() {
        Scenario s = buildScenario("running");
        boolean resolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.midFirstPhaseEvent());

        assertThat(resolved).isFalse();
        assertThat(fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.quarterFinalPhaseId()))
                .isEmpty();
    }

    @Test
    void identicalStandingsProduceIdenticalPairings() {
        Scenario s = buildScenario("det-a");
        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());
        List<String> first = pairingSignature(s.saveGameId(), s.quarterFinalPhaseId());

        // Re-run against a second save seeded with identical results
        Scenario other = buildScenario("det-a"); // same label -> identical club short names
        phaseResolver.resolveIfPhaseComplete(other.save(), other.lastFirstPhaseEvent());
        List<String> second = pairingSignature(other.saveGameId(), other.quarterFinalPhaseId());

        assertThat(second).isEqualTo(first);
    }

    /**
     * Covers {@link PhaseResolver}'s knockout -> knockout branch (Task 9): once the quarterfinal
     * phase itself finishes, its winners — in {@code tie_order}, NOT standings or club-index
     * order — seed the semifinal, and {@code knockoutPairings} re-pairs best surviving against
     * worst surviving among just those four.
     *
     * <p>Ties 1 and 3 are scripted as upsets (the lower seed advances) precisely so the surviving
     * seeds are NOT simply "the top 4 of the original table" in index order — if {@code tiesOf}
     * or this resolver ever quietly sorted by the opaque {@code tie_id} instead of {@code
     * tie_order}, or dropped tie order when collecting winners, the semifinal pairing below would
     * scramble while still looking like a plausible bracket.
     */
    @Test
    void semifinalistsArePairedBestAgainstWorstSurvivingSeed() {
        Scenario s = buildScenario("semis");

        // Given: the quarterfinals are materialized from the first phase's table.
        boolean qfResolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());
        assertThat(qfResolved).isTrue();

        List<StandingRow> table = standingsService.computeStandings(
                s.save(), s.competitionId(), s.firstPhaseId(), null);
        List<UUID> seeds = table.stream().map(StandingRow::clubId).toList(); // seeds.get(i) = seed (i+1)

        // Score each quarterfinal tie so ties 0 and 2 go to the higher seed, and ties 1 and 3 are
        // upsets won by the lower seed — see the pairings knockoutPairings(8) produces:
        // tie_order 0 = seed0 vs seed7, 1 = seed1 vs seed6, 2 = seed2 vs seed5, 3 = seed3 vs seed4.
        List<Fixture> qf = fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.quarterFinalPhaseId());
        Map<Integer, List<Fixture>> byTieOrder = qf.stream()
                .collect(Collectors.groupingBy(Fixture::getTieOrder));
        for (Map.Entry<Integer, List<Fixture>> entry : byTieOrder.entrySet()) {
            List<Fixture> legs = entry.getValue().stream()
                    .sorted(Comparator.comparing(Fixture::getLeg)).toList();
            Fixture leg1 = legs.get(0);
            Fixture leg2 = legs.get(1);
            boolean higherSeedWins = entry.getKey() == 0 || entry.getKey() == 2;
            if (higherSeedWins) {
                leg1.recordResult(0, 1); // leg1: lower(home) 0 - higher(away) 1
                leg2.recordResult(1, 0); // leg2: higher(home) 1 - lower(away) 0 -> aggregate higher 2-0
            } else {
                leg1.recordResult(1, 0); // leg1: lower(home) 1 - higher(away) 0
                leg2.recordResult(0, 1); // leg2: higher(home) 0 - lower(away) 1 -> aggregate lower 2-0
            }
            fixtureRepository.save(leg1);
            fixtureRepository.save(leg2);
        }

        // Every quarterfinal round is now played; processing the second reserved QF round
        // triggers semifinal materialization.
        s.firstQuarterFinalEvent().markProcessed();
        seasonEventRepository.save(s.firstQuarterFinalEvent());

        boolean sfResolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastQuarterFinalEvent());
        assertThat(sfResolved).isTrue();

        List<KnockoutTie> semis = TieResolver.tiesOf(
                fixtureRepository.findBySaveGameIdAndPhaseId(s.saveGameId(), s.semiFinalPhaseId()));
        assertThat(semis).hasSize(2);

        // Survivors in tie_order sequence: seed0 (tie0, higher won), seed6 (tie1, upset),
        // seed2 (tie2, higher won), seed4 (tie3, upset) -> seeds indices [0, 1, 2, 3] of the
        // WINNERS list, re-paired by knockoutPairings(4) as {0,3} and {1,2}.
        UUID survivor0 = seeds.get(0); // tie0 winner: seed 0 (higher)
        UUID survivor1 = seeds.get(6); // tie1 winner: seed 6 (lower/upset)
        UUID survivor2 = seeds.get(2); // tie2 winner: seed 2 (higher)
        UUID survivor3 = seeds.get(4); // tie3 winner: seed 4 (lower/upset)

        assertThat(semis.get(0).higherSeedClubId()).isEqualTo(survivor0);
        assertThat(semis.get(0).lowerSeedClubId()).isEqualTo(survivor3);
        assertThat(semis.get(1).higherSeedClubId()).isEqualTo(survivor1);
        assertThat(semis.get(1).lowerSeedClubId()).isEqualTo(survivor2);
    }
}
