package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.FixtureScheduler;
import br.com.elifootcarvoeiro.contract.FreeAgentGenerator;
import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import br.com.elifootcarvoeiro.player.OverallCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.seed.SeedClub;
import br.com.elifootcarvoeiro.seed.SeedData;
import br.com.elifootcarvoeiro.seed.SeedLoader;
import br.com.elifootcarvoeiro.seed.SeedPlayer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
public class SaveGameService {

    private static final String MANAGED_CLUB_SHORT_NAME = "CRI";

    private final SeedLoader seedLoader;
    private final SaveGameRepository saveGameRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final FixtureScheduler fixtureScheduler;
    private final OverallCalculator overallCalculator;
    private final FinanceCalculator financeCalculator;
    private final FreeAgentGenerator freeAgentGenerator;

    public SaveGameService(SeedLoader seedLoader,
                           SaveGameRepository saveGameRepository,
                           ClubRepository clubRepository,
                           PlayerRepository playerRepository,
                           CompetitionRepository competitionRepository,
                           FixtureRepository fixtureRepository,
                           OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator,
                           FreeAgentGenerator freeAgentGenerator) {
        this.seedLoader = seedLoader;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureScheduler = new FixtureScheduler();
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
        this.freeAgentGenerator = freeAgentGenerator;
    }

    /**
     * Creates a new single-player career by copying the immutable seed into per-SaveGame rows:
     * clubs, players, one league competition, and the full fixture calendar.
     */
    @Transactional
    public SaveGameResponse createCareer(UUID userId) {
        SeedData seed = seedLoader.loadCatarinense();

        // 1. Create SaveGame (managed club set after clubs exist)
        SaveGame saveGame = SaveGame.create(userId, seed.seasonYear());
        saveGameRepository.save(saveGame);

        // 2. Create clubs and players; track order and managed club
        List<SeedClub> seedClubs = seed.clubs();
        int clubCount = seedClubs.size();

        List<UUID> clubIds = new ArrayList<>(clubCount);
        UUID managedClubId = null;
        String managedClubName = null;

        for (SeedClub seedClub : seedClubs) {
            Club club = Club.create(saveGame.getId(), seedClub.name(), seedClub.shortName());
            club.setNickname(seedClub.nickname());
            club.setPrimaryColor(seedClub.primaryColor());
            club.setSecondaryColor(seedClub.secondaryColor());
            clubRepository.save(club);

            clubIds.add(club.getId());

            if (MANAGED_CLUB_SHORT_NAME.equals(seedClub.shortName())) {
                managedClubId = club.getId();
                managedClubName = club.getName();
            }

            List<Player> squad = new ArrayList<>();
            for (SeedPlayer seedPlayer : seedClub.players()) {
                Position position = Position.valueOf(seedPlayer.position());
                int overall = overallCalculator.compute(position,
                        seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                        seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina());
                Player player = Player.create(
                        saveGame.getId(), club.getId(), seedPlayer.name(), position,
                        seedPlayer.age(), seedPlayer.nationality(),
                        seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                        seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina(),
                        overall);
                player.setWage(financeCalculator.weeklyWage(overall, seedPlayer.age()));
                playerRepository.save(player);
                squad.add(player);
            }

            // Financial profile derived from squad strength.
            int reputation = financeCalculator.reputation(squad);
            int capacity = financeCalculator.stadiumCapacity(reputation);
            club.setReputation(reputation);
            club.setStadiumCapacity(capacity);
            club.setBalance(financeCalculator.startingBalance(reputation));
            club.setSponsorshipPerRound(financeCalculator.sponsorshipPerRound(reputation));
            club.setStadiumUpkeepPerRound(financeCalculator.upkeepPerRound(capacity));
            clubRepository.save(club);
        }

        // Stagger contract lengths (1..4) deterministically so deals don't all expire together.
        Random contractRng = new Random(saveGame.getId().getMostSignificantBits());
        List<Player> allPlayers = playerRepository.findBySaveGameId(saveGame.getId()).stream()
                .sorted(Comparator.comparing(Player::getId)).toList();
        for (Player player : allPlayers) {
            player.setContractYears(1 + contractRng.nextInt(4));
        }
        playerRepository.saveAll(allPlayers);

        // Seed an initial free-agent pool (clubless) so there is always a market to shop.
        Random faRng = new Random(saveGame.getId().getMostSignificantBits() ^ 0xFA11AC0DL);
        for (int i = 0; i < 10; i++) {
            playerRepository.save(freeAgentGenerator.generate(saveGame.getId(), faRng));
        }

        // 3. Create competition
        int totalRounds = 2 * (clubCount - 1);
        Competition competition = Competition.create(
                saveGame.getId(), seed.competitionName(), "LEAGUE", seed.seasonYear(), totalRounds);
        competitionRepository.save(competition);

        // 4. Generate and save all fixtures
        fixtureScheduler.doubleRoundRobin(clubCount).forEach(scheduled -> {
            UUID homeClubId = clubIds.get(scheduled.homeIndex());
            UUID awayClubId = clubIds.get(scheduled.awayIndex());
            Fixture fixture = Fixture.create(
                    saveGame.getId(), competition.getId(), scheduled.round(), homeClubId, awayClubId, null);
            fixtureRepository.save(fixture);
        });

        // 5. Set managed club and save the SaveGame
        saveGame.setManagedClubId(managedClubId);
        saveGameRepository.save(saveGame);

        return new SaveGameResponse(
                saveGame.getId(),
                saveGame.getSeasonYear(),
                competition.getId(),
                competition.getName(),
                managedClubId,
                managedClubName,
                saveGame.getCurrentRound(),
                totalRounds);
    }

    /**
     * Loads a save game and enforces that it belongs to {@code userId}.
     *
     * Throws {@code SAVE_GAME_NOT_FOUND} (404) if the save game does not exist OR is owned by a
     * different user. NOT_FOUND (rather than FORBIDDEN) is used deliberately so the API never leaks
     * which save-game ids exist for other users.
     */
    public SaveGame requireOwned(UUID saveGameId, UUID userId) {
        SaveGame saveGame = saveGameRepository.findById(saveGameId)
                .orElseThrow(() -> saveGameNotFound());
        if (!saveGame.getUserId().equals(userId)) {
            throw saveGameNotFound();
        }
        return saveGame;
    }

    private ApiException saveGameNotFound() {
        return new ApiException("SAVE_GAME_NOT_FOUND", "Save game not found", HttpStatus.NOT_FOUND);
    }

    public SaveGameResponse getById(UUID saveGameId, UUID userId) {
        SaveGame saveGame = requireOwned(saveGameId, userId);

        String managedClubName = null;
        if (saveGame.getManagedClubId() != null) {
            managedClubName = clubRepository.findById(saveGame.getManagedClubId())
                    .map(Club::getName)
                    .orElse(null);
        }

        // Determine totalRounds and competitionId from the competition
        Competition competition = competitionRepository.findBySaveGameId(saveGameId)
                .stream()
                .findFirst()
                .orElse(null);

        UUID competitionId = competition != null ? competition.getId() : null;
        String competitionName = competition != null ? competition.getName() : null;
        int totalRounds = competition != null ? competition.getTotalRounds() : 0;

        return new SaveGameResponse(
                saveGame.getId(),
                saveGame.getSeasonYear(),
                competitionId,
                competitionName,
                saveGame.getManagedClubId(),
                managedClubName,
                saveGame.getCurrentRound(),
                totalRounds);
    }

}
