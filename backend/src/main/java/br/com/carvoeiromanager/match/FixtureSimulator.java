package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
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

    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final MatchSimulationService matchSimulationService;
    private final MatchEventRepository matchEventRepository;
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

        Club homeClub = clubById.get(homeClubId);
        Club awayClub = clubById.get(awayClubId);
        FixtureResultResponse response = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "",
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "",
                match.awayGoals());
        return new FixtureSim(response, homeXi, awayXi, match.homeGoals(), match.awayGoals(), attributed,
                homeFitness, awayFitness);
    }

    private List<AttributedEvent> saveEvents(Fixture fixture, SimulatedMatch match,
                                             List<Player> homeXi, List<Player> awayXi,
                                             TacticalProfile homeProfile, TacticalProfile awayProfile,
                                             UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        List<AttributedEvent> attributed = new ArrayList<>();
        for (SimulatedEvent event : match.events()) {
            List<Player> xi = event.home() ? homeXi : awayXi;
            TacticalProfile profile = event.home() ? homeProfile : awayProfile;
            UUID clubId = event.home() ? homeClubId : awayClubId;

            Player player = switch (event.type()) {
                case GOAL -> pickWeighted(xi, Player::getFinishing, rng);
                case SET_PIECE_GOAL -> setPieceTaker(xi, profile, rng);
                case YELLOW_CARD, RED_CARD -> pickWeighted(xi, Player::getTackling, rng);
                case CHANCE, INJURY, SUBSTITUTION, TACTICS_CHANGE ->
                        throw new IllegalStateException("Instant simulation does not emit " + event.type());
            };
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), event.minute(), event.type().name(),
                            player.getId(), clubId));
            attributed.add(new AttributedEvent(player.getId(), event.type(), event.home()));
        }
        return attributed;
    }

    private Player setPieceTaker(List<Player> xi, TacticalProfile profile, Random rng) {
        UUID takerId = profile.penaltyTakerId() != null ? profile.penaltyTakerId() : profile.freekickTakerId();
        if (takerId != null) {
            for (Player p : xi) {
                if (p.getId().equals(takerId)) {
                    return p;
                }
            }
        }
        return pickWeighted(xi, Player::getFinishing, rng);
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
