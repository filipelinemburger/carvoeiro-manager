package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.contract.FreeAgentGenerator;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.player.AttributeDeriver;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.OverallCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.season.SeasonCalendarGenerator;
import br.com.carvoeiromanager.seed.SeedCareer;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedCompetition;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedPlayer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Lazy;
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
    private final SeasonCalendarGenerator seasonCalendarGenerator;
    private final OverallCalculator overallCalculator;
    private final FinanceCalculator financeCalculator;
    private final FreeAgentGenerator freeAgentGenerator;
    private final br.com.carvoeiromanager.transfer.TransferWindow transferWindow;
    // TransferService depends back on SaveGameService (ownership checks on the user-facing
    // endpoints), so this side of the cycle is broken with @Lazy: the day-one market run is the
    // only thing SaveGameService needs from it, and that only happens after this bean (and the
    // whole context) is already up.
    private final br.com.carvoeiromanager.transfer.TransferService transferService;

    // New-entity inserts go through the EntityManager (persist), not repository.save():
    // with assigned UUID ids, save() routes through merge() and fires a SELECT before
    // every INSERT, which turns career creation into ~1,400 round-trips.
    @PersistenceContext
    private EntityManager entityManager;

    public SaveGameService(SeedLoader seedLoader,
                           SaveGameRepository saveGameRepository,
                           ClubRepository clubRepository,
                           PlayerRepository playerRepository,
                           CompetitionRepository competitionRepository,
                           SeasonCalendarGenerator seasonCalendarGenerator,
                           OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator,
                           FreeAgentGenerator freeAgentGenerator,
                           br.com.carvoeiromanager.transfer.TransferWindow transferWindow,
                           @Lazy br.com.carvoeiromanager.transfer.TransferService transferService) {
        this.seedLoader = seedLoader;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.seasonCalendarGenerator = seasonCalendarGenerator;
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
        this.freeAgentGenerator = freeAgentGenerator;
        this.transferWindow = transferWindow;
        this.transferService = transferService;
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
        entityManager.persist(saveGame);

        // 2. Create clubs and players; capture shortName -> clubId for membership wiring
        List<SeedClub> seedClubs = seed.clubPool();

        Map<String, UUID> clubIdByShortName = new HashMap<>();
        UUID managedClubId = null;
        String managedClubName = null;

        for (SeedClub seedClub : seedClubs) {
            Club club = Club.create(saveGame.getId(), seedClub.name(), seedClub.shortName());
            club.setCommonName(seedClub.commonName() != null ? seedClub.commonName() : seedClub.name());
            club.setNickname(seedClub.nickname());
            club.setPrimaryColor(seedClub.primaryColor());
            club.setSecondaryColor(seedClub.secondaryColor());
            club.setDefaultFormation(seedClub.formation());
            club.setDefaultMentality(seedClub.mentality());
            club.setDefaultPressing(seedClub.pressing());
            club.setDefaultMarking(seedClub.marking());
            entityManager.persist(club);

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
                // Prefer hand-authored ("real") attributes when the seed carries them (all Série B
                // clubs); otherwise derive the 22 non-anchor attributes from the six anchors.
                Attributes attributes = seedPlayer.attributes() != null
                        ? seedPlayer.attributes().toAttributes(
                                seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                                seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina())
                        : AttributeDeriver.derive(position,
                                seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                                seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina(), overall);
                Player player = Player.create(
                        saveGame.getId(), club.getId(), seedPlayer.name(), position,
                        seedPlayer.age(), seedPlayer.nationality(), attributes, overall);
                player.setWage(financeCalculator.weeklyWage(overall, seedPlayer.age()));
                entityManager.persist(player);
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
            entityManager.persist(freeAgentGenerator.generate(saveGame.getId(), faRng));
        }

        // 3. Create competitions and save membership rows BEFORE calling SeasonCalendarGenerator
        //    (the generator reads memberships via competitionClubRepository to build the fixture schedule).
        List<Competition> competitions = new ArrayList<>();
        for (SeedCompetition seedComp : seed.competitions()) {
            int memberCount = seedComp.memberShortNames().size();
            int totalRounds = 2 * (memberCount - 1);
            Competition competition = Competition.create(
                    saveGame.getId(), seedComp.name(), seedComp.type(), seedComp.seasonYear(), totalRounds);
            entityManager.persist(competition);
            competitions.add(competition);

            for (String shortName : seedComp.memberShortNames()) {
                UUID clubId = clubIdByShortName.get(shortName);
                entityManager.persist(
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

        // 6. Day-one market: AI clubs may already have players worth listing, and those (plus
        //    everyone else) can already draw an unsolicited bid on the managed squad.
        transferService.processMarketDay(saveGame.getId(), seasonStart);

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
        return new ApiException(ErrorCode.SAVE_GAME_NOT_FOUND, "Save game not found");
    }

    /** All of a user's careers, newest first, as lightweight summaries for the load dialog. */
    @Transactional(readOnly = true)
    public List<SaveGameSummaryResponse> listCareers(UUID userId) {
        return saveGameRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toSummary)
                .toList();
    }

    private SaveGameSummaryResponse toSummary(SaveGame saveGame) {
        String managedClubName = saveGame.getManagedClubId() == null ? null
                : clubRepository.findById(saveGame.getManagedClubId()).map(Club::getName).orElse(null);
        String currentDate = saveGame.getCurrentDate() != null ? saveGame.getCurrentDate().toString() : null;
        return new SaveGameSummaryResponse(
                saveGame.getId(),
                managedClubName,
                saveGame.getSeasonYear(),
                saveGame.getCurrentRound(),
                currentDate,
                saveGame.getStatus().name(),
                saveGame.getCreatedAt());
    }

    /** Deletes one career (ownership-checked). Cascade FKs clear all of that career's data. */
    @Transactional
    public void deleteCareer(UUID saveGameId, UUID userId) {
        requireOwned(saveGameId, userId);
        saveGameRepository.deleteByIdNative(saveGameId);
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
