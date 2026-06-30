package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Pressing;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link TacticalProfile} for a club's match: the managed club's
 * persisted lineup tactics when present, otherwise a sensible default (also used
 * for all AI clubs, which never persist a lineup).
 */
@Service
public class TacticsService {

    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public TacticsService(LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository) {
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    public TacticalProfile profileFor(UUID saveGameId, UUID clubId, List<Player> xi) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> fromLineup(lineup, xi))
                .orElseGet(() -> defaultProfile(xi));
    }

    public TacticalProfile defaultProfile(List<Player> xi) {
        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), PlayerRole.defaultFor(p.getPosition()));
        }
        UUID bestFinisher = xi.stream().max(Comparator.comparingInt(Player::getFinishing))
                .map(Player::getId).orElse(null);
        UUID bestPasser = xi.stream().max(Comparator.comparingInt(Player::getPassing))
                .map(Player::getId).orElse(null);
        return new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, bestFinisher, bestPasser, bestFinisher);
    }

    private TacticalProfile fromLineup(Lineup lineup, List<Player> xi) {
        Map<UUID, PlayerRole> persisted = new HashMap<>();
        lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .forEach(lp -> persisted.put(lp.getPlayerId(), lp.getRole()));
        // Fill any XI member missing a persisted role with its band default.
        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), persisted.getOrDefault(p.getId(), PlayerRole.defaultFor(p.getPosition())));
        }
        return new TacticalProfile(
                lineup.getMentality(), lineup.getFormation(), lineup.getPressing(), lineup.getMarking(),
                roles, lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }
}
