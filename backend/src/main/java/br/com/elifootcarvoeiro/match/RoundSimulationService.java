package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.career.SaveGameStatus;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.finance.FinanceRoundResponse;
import br.com.elifootcarvoeiro.finance.FinanceService;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.transfer.TransferService;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Simulates a single league round deterministically, then applies the per-round
 * player-state lifecycle (cards -> suspensions, form) via {@link MatchOutcomeApplier}.
 */
@Service
public class RoundSimulationService {

    private final SaveGameService saveGameService;
    private final SaveGameRepository saveGameRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final TeamStrengthCalculator teamStrengthCalculator;
    private final MatchSimulationService matchSimulationService;
    private final MatchEventRepository matchEventRepository;
    private final MatchOutcomeApplier matchOutcomeApplier;
    private final FinanceService financeService;
    private final TransferService transferService;

    public RoundSimulationService(SaveGameService saveGameService,
                                  SaveGameRepository saveGameRepository,
                                  CompetitionRepository competitionRepository,
                                  FixtureRepository fixtureRepository,
                                  ClubRepository clubRepository,
                                  PlayerRepository playerRepository,
                                  LineupService lineupService,
                                  TacticsService tacticsService,
                                  TeamStrengthCalculator teamStrengthCalculator,
                                  MatchSimulationService matchSimulationService,
                                  MatchEventRepository matchEventRepository,
                                  MatchOutcomeApplier matchOutcomeApplier,
                                  FinanceService financeService,
                                  TransferService transferService) {
        this.saveGameService = saveGameService;
        this.saveGameRepository = saveGameRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.teamStrengthCalculator = teamStrengthCalculator;
        this.matchSimulationService = matchSimulationService;
        this.matchEventRepository = matchEventRepository;
        this.matchOutcomeApplier = matchOutcomeApplier;
        this.financeService = financeService;
        this.transferService = transferService;
    }

    private record FixtureSim(FixtureResultResponse response, List<Player> homeXi, List<Player> awayXi,
                             int homeGoals, int awayGoals, List<AttributedEvent> events) {
    }

    @Transactional
    public SimulateRoundResponse simulateNextRound(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameService.requireOwned(saveGameId, userId);
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            throw new ApiException("SEASON_FINISHED", "The season has already finished", HttpStatus.CONFLICT);
        }

        final Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        final int nextRound = saveGame.getCurrentRound();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, competition.getId(), nextRound);

        Map<UUID, Club> clubById = buildClubMap(saveGameId);
        final UUID managedClubId = saveGame.getManagedClubId();

        List<FixtureResultResponse> results = new ArrayList<>();
        Set<UUID> playedIds = new HashSet<>();
        MatchOutcomeApplier.Summary summary = new MatchOutcomeApplier.Summary();
        List<Player> toPersist = new ArrayList<>();

        for (Fixture fixture : fixtures) {
            FixtureSim sim = simulateFixture(fixture, saveGameId, managedClubId, clubById, nextRound);
            results.add(sim.response());

            boolean homeManaged = sim.homeXi().stream().anyMatch(p -> p.getClubId().equals(managedClubId));
            boolean awayManaged = sim.awayXi().stream().anyMatch(p -> p.getClubId().equals(managedClubId));
            matchOutcomeApplier.applyFixture(sim.events(),
                    new MatchOutcomeApplier.TeamOutcome(sim.homeXi(), sim.homeGoals(), sim.awayGoals(), homeManaged),
                    new MatchOutcomeApplier.TeamOutcome(sim.awayXi(), sim.awayGoals(), sim.homeGoals(), awayManaged),
                    summary);

            sim.homeXi().forEach(p -> { playedIds.add(p.getId()); toPersist.add(p); });
            sim.awayXi().forEach(p -> { playedIds.add(p.getId()); toPersist.add(p); });
        }

        List<Player> nonPlayers = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> !playedIds.contains(p.getId()))
                .toList();
        matchOutcomeApplier.applyNonPlayers(nonPlayers);
        toPersist.addAll(nonPlayers);
        playerRepository.saveAll(toPersist);

        final int newCurrentRound = nextRound + 1;
        saveGame.setCurrentRound(newCurrentRound);
        if (newCurrentRound >= competition.getTotalRounds()) {
            saveGame.setStatus(SaveGameStatus.FINISHED);
        }
        saveGameRepository.save(saveGame);

        FinanceRoundResponse roundFinance = financeService.processRound(saveGameId, nextRound, fixtures);
        BigDecimal prize = BigDecimal.ZERO;
        if (saveGame.getStatus() == SaveGameStatus.FINISHED) {
            prize = financeService.processSeasonEnd(saveGameId);
        }
        FinanceRoundResponse finance = new FinanceRoundResponse(
                roundFinance.gate(), roundFinance.sponsorship(), roundFinance.wages(),
                roundFinance.upkeep(), prize, roundFinance.net(), roundFinance.balance().add(prize));

        transferService.generateIncomingOffers(saveGameId, newCurrentRound);

        return new SimulateRoundResponse(newCurrentRound, List.copyOf(results),
                saveGame.getStatus() == SaveGameStatus.FINISHED,
                List.copyOf(summary.bookings()), List.copyOf(summary.newSuspensions()),
                finance);
    }

    private FixtureSim simulateFixture(Fixture fixture, UUID saveGameId, UUID managedClubId,
                                       Map<UUID, Club> clubById, int round) {
        final UUID homeClubId = fixture.getHomeClubId();
        final UUID awayClubId = fixture.getAwayClubId();

        List<Player> homeXi = resolveXi(saveGameId, homeClubId, managedClubId);
        List<Player> awayXi = resolveXi(saveGameId, awayClubId, managedClubId);

        TacticalProfile homeProfile = tacticsService.profileFor(saveGameId, homeClubId, homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(saveGameId, awayClubId, awayXi);

        TeamMatchInput homeInput = teamStrengthCalculator.compute(homeXi, homeProfile);
        TeamMatchInput awayInput = teamStrengthCalculator.compute(awayXi, awayProfile);

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) round;

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
        return new FixtureSim(response, homeXi, awayXi, match.homeGoals(), match.awayGoals(), attributed);
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

    private List<Player> resolveXi(UUID saveGameId, UUID clubId, UUID managedClubId) {
        if (clubId.equals(managedClubId)) {
            return lineupService.startingXi(saveGameId, clubId);
        }
        return lineupService.bestXi(clubId);
    }

    private Map<UUID, Club> buildClubMap(UUID saveGameId) {
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Function.identity()));
    }
}
