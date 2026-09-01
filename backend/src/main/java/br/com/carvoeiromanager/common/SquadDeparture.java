package br.com.carvoeiromanager.common;

import br.com.carvoeiromanager.match.Lineup;
import br.com.carvoeiromanager.match.LineupPlayerRepository;
import br.com.carvoeiromanager.match.LineupRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The two rules every way out of the managed squad shares — selling a player and terminating
 * his contract: what makes a departure legal, and what must be cleaned up once it happens.
 * Kept in one place so the two paths cannot drift apart.
 */
@Component
public class SquadDeparture {

    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public SquadDeparture(PlayerRepository playerRepository,
                          LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository) {
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    /** The professional squad: youth prospects never count toward the departure rules. */
    public List<Player> seniorSquad(UUID clubId) {
        return playerRepository.findByClubIdAndYouthProspectFalse(clubId);
    }

    /**
     * Refuses a departure that would leave the club unable to field a team: below the squad
     * floor, or without a goalkeeper.
     */
    public void requireDepartureAllowed(List<Player> seniorSquad, Player leaving) {
        if (seniorSquad.size() <= SquadRules.MIN_SQUAD_SIZE) {
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Squad would be too small");
        }
        if (leaving.getPosition() == Position.GK
                && seniorSquad.stream().filter(p -> p.getPosition() == Position.GK).count() <= 1) {
            throw new ApiException(ErrorCode.SQUAD_TOO_SMALL, "Cannot release your last goalkeeper");
        }
    }

    /**
     * Removes a departing player from the managed lineup: the lineup_players row and any
     * set-piece-taker column pointing at him (no-cascade FK safety).
     */
    public void cleanManagedLineup(UUID saveGameId, UUID managedClubId, UUID playerId) {
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, managedClubId).ifPresent(lineup -> {
            lineupPlayerRepository.deleteByLineupIdAndPlayerId(lineup.getId(), playerId);
            boolean takerChanged = false;
            if (playerId.equals(lineup.getPenaltyTakerId())) { lineup.setPenaltyTakerId(null); takerChanged = true; }
            if (playerId.equals(lineup.getFreekickTakerId())) { lineup.setFreekickTakerId(null); takerChanged = true; }
            if (playerId.equals(lineup.getCornerTakerId())) { lineup.setCornerTakerId(null); takerChanged = true; }
            if (takerChanged) {
                lineupRepository.save(lineup);
            }
        });
    }
}
