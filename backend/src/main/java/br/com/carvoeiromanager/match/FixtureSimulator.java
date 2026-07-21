package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
                            FitnessCalculator fitnessCalculator) {
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
        return simulateRound(save, competitionId, fixtures, Set.of());
    }

    public RoundOutcome simulateRound(SaveGame save, UUID competitionId, List<Fixture> fixtures,
                                      Set<UUID> alreadyPlayedElsewhere) {
        final UUID saveGameId = save.getId();
        final UUID managedClubId = save.getManagedClubId();

        Map<UUID, Club> clubById = buildClubMap(saveGameId);

        List<FixtureResultResponse> results = new ArrayList<>();
        Set<UUID> playedIds = new HashSet<>();
        MatchOutcomeApplier.Summary summary = new MatchOutcomeApplier.Summary();
        List<Player> toPersist = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            FixtureSim sim = simulateFixture(fixture, saveGameId, managedClubId, clubById);
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

        List<Player> nonPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !playedIds.contains(p.getId()) && !alreadyPlayedElsewhere.contains(p.getId()))
                .toList();
        matchOutcomeApplier.applyNonPlayers(nonPlayers);
        toPersist.addAll(nonPlayers);
        playerRepository.saveAll(toPersist);

        return new RoundOutcome(List.copyOf(results), summary.bookings(), summary.newSuspensions(),
                List.copyOf(summary.newInjuries()));
    }

    private FixtureSim simulateFixture(Fixture fixture, UUID saveGameId, UUID managedClubId,
                                       Map<UUID, Club> clubById) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();

        List<Player> homeXi = resolveXi(saveGameId, homeClubId, managedClubId, fixture.getMatchDate());
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, managedClubId, fixture.getMatchDate());

        TacticalProfile homeProfile = tacticsService.profileFor(saveGameId, homeClubId, homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(saveGameId, awayClubId, awayXi);

        final LocalDate matchDate = fixture.getMatchDate();
        Map<UUID, Integer> homeFitness = effectiveFitness(homeXi, matchDate);
        Map<UUID, Integer> awayFitness = effectiveFitness(awayXi, matchDate);

        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile, fitnessMult(homeFitness));
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile, fitnessMult(awayFitness));

        // Seed uses fixture.getRound() — identical to the old path's nextRound for
        // all fixtures in a single-round advance, and safe for multi-round date-driven advances.
        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) fixture.getRound();

        SimulatedMatch match = matchSimulationService.simulate(homeInput, awayInput, seed);

        fixture.recordResult(match.homeGoals(), match.awayGoals());
        fixtureRepository.save(fixture);

        List<AttributedEvent> attributed = saveEvents(fixture, match, homeXi, awayXi,
                homeProfile, awayProfile, homeClubId, awayClubId, seed);
        persistAppearances(fixture, homeXi, awayXi, homeClubId, awayClubId, seed);

        Club homeClub = clubById.get(homeClubId);
        Club awayClub = clubById.get(awayClubId);
        FixtureResultResponse response = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "", shortName(homeClub),
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "", shortName(awayClub),
                match.awayGoals());
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
                case GOAL -> pickByWeight(available, FixtureSimulator::goalWeight, rng);
                case PENALTY_GOAL -> SetPieces.takerOrFallback(available, profile.penaltyTakerId(), Player::getFinishing);
                case FREE_KICK_GOAL -> SetPieces.takerOrFallback(available, profile.freekickTakerId(),
                        p -> p.attribute(Attribute.BOLA_PARADA));
                case CORNER_GOAL -> pickByWeight(available, SetPieces::headerWeight, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(available, Player::getTackling, rng);
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE, HALF_TIME, PENALTY_MISSED, SET_PIECE_GOAL, FOUL ->
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

    /** Every starter plays all 90 minutes — the instant path has no substitutions. */
    private void persistAppearances(Fixture fixture, List<Player> homeXi, List<Player> awayXi,
                                    UUID homeClubId, UUID awayClubId, long seed) {
        Map<UUID, Integer> fouls = foulCounts(homeXi, awayXi, seed);
        for (Player p : homeXi) {
            fixtureAppearanceRepository.save(FixtureAppearance.create(
                    fixture.getId(), p.getId(), homeClubId, 90, fouls.getOrDefault(p.getId(), 0)));
        }
        for (Player p : awayXi) {
            fixtureAppearanceRepository.save(FixtureAppearance.create(
                    fixture.getId(), p.getId(), awayClubId, 90, fouls.getOrDefault(p.getId(), 0)));
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
                    ? pickByWeightExcluding(xi, scorer, FixtureSimulator::assistWeight, assistRng)
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

    /** Mirrors LiveMatchEngine.assistWeight — creative-passing composite. */
    private static int assistWeight(Player p) {
        return p.attribute(Attribute.PASSE) + p.attribute(Attribute.VISAO)
                + p.attribute(Attribute.CRUZAMENTO) + 10;
    }

    private Player pickWeighted(List<Player> xi, ToIntFunction<Player> attribute, Random rng) {
        // Guard before any RNG draw, so determinism for valid XIs is unaffected. An
        // empty XI cannot occur with full seeded rosters today, but once eligibility
        // filtering lands (Tasks 6-7) a meaningful error beats nextInt(0)'s cryptic throw.
        if (xi.isEmpty()) {
            throw new IllegalStateException("Cannot attribute a match event to an empty XI");
        }
        int totalWeight = xi.stream().mapToInt(p -> attribute.applyAsInt(p) + 1).sum();
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player player : xi) {
            accumulated += attribute.applyAsInt(player) + 1;
            if (pick < accumulated) {
                return player;
            }
        }
        return xi.get(xi.size() - 1);
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
        int positionFactor = switch (p.getPosition()) {
            case ATT -> 12;
            case MID -> 6;
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
    Map<UUID, Integer> foulCounts(List<Player> homeXi, List<Player> awayXi, long seed) {
        Random foulRng = new Random(seed ^ 0xF0011E5L);
        Map<UUID, Integer> counts = new HashMap<>();
        for (int minute = 1; minute <= 90; minute++) {
            drawFoul(homeXi, foulRng, counts);
            drawFoul(awayXi, foulRng, counts);
        }
        return counts;
    }

    private void drawFoul(List<Player> xi, Random foulRng, Map<UUID, Integer> counts) {
        if (xi.isEmpty() || foulRng.nextDouble() >= BASE_FOUL_RATE) {
            return;
        }
        int totalWeight = xi.stream().mapToInt(p -> Math.max(0, foulWeight(p))).sum();
        Player fouler;
        if (totalWeight <= 0) {
            fouler = xi.get(foulRng.nextInt(xi.size()));
        } else {
            int pick = foulRng.nextInt(totalWeight);
            int acc = 0;
            Player chosen = xi.get(xi.size() - 1);
            for (Player p : xi) {
                acc += Math.max(0, foulWeight(p));
                if (pick < acc) { chosen = p; break; }
            }
            fouler = chosen;
        }
        counts.merge(fouler.getId(), 1, Integer::sum);
    }

    /** Mirrors LiveMatchEngine.foulWeight. */
    private static int foulWeight(Player p) {
        return p.attribute(Attribute.AGRESSIVIDADE) + p.attribute(Attribute.DESARME) + 10;
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

    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID managedClubId, LocalDate matchDate) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId);
        }
        return lineupService.bestXi(clubId, matchDate);
    }

    private Map<UUID, Club> buildClubMap(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
    }
}
