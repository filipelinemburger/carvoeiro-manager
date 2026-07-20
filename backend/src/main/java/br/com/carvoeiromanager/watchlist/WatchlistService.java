package br.com.carvoeiromanager.watchlist;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.contract.PreContractWindow;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manager-facing watchlist toggle + listing. Rival-only: watching an own-squad player is
 * rejected. The listing surfaces each watched player's current status (club or free agent,
 * transfer-listed, pre-contract-eligible) computed read-only from existing data.
 */
@Service
public class WatchlistService {

    private final SaveGameService saveGameService;
    private final WatchlistEntryRepository watchlistEntryRepository;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final PreContractWindow preContractWindow;

    public WatchlistService(SaveGameService saveGameService,
                            WatchlistEntryRepository watchlistEntryRepository,
                            PlayerRepository playerRepository,
                            ClubRepository clubRepository,
                            PreContractWindow preContractWindow) {
        this.saveGameService = saveGameService;
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.preContractWindow = preContractWindow;
    }

    @Transactional
    public void add(UUID saveGameId, UUID userId, UUID playerId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player not found"));
        if (save.getManagedClubId().equals(player.getClubId())) {
            throw new ApiException(ErrorCode.CANNOT_WATCH_OWN_PLAYER,
                    "You can only watch players from other clubs");
        }
        if (watchlistEntryRepository.existsBySaveGameIdAndPlayerId(saveGameId, playerId)) {
            return; // idempotent
        }
        watchlistEntryRepository.save(WatchlistEntry.create(saveGameId, playerId, Instant.now()));
    }

    @Transactional
    public void remove(UUID saveGameId, UUID userId, UUID playerId) {
        saveGameService.requireOwned(saveGameId, userId);
        watchlistEntryRepository.deleteBySaveGameIdAndPlayerId(saveGameId, playerId);
    }

    public List<WatchlistPlayerResponse> getWatchlist(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        boolean windowOpen = preContractWindow.isOpen(save.getCurrentDate());
        Map<UUID, String> clubNames = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, Club::getName));
        Map<UUID, Player> players = playerRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Player::getId, p -> p));
        return watchlistEntryRepository.findBySaveGameId(saveGameId).stream()
                .map(e -> players.get(e.getPlayerId()))
                .filter(p -> p != null)
                .map(p -> new WatchlistPlayerResponse(p.getId(), p.getName(), p.getPosition().name(),
                        p.getAge(), p.getOverall(),
                        p.isFreeAgent() ? "Agente livre" : clubNames.getOrDefault(p.getClubId(), "?"),
                        p.isFreeAgent(), p.isTransferListed(),
                        !p.isFreeAgent() && windowOpen && p.getContractYears() == 1))
                .sorted(Comparator.comparing(WatchlistPlayerResponse::overall).reversed())
                .toList();
    }
}
