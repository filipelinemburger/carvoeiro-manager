package br.com.elifootcarvoeiro.career;

import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.FixtureScheduler;
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
import java.util.List;
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

    public SaveGameService(SeedLoader seedLoader,
                           SaveGameRepository saveGameRepository,
                           ClubRepository clubRepository,
                           PlayerRepository playerRepository,
                           CompetitionRepository competitionRepository,
                           FixtureRepository fixtureRepository) {
        this.seedLoader = seedLoader;
        this.saveGameRepository = saveGameRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.competitionRepository = competitionRepository;
        this.fixtureRepository = fixtureRepository;
        this.fixtureScheduler = new FixtureScheduler();
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

            for (SeedPlayer seedPlayer : seedClub.players()) {
                Position position = Position.valueOf(seedPlayer.position());
                int overall = computeOverall(seedPlayer, position);
                Player player = Player.create(
                        saveGame.getId(), club.getId(), seedPlayer.name(), position,
                        seedPlayer.age(), seedPlayer.nationality(),
                        seedPlayer.pace(), seedPlayer.finishing(), seedPlayer.passing(),
                        seedPlayer.tackling(), seedPlayer.goalkeeping(), seedPlayer.stamina(),
                        overall);
                playerRepository.save(player);
            }
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
                    saveGame.getId(), competition.getId(), scheduled.round(), homeClubId, awayClubId);
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

    /**
     * Computes player overall as a position-weighted average of the 6 attributes.
     *
     * Weights (sum = 1.0):
     *   GK:  goalkeeping .60, stamina .15, passing .15, pace .10
     *   DEF: tackling .40, pace .20, stamina .15, passing .15, finishing .10
     *   MID: passing .35, stamina .20, tackling .20, pace .15, finishing .10
     *   ATT: finishing .40, pace .25, passing .15, stamina .10, tackling .10
     */
    private int computeOverall(SeedPlayer p, Position position) {
        double weighted = switch (position) {
            case GK -> p.goalkeeping() * 0.60 + p.stamina() * 0.15 + p.passing() * 0.15 + p.pace() * 0.10;
            case DEF -> p.tackling() * 0.40 + p.pace() * 0.20 + p.stamina() * 0.15 + p.passing() * 0.15 + p.finishing() * 0.10;
            case MID -> p.passing() * 0.35 + p.stamina() * 0.20 + p.tackling() * 0.20 + p.pace() * 0.15 + p.finishing() * 0.10;
            case ATT -> p.finishing() * 0.40 + p.pace() * 0.25 + p.passing() * 0.15 + p.stamina() * 0.10 + p.tackling() * 0.10;
        };
        return (int) Math.round(weighted);
    }
}
