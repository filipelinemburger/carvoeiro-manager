package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.finance.AttendanceCalculator;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Runs the fixture-simulation engine for a given list of fixtures: per-fixture
 * simulation, event attribution, outcome application and player persistence.
 * Reused by {@code SeasonAdvanceService} via the date-driven advance path.
 *
 * <p>Match seed: {@code saveGameId.msb ^ fixtureId.msb ^ fixture.getRound()}.
 * Keying on {@code fixture.getRound()} keeps the seed identical to the original
 * round-based path (all fixtures in a round share the same round number) while
 * remaining safe for date-driven calls where there is no single "current round".
 */
@Service
public class FixtureSimulator {

    private static final double ASSIST_CHANCE = 0.65;
    private static final double BASE_FOUL_RATE = 0.13;

    /** Salt keeping the shootout's RNG stream distinct from the match's. */
    private static final long SHOOTOUT_SEED_SALT = 0x5A17E9C3L;

    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final MatchSimulationService matchSimulationService;
    private final MatchEventRepository matchEventRepository;
    private final FixtureAppearanceRepository fixtureAppearanceRepository;
    private final MatchOutcomeApplier matchOutcomeApplier;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final FixtureRepository fixtureRepository;
    private final FitnessCalculator fitnessCalculator;
    private final AttendanceCalculator attendanceCalculator;
    private final WeatherService weatherService;
    private final MatchContextResolver matchContextResolver;
    private final StaffEffectsService staffEffectsService;

    public FixtureSimulator(LineupService lineupService,
                            TacticsService tacticsService,
                            TeamStrengthCalculator teamStrengthCalculator,
                            MatchSimulationService matchSimulationService,
                            MatchEventRepository matchEventRepository,
                            FixtureAppearanceRepository fixtureAppearanceRepository,
                            MatchOutcomeApplier matchOutcomeApplier,
                            PlayerRepository playerRepository,
                            ClubRepository clubRepository,
                            FixtureRepository fixtureRepository,
                            FitnessCalculator fitnessCalculator,
                            AttendanceCalculator attendanceCalculator,
                            WeatherService weatherService,
                            MatchContextResolver matchContextResolver,
                            StaffEffectsService staffEffectsService) {
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.matchSimulationService = matchSimulationService;
        this.matchEventRepository = matchEventRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.matchOutcomeApplier = matchOutcomeApplier;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.fixtureRepository = fixtureRepository;
        this.fitnessCalculator = fitnessCalculator;
        this.attendanceCalculator = attendanceCalculator;
        this.weatherService = weatherService;
        this.matchContextResolver = matchContextResolver;
        this.staffEffectsService = staffEffectsService;
    }

    private record FixtureSim(FixtureResultResponse response, List<Player> homeXi, List<Player> awayXi,
                              int homeGoals, int awayGoals, List<AttributedEvent> events,
                              Map<UUID, Integer> homeFitness, Map<UUID, Integer> awayFitness) {
    }

    /**
     * Simulates the supplied fixtures for the given save game, applies outcomes
     * (cards, suspensions, form) via {@link MatchOutcomeApplier}, persists player
     * state, and returns a {@link RoundOutcome} with results and managed-club
     * booking/suspension summaries.
     *
     * @param save          the current save game (used for saveGameId and managedClubId)
     * @param competitionId the competition the fixtures belong to (documents scope; Task 7 uses it)
     * @param fixtures      the fixtures to simulate
     */
    public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures) {
        return simulateRound(save, competitionId, fixtures, Set.of(), Map.of());
    }

    public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures,
                                      Set<UUID> alreadyPlayedElsewhere) {
        return simulateRound(save, competitionId, fixtures, alreadyPlayedElsewhere, Map.of());
    }

    /**
     * Simulates {@code fixtures} as above, but for any fixture present in {@code precomputed}
     * REPLAYS the stored {@link SimulatedMatch} instead of calling {@link MatchSimulationService}
     * again — used at live-match finish to replay the round's other fixtures from the scores
     * pre-simulated at kickoff (see {@code LiveMatchService}), so results stay byte-identical
     * regardless of when they were computed. When the precomputed entry also carries
     * {@code homeXiIds}/{@code awayXiIds} (see {@link PrecomputedFixture}), attribution replays
     * that EXACT XI by id lookup instead of re-resolving via {@code bestXi} — required because
     * player state (training evolutions/injuries) can legitimately change between kickoff and
     * finish, which would otherwise make {@code bestXi} pick a different player than it picked at
     * kickoff and misattribute the pre-simulated goals. Everything else downstream (attribution,
     * persistence) is unchanged — each fixture's attribution RNG streams are seeded independently.
     * An empty/missing entry, or one without stored XI ids (older pre-existing serialized data),
     * falls back to the legacy resolve-fresh-XI behaviour.
     */
    public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures,
                                      Set<UUID> alreadyPlayedElsewhere, Map<UUID, PrecomputedFixture> precomputed) {
        return simulateRound(save, competitionId, fixtures, alreadyPlayedElsewhere, precomputed, true);
    }

    /**
     * Simulates a round of a competition the managed club does not play in.
     *
     * <p>Identical to {@link #simulateRound} except that it does <b>not</b> run the didn't-play
     * sweep. That sweep is save-wide: it ticks injuries, burns suspensions and drifts form and
     * morale for every player not in this round's XIs — including the managed club's. Exactly one
     * sweep per advance is the rule the game has always run on, and the managed round is what
     * earns it. Letting background rounds sweep too would tick the player's squad once more for
     * every division playing that day, so injuries and bans would expire faster the more of the
     * football world exists. Background clubs still get their tick from that one managed sweep.
     */
    public RoundOutcome simulateBackgroundRound(SaveGame save, UUID competitionId,
                                                List<Fixture> fixtures) {
        return simulateRound(save, competitionId, fixtures, Set.of(), Map.of(), false);
    }

    private RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures,
                                       Set<UUID> alreadyPlayedElsewhere,
                                       Map<UUID, PrecomputedFixture> precomputed,
                                       boolean sweepNonPlayers) {
        final UUID saveGameId = save.getId();
        final UUID managedClubId = save.getManagedClubId();

        Map<UUID, Club> clubById = buildClubMap(saveGameId);

        // Built ONCE for the whole round — see MatchContextResolver.RoundContext javadoc for why
        // this matters both for correctness (as-of-matchDate standings) and for query count (avoids
        // recomputing the competition's fixture list/standings table per fixture). Every fixture in
        // `fixtures` belongs to the same round of the same competition and therefore shares a
        // matchDate (see SeasonCalendarGenerator), so the first fixture's date is representative.
        MatchContextResolver.RoundContext roundContext = fixtures.isEmpty() ? null
                : matchContextResolver.forRound(save, competitionId, fixtures.get(0).getMatchDate());

        List<FixtureResultResponse> results = new ArrayList<>();
        Set<UUID> playedIds = new HashSet<>();
        MatchOutcomeApplier.Summary summary = new MatchOutcomeApplier.Summary();
        List<Player> toPersist = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            FixtureSim sim = simulateFixture(fixture, saveGameId, managedClubId, clubById, precomputed, roundContext);
            results.add(sim.response());

            boolean homeManaged = sim.homeXi().stream().anyMatch(p -> p.getClubId().equals(managedClubId));
            boolean awayManaged = sim.awayXi().stream().anyMatch(p -> p.getClubId().equals(managedClubId));
            matchOutcomeApplier.applyFixture(sim.events(),
                    new MatchOutcomeApplier.TeamOutcome(sim.homeXi(), sim.homeGoals(), sim.awayGoals(), homeManaged),
                    new MatchOutcomeApplier.TeamOutcome(sim.awayXi(), sim.awayGoals(), sim.homeGoals(), awayManaged),
                    summary);

            // Roll injuries AFTER match simulation and event attribution, using a separate
            // RNG so the existing match/event draws are byte-identical.
            long injurySeed = saveGameId.getMostSignificantBits()
                    ^ fixture.getId().getMostSignificantBits()
                    ^ (long) fixture.getRound();
            Random injuryRng = new Random(injurySeed ^ 0x1A70F1E5L);
            matchOutcomeApplier.applyInjuries(sim.homeXi(), sim.homeFitness(), injuryRng, homeManaged, summary);
            matchOutcomeApplier.applyInjuries(sim.awayXi(), sim.awayFitness(), injuryRng, awayManaged, summary);

            applyFitnessDecay(sim.homeXi(), sim.homeFitness(), fixture.getMatchDate());
            applyFitnessDecay(sim.awayXi(), sim.awayFitness(), fixture.getMatchDate());

            sim.homeXi().forEach(p -> { playedIds.add(p.getId()); toPersist.add(p); });
            sim.awayXi().forEach(p -> { playedIds.add(p.getId()); toPersist.add(p); });
        }

        if (sweepNonPlayers) {
            List<Player> nonPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                    .filter(p -> !playedIds.contains(p.getId()) && !alreadyPlayedElsewhere.contains(p.getId()))
                    .toList();
            matchOutcomeApplier.applyNonPlayers(nonPlayers);
            toPersist.addAll(nonPlayers);
        }
        playerRepository.saveAll(toPersist);

        return new RoundOutcome(List.copyOf(results), summary.bookings(), summary.newSuspensions(),
                List.copyOf(summary.newInjuries()));
    }

    /**
     * The pure half of fixture simulation: resolves the XI/tactics/team-strength inputs and runs
     * the seeded engine. Performs NO writes — {@link #resolveXi} only reads through
     * {@code LineupService} (saved lineup or best-XI selection), and {@link TacticsService#profileFor}
     * only reads persisted lineup/club tactics. Used to pre-simulate the round's other fixtures at
     * live-match kickoff (see {@code LiveMatchService}) so they can be replayed — not re-simulated —
     * at finish via the {@link #simulateRound(SaveGame, UUID, List, Set, Map)} precomputed overload.
     */
    SimulatedMatch simulatePure(Fixture fixture, UUID saveGameId, UUID managedClubId) {
        return simulatePureWithXi(fixture, saveGameId, managedClubId).match();
    }

    /**
     * Same pipeline as {@link #simulatePure}, but also returns the resolved home/away XI (player
     * ids, in {@code bestXi} pick order) so the caller can capture them into
     * {@link PrecomputedFixture}/{@link ParallelFixtureScore} at kickoff. See
     * {@link PrecomputedFixture}'s javadoc for why finish-time attribution MUST replay these exact
     * ids rather than re-resolving via {@code bestXi}.
     */
    PrecomputedFixture simulatePureWithXi(Fixture fixture, UUID saveGameId, UUID managedClubId) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();
        final LocalDate matchDate = fixture.getMatchDate();

        List<Player> homeXi = resolveXi(saveGameId, homeClubId, awayClubId, managedClubId, matchDate);
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, homeClubId, managedClubId, matchDate);

        TacticalProfile homeProfile = tacticsService.profileFor(saveGameId, homeClubId, homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(saveGameId, awayClubId, awayXi);

        Map<UUID, Integer> homeFitness = effectiveFitness(homeXi, matchDate);
        Map<UUID, Integer> awayFitness = effectiveFitness(awayXi, matchDate);

        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile, fitnessMult(homeFitness))
                .withCoachMultiplier(staffEffectsService.coachStrengthMultiplierFor(homeClubId));
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile, fitnessMult(awayFitness))
                .withCoachMultiplier(staffEffectsService.coachStrengthMultiplierFor(awayClubId));

        Weather weather = resolveWeather(fixture.getId(), homeClubId, matchDate);
        long seed = seedFor(saveGameId, fixture);
        SimulatedMatch match = matchSimulationService.simulate(homeInput, awayInput, weather, seed);
        // The 90-minute score is now known; only a knockout tie's second leg, level on
        // aggregate, asks for the extended (deterministic — same seed) re-run. Re-simulating
        // with the SAME seed reproduces the IDENTICAL minutes 1-90 (Random(seed) always replays
        // from the start), so this can never perturb the already-computed result — it only
        // extends it.
        if (needsExtraTime(fixture, match.homeGoals(), match.awayGoals())) {
            match = matchSimulationService.simulate(homeInput, awayInput, weather, seed, MatchMath.EXTRA_TIME_END);
        }
        return new PrecomputedFixture(match, playerIds(homeXi), playerIds(awayXi));
    }

    /**
     * True when this fixture must produce a winner: a knockout tie's second leg that is level on
     * aggregate after 90. League fixtures, first legs, and ties whose first leg hasn't been
     * played return false, so no extra-time RNG is ever drawn for them and their results stay
     * byte-identical to the pre-knockout engine. Crossover: this fixture's home club was the
     * AWAY club in leg 1, so leg 1's awayGoals add to this club's aggregate.
     */
    private boolean needsExtraTime(Fixture fixture, int homeGoals, int awayGoals) {
        if (fixture.getTieId() == null) {
            return false;                       // league fixture
        }
        if (fixture.getLeg() == null || fixture.getLeg() == 1) {
            return false;                       // first legs never go to extra time
        }
        Fixture firstLeg = fixtureRepository.findByTieId(fixture.getTieId()).stream()
                .filter(f -> f.getLeg() != null && f.getLeg() == 1)
                .findFirst().orElse(null);
        if (firstLeg == null || !firstLeg.isPlayed()) {
            return false;
        }
        int aggHome = homeGoals + firstLeg.getAwayGoals();
        int aggAway = awayGoals + firstLeg.getHomeGoals();
        return aggHome == aggAway;
    }

    /**
     * Runs a penalty shootout and persists it when {@code needsExtraTime} says the tie is STILL
     * level with the FINAL (post-extra-time) score — a no-op call for every other fixture, since
     * that same guard returned false before extra time was ever played. Takers are the resolved
     * XI (no in-match substitutions in this pure/instant engine, unlike {@code LiveMatchEngine}),
     * best finishers first; the goalkeeper is excluded from the taker pool.
     *
     * <p>MUST run after the caller's {@code fixtureRepository.save(fixture)} that first persists
     * this fixture: {@code match_events.fixture_id} is an FK, and each repository call is its own
     * committed transaction here, so an earlier {@code matchEventRepository.save} would race a
     * fixture row that does not exist yet. The penalty score set below is persisted with its own
     * follow-up save, since the caller's save already ran before these fields were known.
     */
    private void resolveShootout(Fixture fixture, List<Player> homeXi, List<Player> awayXi,
                                 int homeGoals, int awayGoals, UUID homeClubId, UUID awayClubId, long seed) {
        if (!needsExtraTime(fixture, homeGoals, awayGoals)) {
            return;
        }
        Player homeKeeper = keeperOf(homeXi);
        Player awayKeeper = keeperOf(awayXi);
        List<Player> homeTakers = outfieldOf(homeXi, homeKeeper);
        List<Player> awayTakers = outfieldOf(awayXi, awayKeeper);

        Shootout.Result shootout = Shootout.decide(
                homeTakers, awayTakers, homeKeeper, awayKeeper, seed ^ SHOOTOUT_SEED_SALT);
        fixture.setPenaltyHome(shootout.homeScore());
        fixture.setPenaltyAway(shootout.awayScore());
        fixtureRepository.save(fixture);
        for (Shootout.Kick kick : shootout.kicks()) {
            matchEventRepository.save(MatchEvent.create(fixture.getId(), MatchMath.EXTRA_TIME_END,
                    (kick.scored() ? EventType.PENALTY_SHOOTOUT_SCORED : EventType.PENALTY_SHOOTOUT_MISSED).name(),
                    kick.takerId(), kick.home() ? homeClubId : awayClubId));
        }
    }

    /** The on-pitch goalkeeper, or (a red-carded keeper with no sub) the best goalkeeping stat. */
    private static Player keeperOf(List<Player> xi) {
        return xi.stream().filter(p -> p.getPosition() == Position.GK).findFirst()
                .orElseGet(() -> xi.stream().max(Comparator.comparingInt(Player::getGoalkeeping))
                        .orElseThrow(() -> new IllegalStateException(
                                "Cannot run a shootout with an empty XI")));
    }

    /** Every outfield player minus the identified keeper; falls back to the full XI if that empties it. */
    private static List<Player> outfieldOf(List<Player> xi, Player keeper) {
        List<Player> outfield = xi.stream().filter(p -> !p.getId().equals(keeper.getId())).toList();
        return outfield.isEmpty() ? xi : outfield;
    }

    private static List<UUID> playerIds(List<Player> players) {
        return players.stream().map(Player::getId).toList();
    }

    /**
     * Match seed: {@code saveGameId.msb ^ fixtureId.msb ^ fixture.getRound()}. Keying on the round
     * (rather than a "next round" counter) keeps the seed identical to the original round-based path
     * for all fixtures sharing a round, and safe for multi-round date-driven advances.
     */
    private static long seedFor(UUID saveGameId, Fixture fixture) {
        return saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) fixture.getRound();
    }

    /**
     * Weather is always derived from the HOME club's climate zone (per the feature spec) and never
     * persisted — recomputed identically every time from the fixture id and match date. A missing
     * home club (already tolerated elsewhere in this class, e.g. {@code shortName}) falls back to
     * {@link Weather#neutral()} rather than throwing.
     */
    private Weather resolveWeather(UUID fixtureId, UUID homeClubId, LocalDate matchDate) {
        return clubRepository.findById(homeClubId)
                .map(club -> WeatherGenerator.forFixture(fixtureId, club.getClimateZone(), matchDate))
                .orElseGet(Weather::neutral);
    }

    private FixtureSim simulateFixture(Fixture fixture, UUID saveGameId, UUID managedClubId,
                                       Map<UUID, Club> clubById, Map<UUID, PrecomputedFixture> precomputed,
                                       MatchContextResolver.RoundContext roundContext) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();
        final LocalDate matchDate = fixture.getMatchDate();

        PrecomputedFixture precomputedFixture = precomputed.get(fixture.getId());
        boolean replayStoredXi = precomputedFixture != null
                && precomputedFixture.homeXiIds() != null && precomputedFixture.awayXiIds() != null;

        List<Player> homeXi;
        List<Player> awayXi;
        SimulatedMatch match;
        if (replayStoredXi) {
            // Robust path (see PrecomputedFixture javadoc): replay the EXACT XI captured at
            // kickoff pre-sim by id lookup, NOT a fresh resolveXi call — player state (training
            // evolutions/injuries, applied earlier in SeasonAdvanceService.completeUserMatch) may
            // have changed since kickoff, which would otherwise make resolveXi/bestXi legitimately
            // pick a different player than it picked at kickoff and misattribute the stored goals.
            homeXi = playersByIds(precomputedFixture.homeXiIds());
            awayXi = playersByIds(precomputedFixture.awayXiIds());
            match = precomputedFixture.match();
        } else if (precomputedFixture != null) {
            // Precomputed before XI ids were captured (in-flight match from before this fix) —
            // the match is already simulated, so only the XI needs resolving for attribution.
            // resolveXi is deterministic (see LineupService.bestXi's id tie-break) given
            // UNCHANGED player state, so this fresh resolution matches the one implicitly used
            // to build the stored match.
            homeXi = resolveXi(saveGameId, homeClubId, awayClubId, managedClubId, matchDate);
            awayXi = resolveXi(saveGameId, awayClubId, homeClubId, managedClubId, matchDate);
            match = precomputedFixture.match();
        } else {
            // Legacy path: nothing was precomputed for this fixture (the common case for every
            // AI-only fixture in an instant-advance round). A single simulatePureWithXi call
            // resolves the XI, runs the seeded engine, and returns the resolved XI ids alongside
            // the match — reused here via playersByIds for attribution, the same pattern as the
            // replayStoredXi branch above. This avoids resolving the XI (and recomputing tactics/
            // fitness/team-strength) a second time, which a separate resolveXi + simulatePure call
            // used to do.
            PrecomputedFixture computed = simulatePureWithXi(fixture, saveGameId, managedClubId);
            homeXi = playersByIds(computed.homeXiIds());
            awayXi = playersByIds(computed.awayXiIds());
            match = computed.match();
        }

        TacticalProfile homeProfile = tacticsService.profileFor(saveGameId, homeClubId, homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(saveGameId, awayClubId, awayXi);

        Map<UUID, Integer> homeFitness = effectiveFitness(homeXi, matchDate);
        Map<UUID, Integer> awayFitness = effectiveFitness(awayXi, matchDate);

        long seed = seedFor(saveGameId, fixture);

        Club homeClub = clubById.get(homeClubId);
        Club awayClub = clubById.get(awayClubId);

        fixture.recordResult(match.homeGoals(), match.awayGoals());
        // The match just finished, so the forecast is always revealed — weatherFor is used
        // directly rather than routing through the eve-of-match visibleWeather gate.
        Weather weather = homeClub != null ? weatherService.weatherFor(fixture, homeClub) : Weather.neutral();
        if (homeClub != null && awayClub != null && roundContext != null) {
            fixture.setAttendance(attendanceCalculator.attendance(
                    homeClub.getStadiumCapacity(),
                    homeClub.getReputation(),
                    awayClub.getReputation(),
                    roundContext.recentFormPoints(homeClubId, matchDate),
                    roundContext.leaguePosition(homeClubId),
                    roundContext.clubCount(),
                    weather));
        }
        fixtureRepository.save(fixture);
        // Reuses needsExtraTime with the FINAL (possibly extra-time) score: it returns true only
        // when the tie is STILL level, which is exactly when a shootout must decide it. Runs
        // AFTER the save above (and mutates+re-saves the fixture itself) — match_events rows
        // reference fixture_id via a FK, so the fixture row must exist first.
        resolveShootout(fixture, homeXi, awayXi, match.homeGoals(), match.awayGoals(),
                homeClubId, awayClubId, seed);

        // How long this match actually ran. Derived from the match rather than a local, because
        // `match` arrives here from three different paths (replayed XI, legacy precompute, fresh
        // simulation) and only the engine knows whether extra time was played. EXTRA_TIME_START is
        // emitted exactly once, and only when the loop was extended to 120.
        int finalMinute = match.events().stream()
                .anyMatch(e -> e.type() == EventType.EXTRA_TIME_START)
                ? MatchMath.EXTRA_TIME_END : MatchMath.MINUTES;

        List<AttributedEvent> attributed = saveEvents(fixture, match, homeXi, awayXi,
                homeProfile, awayProfile, homeClubId, awayClubId, seed);
        persistAppearances(fixture, homeXi, awayXi, homeProfile, awayProfile, homeClubId, awayClubId, seed, finalMinute);

        FixtureResultResponse response = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "", shortName(homeClub),
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "", shortName(awayClub),
                match.awayGoals(),
                homeClub != null ? homeClub.resolvedStadiumName() : null,
                fixture.getAttendance(), weather.condition().name(), weather.temperatureC(),
                // resolveShootout ran above, so these are already set when the tie went to penalties.
                fixture.getPenaltyHome(), fixture.getPenaltyAway());
        return new FixtureSim(response, homeXi, awayXi, match.homeGoals(), match.awayGoals(), attributed,
                homeFitness, awayFitness);
    }

    private static String shortName(Club club) {
        if (club == null) return "";
        return club.getCommonName() != null ? club.getCommonName() : club.getName();
    }

    private List<AttributedEvent> saveEvents(Fixture fixture, SimulatedMatch match,
                                             List<Player> homeXi, List<Player> awayXi,
                                             TacticalProfile homeProfile, TacticalProfile awayProfile,
                                             UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        Random assistRng = new Random(seed ^ 0x5A551571L);
        List<AttributedEvent> attributed = new ArrayList<>();
        Set<UUID> booked = new HashSet<>();
        Set<UUID> sentOff = new HashSet<>();
        for (SimulatedEvent event : match.events()) {
            if (event.type() == EventType.EXTRA_TIME_START) {
                // No natural scorer/carrier for this marker — attribute it to a "harmless"
                // carrier player, the same convention LiveMatchEngine uses for TACTICS_CHANGE/
                // HALF_TIME (see its javadoc): match_events.player_id/club_id are NOT NULL, and
                // this event has no real player behind it. Skips the attribution switch below
                // entirely (not a goal/card, no assist).
                Player carrier = !homeXi.isEmpty() ? homeXi.get(0) : (!awayXi.isEmpty() ? awayXi.get(0) : null);
                if (carrier != null) {
                    matchEventRepository.save(MatchEvent.create(fixture.getId(), event.minute(),
                            EventType.EXTRA_TIME_START.name(), carrier.getId(), homeClubId));
                }
                continue;
            }
            List<Player> xi = event.home() ? homeXi : awayXi;
            TacticalProfile profile = event.home() ? homeProfile : awayProfile;
            UUID clubId = event.home() ? homeClubId : awayClubId;

            // Sent-off players take no further part. The fallback to the full XI is practically
            // unreachable (it would need 11 send-offs on one side) but beats an empty-pool crash.
            List<Player> available = xi.stream().filter(p -> !sentOff.contains(p.getId())).toList();
            if (available.isEmpty()) {
                available = xi;
            }

            Player player = switch (event.type()) {
                case GOAL -> pickByWeight(available,
                        p -> scorerWeight(p, profile.attackZone(), profile.instructionFor(p.getId())), rng);
                case PENALTY_GOAL -> SetPieces.takerOrFallback(available, profile.penaltyTakerId(), Player::getFinishing);
                case FREE_KICK_GOAL -> SetPieces.takerOrFallback(available, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                case CORNER_GOAL -> pickByWeight(available, SetPieces::headerWeight, rng);
                // Card weight = tackling+1 (MARCACAO_DURA ×1.5). pickByWeight over (tackling+1) makes
                // exactly one nextInt(sum(tackling+1)) draw with the same cumulative walk the former
                // pickWeighted(getTackling) made (which added the +1 floor internally), so a null
                // instruction is byte-identical to the pre-instruction pick.
                case YELLOW_CARD, RED_CARD -> pickByWeight(available,
                        p -> cardWeight(p, profile.instructionFor(p.getId())), rng);
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_MISSED, SET_PIECE_GOAL, FOUL,
                     EXTRA_TIME_START, PENALTY_SHOOTOUT_SCORED, PENALTY_SHOOTOUT_MISSED ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
            };
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), event.minute(), event.type().name(),
                            player.getId(), clubId));
            attributed.add(new AttributedEvent(player.getId(), event.type(), event.home()));

            Player assister = assisterFor(event, player, available, profile, assistRng);
            if (assister != null) {
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), event.minute(), "ASSIST", assister.getId(), clubId));
            }

            // Second yellow to the same player = an immediate red in the same minute (real-football
            // rule); either kind of red removes the player from all later attribution.
            if (event.type() == EventType.YELLOW_CARD && !booked.add(player.getId())) {
                matchEventRepository.save(MatchEvent.create(fixture.getId(), event.minute(),
                        EventType.RED_CARD.name(), player.getId(), clubId));
                attributed.add(new AttributedEvent(player.getId(), EventType.RED_CARD, event.home()));
                sentOff.add(player.getId());
            } else if (event.type() == EventType.RED_CARD) {
                sentOff.add(player.getId());
            }
        }
        return attributed;
    }

    /**
     * Every starter plays the whole match — the instant path has no substitutions. {@code
     * finalMinute} is 90 normally and 120 when the tie went to extra time; hard-coding 90 would
     * make an AI club's players accrue less fatigue than the managed club's after the same
     * 120-minute cup tie, because the live path records the true figure from
     * {@code timeline.minutesPlayed()}.
     */
    private void persistAppearances(Fixture fixture, List<Player> homeXi, List<Player> awayXi,
                                    TacticalProfile homeProfile, TacticalProfile awayProfile,
                                    UUID homeClubId, UUID awayClubId, long seed, int finalMinute) {
        Map<UUID, Integer> fouls = foulCounts(homeXi, awayXi, homeProfile, awayProfile, seed);
        for (Player p : homeXi) {
            fixtureAppearanceRepository.save(FixtureAppearance.create(
                    fixture.getId(), p.getId(), homeClubId, finalMinute, fouls.getOrDefault(p.getId(), 0)));
        }
        for (Player p : awayXi) {
            fixtureAppearanceRepository.save(FixtureAppearance.create(
                    fixture.getId(), p.getId(), awayClubId, finalMinute, fouls.getOrDefault(p.getId(), 0)));
        }
    }

    /**
     * The assister for one scoring event, or null when unassisted. CORNER_GOAL's crossing-weighted
     * taker (distinct from the header-scorer) is the assist deterministically — no extra RNG draw.
     * GOAL rolls a ~65% chance and, if hit, picks a creativity-weighted teammate excluding the
     * scorer. PENALTY_GOAL and FREE_KICK_GOAL are never assisted.
     */
    private Player assisterFor(SimulatedEvent event, Player scorer, List<Player> xi,
                               TacticalProfile profile, Random assistRng) {
        return switch (event.type()) {
            case GOAL -> assistRng.nextDouble() < ASSIST_CHANCE
                    ? pickByWeightExcluding(xi, scorer,
                            p -> assistWeight(p, profile.attackZone(),
                                    RoleAssignment.resolve(profile.roles(), p).archetype(),
                                    profile.instructionFor(p.getId())), assistRng)
                    : null;
            case CORNER_GOAL -> SetPieces.takerOrFallback(xi, profile.cornerTakerId(),
                    p -> p.attribute(Attribute.CRUZAMENTO));
            default -> null;
        };
    }

    /** Weighted pick over an XI excluding one player; null if no eligible teammate remains. */
    private Player pickByWeightExcluding(List<Player> xi, Player exclude, ToIntFunction<Player> weight, Random rng) {
        List<Player> pool = xi.stream().filter(p -> !p.getId().equals(exclude.getId())).toList();
        if (pool.isEmpty()) {
            return null;
        }
        int totalWeight = pool.stream().mapToInt(p -> Math.max(0, weight.applyAsInt(p))).sum();
        if (totalWeight <= 0) {
            return pool.get(rng.nextInt(pool.size()));
        }
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player p : pool) {
            accumulated += Math.max(0, weight.applyAsInt(p));
            if (pick < accumulated) return p;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * Mirrors {@code LiveMatchEngine.scorerWeight} — zone- and instruction-aware open-play scorer
     * weight. The base {@link #goalWeight} plus the zone's signature half-attribute (headers wide,
     * long shots central); MISTO adds nothing and a null instruction adds nothing, so
     * {@code scorerWeight(p, zone, null)} equals the pre-instruction two-arg weight exactly
     * (byte-identical). CHUTAR_DE_LONGE lifts a midfielder's base position-factor 6→8 (in
     * {@link #goalWeight}) then ×1.25; INFILTRAR_NA_AREA ×1.5. Integer {@code /2}, {@code *3/2},
     * {@code *5/4} match the live engine's rounding.
     */
    static int scorerWeight(Player p, AttackZone zone, PlayerInstruction instr) {
        int base = goalWeight(p, instr);
        int w = switch (zone) {
            case LATERAIS -> base + p.attribute(Attribute.CABECEIO) / 2;
            case CENTRO -> base + p.attribute(Attribute.CHUTE_LONGE) / 2;
            case MISTO -> base;
        };
        if (instr == PlayerInstruction.INFILTRAR_NA_AREA) {
            w = w * 3 / 2;
        } else if (instr == PlayerInstruction.CHUTAR_DE_LONGE) {
            w = w * 5 / 4;
        }
        return w;
    }

    /**
     * Mirrors {@code LiveMatchEngine.assistWeight} — zone-, role- and instruction-aware creative
     * passing weight. MISTO is the legacy composite {@code 10 + PASSE + VISAO + CRUZAMENTO}; LATERAIS
     * leans on crossing and boosts wide roles, CENTRO on passing/vision and boosts central creators.
     * CRUZAR_MAIS then ×1.5. A null instruction leaves the zone/role weight untouched, so
     * {@code assistWeight(p, zone, role, null)} equals the pre-instruction three-arg weight exactly
     * (byte-identical). The {@code * 3 / 2} integer ×1.5 rounds identically to the live engine.
     */
    static int assistWeight(Player p, AttackZone zone, RoleArchetype role, PlayerInstruction instr) {
        int base = 10;
        int w = switch (zone) {
            case MISTO -> base + p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO)
                    + p.attribute(Attribute.CRUZAMENTO);
            case LATERAIS -> {
                int weight = base + 2 * p.attribute(Attribute.CRUZAMENTO);
                if (role.isWideCreator()) {
                    weight = weight * 3 / 2;
                }
                yield weight;
            }
            case CENTRO -> {
                int weight = base + p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO);
                if (role.isCentralCreator()) {
                    weight = weight * 3 / 2;
                }
                yield weight;
            }
        };
        if (instr == PlayerInstruction.CRUZAR_MAIS) {
            w = w * 3 / 2;
        }
        return w;
    }

    /**
     * Card-attribution weight, mirroring {@code LiveMatchEngine.cardWeight}. Base is {@code tackling+1}
     * (the +1 floor keeps everyone eligible — the former {@code pickWeighted(getTackling)} added it
     * internally, so a null instruction is byte-identical). MARCACAO_DURA ×1.5 via integer
     * {@code * 3 / 2}, rounding identically to the live engine.
     */
    static int cardWeight(Player p, PlayerInstruction instr) {
        int base = p.getTackling() + 1;
        return instr == PlayerInstruction.MARCACAO_DURA ? base * 3 / 2 : base;
    }

    /** Weighted pick over an XI where a player with weight 0 (e.g. a keeper scoring) is never chosen. */
    private Player pickByWeight(List<Player> xi, ToIntFunction<Player> weight, Random rng) {
        if (xi.isEmpty()) {
            throw new IllegalStateException("Cannot attribute a match event to an empty XI");
        }
        int totalWeight = xi.stream().mapToInt(p -> Math.max(0, weight.applyAsInt(p))).sum();
        if (totalWeight <= 0) {
            return xi.get(rng.nextInt(xi.size()));   // degenerate XI (e.g. keeper only)
        }
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player player : xi) {
            accumulated += Math.max(0, weight.applyAsInt(player));
            if (pick < accumulated) {
                return player;
            }
        }
        return xi.get(xi.size() - 1);
    }

    /**
     * Goal / chance likelihood by position (attackers most, keepers never in open play) blended with
     * finishing. Mirrors {@code LiveMatchEngine.goalWeight} so live and instant matches attribute
     * scorers consistently — a keeper does not top the scorers' chart.
     */
    private static int goalWeight(Player p) {
        return goalWeight(p, null);
    }

    /**
     * Instruction-aware {@link #goalWeight}, mirroring the live engine: CHUTAR_DE_LONGE lifts a
     * MIDFIELDER's position-factor 6→8 (ATT/DEF/GK unchanged); any other instruction (or null) uses
     * the pre-instruction factors, so the neutral case is byte-identical.
     */
    private static int goalWeight(Player p, PlayerInstruction instr) {
        int positionFactor = switch (p.getPosition()) {
            case ATT -> 12;
            case MID -> instr == PlayerInstruction.CHUTAR_DE_LONGE ? 8 : 6;
            case DEF -> 2;
            case GK -> 0;
        };
        return positionFactor * (p.getFinishing() + 10);
    }

    /**
     * Per-player foul counts for one fixture's two XIs, drawn directly (no minute-by-minute
     * simulation — nothing displays instant-match fouls live, only the final per-player count
     * matters). Mirrors LiveMatchEngine.maybeFoul's calibration and weighting, using a fixed
     * "average aggression" input since the instant path has no per-minute TeamMatchInput here.
     */
    Map<UUID, Integer> foulCounts(List<Player> homeXi, List<Player> awayXi,
                                  TacticalProfile homeProfile, TacticalProfile awayProfile, long seed) {
        Random foulRng = new Random(seed ^ 0xF0011E5L);
        Map<UUID, Integer> counts = new HashMap<>();
        for (int minute = 1; minute <= 90; minute++) {
            drawFoul(homeXi, homeProfile, foulRng, counts);
            drawFoul(awayXi, awayProfile, foulRng, counts);
        }
        return counts;
    }

    private void drawFoul(List<Player> xi, TacticalProfile profile, Random foulRng, Map<UUID, Integer> counts) {
        if (xi.isEmpty() || foulRng.nextDouble() >= BASE_FOUL_RATE) {
            return;
        }
        int totalWeight = xi.stream().mapToInt(p -> Math.max(0, foulWeight(p, profile.instructionFor(p.getId())))).sum();
        Player fouler;
        if (totalWeight <= 0) {
            fouler = xi.get(foulRng.nextInt(xi.size()));
        } else {
            int pick = foulRng.nextInt(totalWeight);
            int acc = 0;
            Player chosen = xi.get(xi.size() - 1);
            for (Player p : xi) {
                acc += Math.max(0, foulWeight(p, profile.instructionFor(p.getId())));
                if (pick < acc) { chosen = p; break; }
            }
            fouler = chosen;
        }
        counts.merge(fouler.getId(), 1, Integer::sum);
    }

    /**
     * Mirrors {@code LiveMatchEngine.foulWeight}. Base {@code AGRESSIVIDADE + DESARME + 10};
     * MARCACAO_DURA ×1.5 via integer {@code * 3 / 2}. A null instruction returns the base unchanged
     * (byte-identical).
     */
    static int foulWeight(Player p, PlayerInstruction instr) {
        int base = p.attribute(Attribute.AGRESSIVIDADE) + p.attribute(Attribute.DESARME) + 10;
        return instr == PlayerInstruction.MARCACAO_DURA ? base * 3 / 2 : base;
    }

    private Map<UUID, Integer> effectiveFitness(List<Player> xi, LocalDate onDate) {
        Map<UUID, Integer> map = new HashMap<>();
        for (Player p : xi) {
            map.put(p.getId(),
                    fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge()));
        }
        return map;
    }

    private Map<UUID, Double> fitnessMult(Map<UUID, Integer> effectiveFitnessById) {
        Map<UUID, Double> map = new HashMap<>();
        effectiveFitnessById.forEach((id, fit) -> map.put(id, fitnessCalculator.fitnessMult(fit)));
        return map;
    }

    private void applyFitnessDecay(List<Player> xi, Map<UUID, Integer> effectiveFitnessById, LocalDate matchDate) {
        for (Player p : xi) {
            int eff = effectiveFitnessById.getOrDefault(p.getId(), 100);
            int newBase = fitnessCalculator.decayedBaseFitness(eff, p.getStamina(), p.getAge(), p.getPosition());
            p.recordAppearance(newBase, matchDate);
        }
    }

    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID opponentClubId,
                                   UUID managedClubId, LocalDate matchDate) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId, opponentClubId);
        }
        return lineupService.bestXi(clubId, matchDate, opponentClubId);
    }

    /**
     * Loads the exact players captured in a {@link PrecomputedFixture}, by id, preserving the
     * stored pick order (needed for byte-identical weighted attribution — see
     * {@link #pickByWeight}, which picks by cumulative order). A stored id
     * that no longer resolves (players are not deleted mid-season; defensive only) is skipped
     * rather than failing the whole round.
     */
    private List<Player> playersByIds(List<UUID> ids) {
        Map<UUID, Player> byId = playerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private Map<UUID, Club> buildClubMap(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
    }
}
