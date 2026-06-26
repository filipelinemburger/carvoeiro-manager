package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.Position;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Simulates a single league round in a deterministic, reproducible way.
 *
 * Strength formulae (values ~0..99):
 *   attack  = mean over 11 players of (0.5*finishing + 0.3*pace + 0.2*passing)
 *   defense = 0.6 * mean(tackling of the 10 outfield) + 0.4 * (GK's goalkeeping)
 *
 * Seed derivation (no clock/random source):
 *   seed = saveGameId.getMostSignificantBits()
 *          ^ fixtureId.getMostSignificantBits()
 *          ^ (long) round
 *
 * Scorer selection per goal: deterministic RNG seeded from the fixture seed, iterated once
 * per goal in minute order; pick is weighted by each player's finishing attribute.
 */
@Service
public class RoundSimulationService {

    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;
    private final LineupService lineupService;
    private final MatchSimulationService matchSimulationService;
    private final MatchEventRepository matchEventRepository;

    public RoundSimulationService(SaveGameRepository saveGameRepository,
                                  CompetitionRepository competitionRepository,
                                  FixtureRepository fixtureRepository,
                                  ClubRepository clubRepository,
                                  LineupService lineupService,
                                  MatchSimulationService matchSimulationService,
                                  MatchEventRepository matchEventRepository) {
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
        this.lineupService = lineupService;
        this.matchSimulationService = matchSimulationService;
        this.matchEventRepository = matchEventRepository;
    }

    @Transactional
    public SimulateRoundResponse simulateNextRound(UUID saveGameId) {
        SaveGame saveGame = loadSaveGame(saveGameId);

        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        final Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        // currentRound is the last completed round (0 = none completed yet).
        // The next round to simulate is the 0-based index: currentRound itself.
        // After simulation we set currentRound = currentRound + 1.
        final int nextRound = saveGame.getCurrentRound();
        // Fixtures are stored with 0-based round indices (0..totalRounds-1)
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, competition.getId(), nextRound);

        Map<UUID, Club> clubById = buildClubMap(saveGameId);
        final UUID managedClubId = saveGame.getManagedClubId();

        List<FixtureResultResponse> results = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            FixtureResultResponse result = simulateFixture(fixture, saveGameId, managedClubId, clubById, nextRound);
            results.add(result);
        }

        final int newCurrentRound = nextRound + 1;
        saveGame.setCurrentRound(newCurrentRound);
        if (newCurrentRound >= competition.getTotalRounds()) {
            saveGame.setStatus(SaveGameStatus.FINISHED);
        }
        saveGameRepository.save(saveGame);

        // Return 1-based round number for display
        return new SimulateRoundResponse(newCurrentRound, List.copyOf(results), saveGame.getStatus() == SaveGameStatus.FINISHED);
    }

    private FixtureResultResponse simulateFixture(Fixture fixture, UUID saveGameId, UUID managedClubId,
                                                   Map<UUID, Club> clubById, int round) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();

        List<Player> homeXi = resolveXi(saveGameId, homeClubId, managedClubId);
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, managedClubId);

        LineupStrength homeStrength = computeStrength(homeXi);
        LineupStrength awayStrength = computeStrength(awayXi);

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) round;

        SimulatedMatch match = matchSimulationService.simulate(homeStrength, awayStrength, seed);

        fixture.recordResult(match.homeGoals(), match.awayGoals());
        fixtureRepository.save(fixture);

        saveGoalEvents(fixture, match, homeXi, awayXi, homeClubId, awayClubId, seed);

        Club homeClub = clubById.get(homeClubId);
        Club awayClub = clubById.get(awayClubId);

        return new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub != null ? homeClub.getName() : "",
                match.homeGoals(),
                awayClubId, awayClub != null ? awayClub.getName() : "",
                match.awayGoals());
    }

    /**
     * Saves a MatchEvent for each goal in the match using a deterministic scorer selection.
     *
     * A single Random seeded from the fixture seed is used to pick scorers in minute order,
     * ensuring full reproducibility: same seed always produces identical scorer assignments.
     */
    private void saveGoalEvents(Fixture fixture, SimulatedMatch match,
                                 List<Player> homeXi, List<Player> awayXi,
                                 UUID homeClubId, UUID awayClubId, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);

        for (SimulatedGoal goal : match.goals()) {
            List<Player> scoringXi = goal.home() ? homeXi : awayXi;
            UUID scoringClubId = goal.home() ? homeClubId : awayClubId;

            Player scorer = pickScorerWeightedByFinishing(scoringXi, rng);
            matchEventRepository.save(
                    MatchEvent.create(fixture.getId(), goal.minute(), "GOAL", scorer.getId(), scoringClubId));
        }
    }

    /**
     * Picks a scorer from the XI with probability proportional to each player's finishing attribute.
     * The GK (goalkeeping-specialist) has a very low finishing, so they are selected rarely.
     */
    private Player pickScorerWeightedByFinishing(List<Player> xi, Random rng) {
        // Use finishing + 1 so every player has a non-zero weight
        int totalWeight = xi.stream().mapToInt(p -> p.getFinishing() + 1).sum();
        int pick = rng.nextInt(totalWeight);
        int accumulated = 0;
        for (Player player : xi) {
            accumulated += player.getFinishing() + 1;
            if (pick < accumulated) {
                return player;
            }
        }
        return xi.get(xi.size() - 1);
    }

    /**
     * Returns the persisted XI for the managed club, or bestXi for AI clubs.
     */
    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID managedClubId) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId);
        }
        return lineupService.bestXi(clubId);
    }

    /**
     * Computes lineup strength from 11 players.
     *
     * attack  = mean over 11 of (0.5*finishing + 0.3*pace + 0.2*passing)
     * defense = 0.6 * mean(tackling of the 10 outfield) + 0.4 * (GK's goalkeeping)
     */
    private LineupStrength computeStrength(List<Player> xi) {
        double attackSum = xi.stream()
                .mapToDouble(p -> 0.5 * p.getFinishing() + 0.3 * p.getPace() + 0.2 * p.getPassing())
                .sum();
        double attack = attackSum / xi.size();

        Player gk = xi.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .findFirst()
                .orElse(xi.get(0));

        double outfieldTackling = xi.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .mapToDouble(Player::getTackling)
                .average()
                .orElse(0.0);

        double defense = 0.6 * outfieldTackling + 0.4 * gk.getGoalkeeping();

        return new LineupStrength(attack, defense);
    }

    private SaveGame loadSaveGame(UUID saveGameId) {
        return saveGameRepository.findById(saveGameId)
                .orElseThrow(() -> new ApiException(
                        "SAVE_GAME_NOT_FOUND",
                        "Save game with id " + saveGameId + " not found",
                        HttpStatus.NOT_FOUND));
    }

    private Map<UUID, Club> buildClubMap(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
    }
}
