package br.com.carvoeiromanager.career;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClimateZone;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.player.AttributeDeriver;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.OverallCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.season.SeasonCalendarGenerator;
import br.com.carvoeiromanager.seed.SeedCareer;
import br.com.carvoeiromanager.seed.SeedClub;
import br.com.carvoeiromanager.seed.SeedClubStaff;
import br.com.carvoeiromanager.seed.SeedCompetition;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedPhase;
import br.com.carvoeiromanager.seed.SeedPhaseValidator;
import br.com.carvoeiromanager.seed.SeedPlayer;
import br.com.carvoeiromanager.seed.SeedStaff;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRole;
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
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final SeasonCalendarGenerator seasonCalendarGenerator;
    private final OverallCalculator overallCalculator;
    private final FinanceCalculator financeCalculator;
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
                           CompetitionPhaseRepository competitionPhaseRepository,
                           SeasonCalendarGenerator seasonCalendarGenerator,
                           OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator,
                           br.com.carvoeiromanager.transfer.TransferWindow transferWindow,
                           @Lazy br.com.carvoeiromanager.transfer.TransferService transferService) {
        this.seedLoader = seedLoader;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.seasonCalendarGenerator = seasonCalendarGenerator;
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
        this.transferWindow = transferWindow;
        this.transferService = transferService;
    }

    /** Convenience overload for internal/test callers that don't need a specific manager identity. */
    @Transactional
    public SaveGameResponse createCareer(UUID userId) {
        return createCareer(userId, new CreateSaveGameRequest("Técnico", "Brasil"));
    }

    /**
     * Creates a new single-player career by copying the immutable seed into per-SaveGame rows:
     * clubs, players, competitions with membership rows, and the full dated calendar generated
     * by SeasonCalendarGenerator.
     */
    @Transactional
    public SaveGameResponse createCareer(UUID userId, CreateSaveGameRequest req) {
        SeedCareer seed = seedLoader.loadCareer();

        // 1. Create SaveGame (managed club set after clubs exist)
        int seasonYear = seed.competitions().get(0).seasonYear();
        SaveGame saveGame = SaveGame.create(userId, seasonYear, req.managerName(), req.managerNationality());
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
            club.setStadiumName(seedClub.stadium());
            club.setCity(seedClub.city());
            club.setState(seedClub.state());
            if (seedClub.climateZone() != null) {
                club.setClimateZone(ClimateZone.valueOf(seedClub.climateZone()));
            }
            club.setPrimaryColor(seedClub.primaryColor());
            club.setSecondaryColor(seedClub.secondaryColor());
            club.setDefaultFormation(seedClub.formation());
            club.setDefaultMentality(seedClub.mentality());
            // Prefer a hand-picked seed style; otherwise derive from mentality, mirroring the V36
            // migration backfill so fresh careers match upgraded saves.
            club.setDefaultGameStyle(seedClub.style() != null
                    ? seedClub.style() : styleFromMentality(seedClub.mentality()));
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
                // Prefer hand-authored ("real") attributes when the seed carries them (every club
                // in the pool, as of the Catarinense data-realism pass); otherwise derive the 22
                // non-anchor attributes from the six anchors. The derive path still runs for
                // free agents and newgens generated at runtime.
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
                applyResearchedPositionsAndRoles(player, seedPlayer);
                player.setWage(financeCalculator.weeklyWage(overall, seedPlayer.age()));
                entityManager.persist(player);
                squad.add(player);
            }

            // Financial profile derived from squad strength.
            int reputation = financeCalculator.reputation(squad);
            int capacity = seedClub.stadiumCapacity() != null
                    ? seedClub.stadiumCapacity()
                    : financeCalculator.stadiumCapacity(reputation);
            club.setReputation(reputation);
            club.setStadiumCapacity(capacity);
            club.setBalance(financeCalculator.startingBalance(reputation));
            club.setSponsorshipPerRound(financeCalculator.sponsorshipPerRound(reputation));
            club.setStadiumUpkeepPerRound(financeCalculator.upkeepPerRound(capacity));
            clubRepository.save(club);
        }

        seedClubStaff(saveGame.getId(), managedClubId, clubIdByShortName);

        // Stagger contract lengths (1..4) deterministically so deals don't all expire together.
        Random contractRng = new Random(saveGame.getId().getMostSignificantBits());
        List<Player> allPlayers = playerRepository.findBySaveGameId(saveGame.getId()).stream()
                .sorted(Comparator.comparing(Player::getId)).toList();
        for (Player player : allPlayers) {
            player.setContractYears(1 + contractRng.nextInt(4));
        }
        playerRepository.saveAll(allPlayers);

        // Seed the real free-agent pool (clubless) so there is always a market to shop —
        // hand-authored South American players, not procedurally generated.
        for (SeedPlayer seedPlayer : seedLoader.loadFreeAgents()) {
            Position position = Position.valueOf(seedPlayer.position());
            int overall = overallCalculator.compute(position,
                    seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                    seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina());
            Attributes attributes = seedPlayer.attributes() != null
                    ? seedPlayer.attributes().toAttributes(
                            seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                            seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina())
                    : AttributeDeriver.derive(position,
                            seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                            seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina(), overall);
            Player freeAgent = Player.create(saveGame.getId(), null, seedPlayer.name(), position,
                    seedPlayer.age(), seedPlayer.nationality(), attributes, overall);
            applyResearchedPositionsAndRoles(freeAgent, seedPlayer);
            entityManager.persist(freeAgent);
        }

        // 3. Create competitions and save membership rows BEFORE calling SeasonCalendarGenerator
        //    (the generator reads memberships via competitionClubRepository to build the fixture schedule).
        List<Competition> competitions = new ArrayList<>();
        for (SeedCompetition seedComp : seed.competitions()) {
            // Fail here, before any row is written, rather than mid-season: a bad phase config
            // otherwise surfaces as an IndexOutOfBounds during career creation, an exception
            // inside an advance() that rolls back and permanently blocks the round, or a career
            // that cannot roll over at all. See SeedPhaseValidator for each rule's failure mode.
            SeedPhaseValidator.validate(seedComp);

            // A phased competition's length is the sum of its phases' match-units; an unphased
            // one keeps the legacy double-round-robin count.
            int totalRounds = (seedComp.phases() == null || seedComp.phases().isEmpty())
                    ? (seedComp.memberShortNames().size() - 1) * 2
                    : seedComp.phases().stream().mapToInt(SeedPhase::rounds).sum();

            Competition competition = Competition.create(
                    saveGame.getId(), seedComp.name(), seedComp.type(), seedComp.seasonYear(), totalRounds);
            entityManager.persist(competition);
            competitions.add(competition);

            for (String shortName : seedComp.memberShortNames()) {
                UUID clubId = clubIdByShortName.get(shortName);
                entityManager.persist(
                        CompetitionClub.create(saveGame.getId(), competition.getId(), clubId));
            }

            if (seedComp.phases() != null) {
                int ordinal = 0;
                for (SeedPhase phase : seedComp.phases()) {
                    competitionPhaseRepository.save(CompetitionPhase.create(
                            saveGame.getId(), competition.getId(), ordinal++, phase.name(),
                            PhaseKind.valueOf(phase.kind()), phase.legs(), phase.rounds(),
                            phase.qualifiers()));
                }
            }
        }

        // 4. Route fixture and season_event creation through SeasonCalendarGenerator.
        //    Memberships are persisted above so the generator will find them.
        seasonCalendarGenerator.generate(saveGame.getId(), seasonYear, competitions, managedClubId);

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
                .map(c -> new CompetitionSummary(c.getId(), c.getName(), c.getType(), c.getSeasonYear(),
                        c.getTotalRounds(), hasPhases(c.getId())))
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
                transferWindow.isOpen(seasonStart),
                saveGame.getManagerName(),
                saveGame.getManagerNationality());
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

    /** Whether this competition has any {@link CompetitionPhase} rows — see {@link CompetitionSummary}. */
    private boolean hasPhases(UUID competitionId) {
        return !competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(competitionId).isEmpty();
    }

    /**
     * Maps a club's default mentality to a default game style, mirroring the V36 migration CASE
     * exactly (ATTACKING→JOGO_DE_PASSES, DEFENSIVE→CONTRA_ATAQUE, else/null→EQUILIBRADO) so a fresh
     * career's AI clubs express the same style an upgraded (backfilled) save would.
     */
    private String styleFromMentality(String mentality) {
        if ("ATTACKING".equals(mentality)) {
            return "JOGO_DE_PASSES";
        }
        if ("DEFENSIVE".equals(mentality)) {
            return "CONTRA_ATAQUE";
        }
        return "EQUILIBRADO";
    }

    /**
     * Applies the researched real-world positions/roles (spec §Task 6) on top of a freshly
     * constructed player, overriding the constructor's id-seeded defaults. A player without
     * researched data (positions/roles absent or empty in the seed) keeps those defaults —
     * this is deliberate, not a bug: the setters consume no RNG, so determinism is preserved.
     */
    private void applyResearchedPositionsAndRoles(Player player, SeedPlayer seedPlayer) {
        if (seedPlayer.positions() != null && !seedPlayer.positions().isEmpty()) {
            List<DetailedPosition> researched = seedPlayer.positions().stream()
                    .map(DetailedPosition::valueOf).toList();
            player.setPositions(researched.get(0), researched.subList(1, researched.size()));
        }
        if (seedPlayer.roles() != null && !seedPlayer.roles().isEmpty()) {
            player.setFamiliarRoles(seedPlayer.roles());
        }
    }

    /**
     * Persists each club's researched coaching staff. The managed club never receives a TECNICO
     * row — that job is the player's, and {@code StaffService.getRoster} synthesises the slot from
     * the save's manager name instead.
     *
     * <p>An unknown shortName fails the whole career creation rather than being skipped: a career
     * that silently launched without a club's commission would be far harder to notice than a
     * failure here.
     */
    private void seedClubStaff(UUID saveGameId, UUID managedClubId, Map<String, UUID> clubIdByShortName) {
        for (SeedClubStaff entry : seedLoader.loadClubStaff()) {
            UUID clubId = clubIdByShortName.get(entry.clubShortName());
            if (clubId == null) {
                throw new IllegalStateException(
                        "club-staff.json references unknown club shortName: " + entry.clubShortName());
            }
            for (SeedStaff member : entry.staff()) {
                if (member.role() == StaffRole.TECNICO && clubId.equals(managedClubId)) {
                    continue;
                }
                double average = (member.primaryAttribute() + member.secondaryAttribute()) / 2.0;
                StaffQuality quality = StaffQuality.forAverage(average);
                entityManager.persist(Staff.create(saveGameId, clubId, member.role(), quality,
                        member.name(), quality.monthlyWage(),
                        member.primaryAttribute(), member.secondaryAttribute()));
            }
        }
    }

    /** All of a user's careers, most-recently-played first (falling back to creation time for
     * careers that have only ever been created, never resumed). */
    @Transactional(readOnly = true)
    public List<SaveGameSummaryResponse> listCareers(UUID userId) {
        return saveGameRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing(
                        (SaveGameSummaryResponse r) -> r.lastPlayedAt() != null ? r.lastPlayedAt() : r.createdAt())
                        .reversed())
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
                saveGame.getCreatedAt(),
                saveGame.getLastPlayedAt());
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
                .map(c -> new CompetitionSummary(c.getId(), c.getName(), c.getType(), c.getSeasonYear(),
                        c.getTotalRounds(), hasPhases(c.getId())))
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
                saveGame.getCurrentDate() != null && transferWindow.isOpen(saveGame.getCurrentDate()),
                saveGame.getManagerName(),
                saveGame.getManagerNationality());
    }

}
