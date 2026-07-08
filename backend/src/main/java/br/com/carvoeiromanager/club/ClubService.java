package br.com.carvoeiromanager.club;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.PlayerRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Read-only club browsing: every club of a save game, ranked by reputation. */
@Service
public class ClubService {

    private final SaveGameService saveGameService;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;

    public ClubService(SaveGameService saveGameService, ClubRepository clubRepository,
                       PlayerRepository playerRepository) {
        this.saveGameService = saveGameService;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
    }

    /**
     * All clubs of the save game, sorted by reputation (highest first). squadSize counts only
     * senior players — youth prospects don't count against it, mirroring the transfer/lineup rules.
     */
    public List<ClubSummaryResponse> getClubs(UUID saveGameId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        return clubRepository.findBySaveGameId(saveGameId).stream()
                .map(this::toSummary)
                .sorted(Comparator.comparingInt(ClubSummaryResponse::reputation).reversed())
                .toList();
    }

    private ClubSummaryResponse toSummary(Club club) {
        int squadSize = playerRepository.findByClubIdAndYouthProspectFalse(club.getId()).size();
        return new ClubSummaryResponse(club.getId(), club.getName(), club.getCommonName(), club.getShortName(),
                club.getPrimaryColor(), club.getSecondaryColor(), club.getReputation(), squadSize);
    }
}
