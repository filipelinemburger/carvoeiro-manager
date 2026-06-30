package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionClub;
import br.com.elifootcarvoeiro.competition.CompetitionClubRepository;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.contract.FreeAgentGenerator;
import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import br.com.elifootcarvoeiro.player.OverallCalculator;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.season.SeasonCalendarGenerator;
import br.com.elifootcarvoeiro.seed.SeedCareer;
import br.com.elifootcarvoeiro.seed.SeedClub;
import br.com.elifootcarvoeiro.seed.SeedCompetition;
import br.com.elifootcarvoeiro.seed.SeedLoader;
import br.com.elifootcarvoeiro.seed.SeedPlayer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final CompetitionClubRepository competitionClubRepository;
    private final SeasonCalendarGenerator seasonCalendarGenerator;
    private final OverallCalculator overallCalculator;
    private final FinanceCalculator financeCalculator;
    private final FreeAgentGenerator freeAgentGenerator;
    private final br.com.elifootcarvoeiro.transfer.TransferWindow transferWindow;

    public SaveGameService(SeedLoader seedLoader,
                           SaveGameRepository saveGameRepository,
                           ClubRepository clubRepository,
                           PlayerRepository playerRepository,
                           CompetitionRepository competitionRepository,
                           CompetitionClubRepository competitionClubRepository,
                           SeasonCalendarGenerator seasonCalendarGenerator,
                           OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator,
                           FreeAgentGenerator freeAgentGenerator,
                           br.com.elifootcarvoeiro.transfer.TransferWindow transferWindow) {
        this.seedLoader = seedLoader;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.seasonCalendarGenerator = seasonCalendarGenerator;
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
        this.freeAgentGenerator = freeAgentGenerator;
        this.transferWindow = transferWindow;
    }

    /**
     * Creates a new single-player career by copying the immutable seed into per-SaveGame rows:
     * clubs, players, competitions with membership rows, and the full dated calendar generated
     * by SeasonCalendarGenerator.
     */
    @Transactional
    public SaveGameResponse createCareer(UUID userId) {
        SeedCareer seed = seedLoader.loadCareer();

        // 1. Create SaveGame (managed club set after clubs exist)
        int seasonYear = seed.competitions().get(0).seasonYear();
        SaveGame saveGame = SaveGame.create(userId, seasonYear);
        saveGameRepository.save(saveGame);

        // 2. Create clubs and players; capture shortName -> clubId for membership wiring
        List<SeedClub> seedClubs = seed.clubPool();

        Map<String, UUID> clubIdByShortName = new HashMap<>();
        UUID managedClubId = null;
        String managedClubName = null;

        for (SeedClub seedClub : seedClubs) {
            Club club = Club.create(saveGame.getId(), seedClub.name(), seedClub.shortName());
            club.setNickname(seedClub.nickname());
            club.setPrimaryColor(seedClub.primaryColor());
            club.setSecondaryColor(seedClub.secondaryColor());
            clubRepository.save(club);

            clubIdByShortName.put(seedClub.shortName(), club.getId());

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

        // 3. Create competitions and save membership rows BEFORE calling SeasonCalendarGenerator
        //    (the generator reads memberships via competitionClubRepository to build the fixture schedule).
        List<Competition> competitions = new ArrayList<>();
        for (SeedCompetition seedComp : seed.competitions()) {
            int memberCount = seedComp.memberShortNames().size();
            int totalRounds = 2 * (memberCount - 1);
            Competition competition = Competition.create(
                    saveGame.getId(), seedComp.name(), seedComp.type(), seedComp.seasonYear(), totalRounds);
            competitionRepository.save(competition);
            competitions.add(competition);

            for (String shortName : seedComp.memberShortNames()) {
                UUID clubId = clubIdByShortName.get(shortName);
                competitionClubRepository.save(
                        CompetitionClub.create(saveGame.getId(), competition.getId(), clubId));
            }
        }

        // 4. Route fixture and season_event creation through SeasonCalendarGenerator.
        //    Memberships are persisted above so the generator will find them.
        seasonCalendarGenerator.generate(saveGame.getId(), seasonYear, competitions);

        // 5. Set managed club, current date to season start, and save the SaveGame.
        LocalDate seasonStart = LocalDate.of(seasonYear, 1, 15);
        saveGame.setManagedClubId(managedClubId);
        saveGame.setCurrentDate(seasonStart);
        saveGameRepository.save(saveGame);

        // Build response with summary of all competitions
        Competition primaryCompetition = competitions.get(0);
        List<CompetitionSummary> competitionSummaries = competitions.stream()
                .map(c -> new CompetitionSummary(c.getId(), c.getName(), c.getType(), c.getSeasonYear(), c.getTotalRounds()))
                .toList();

        return new SaveGameResponse(
                saveGame.getId(),
                saveGame.getSeasonYear(),
                primaryCompetition.getId(),
                primaryCompetition.getName(),
                managedClubId,
                managedClubName,
                saveGame.getCurrentRound(),
                primaryCompetition.getTotalRounds(),
                seasonStart.toString(),
                competitionSummaries,
                saveGame.getStatus().name(),
                transferWindow.isOpen(seasonStart));
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

        // Determine totalRounds and competitionId from competitions
        List<Competition> competitionsForSave = competitionRepository.findBySaveGameId(saveGameId);

        Competition primaryCompetition = competitionsForSave.stream().findFirst().orElse(null);

        UUID competitionId = primaryCompetition != null ? primaryCompetition.getId() : null;
        String competitionName = primaryCompetition != null ? primaryCompetition.getName() : null;
        int totalRounds = primaryCompetition != null ? primaryCompetition.getTotalRounds() : 0;

        List<CompetitionSummary> competitionSummaries = competitionsForSave.stream()
                .map(c -> new CompetitionSummary(c.getId(), c.getName(), c.getType(), c.getSeasonYear(), c.getTotalRounds()))
                .toList();

        String currentDate = saveGame.getCurrentDate() != null ? saveGame.getCurrentDate().toString() : null;

        return new SaveGameResponse(
                saveGame.getId(),
                saveGame.getSeasonYear(),
                competitionId,
                competitionName,
                saveGame.getManagedClubId(),
                managedClubName,
                saveGame.getCurrentRound(),
                totalRounds,
                currentDate,
                competitionSummaries,
                saveGame.getStatus().name(),
                saveGame.getCurrentDate() != null && transferWindow.isOpen(saveGame.getCurrentDate()));
    }

}
