package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.GameStyle;
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
import java.util.function.Function;

/**
 * Builds the {@link TacticalProfile} for a club's match: the managed club's
 * persisted lineup tactics when present, otherwise that club's own seed-authored
 * identity (formation/mentality/pressing/marking, persisted on {@link Club} at
 * career creation for every club including AI clubs). Falls back to a neutral
 * default only when the club or an identity field cannot be found (e.g. a stale
 * or synthetic clubId).
 */
@Service
public class TacticsService {

    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;
    private final ClubRepository clubRepository;

    public TacticsService(LineupRepository lineupRepository,
                          LineupPlayerRepository lineupPlayerRepository,
                          ClubRepository clubRepository) {
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.clubRepository = clubRepository;
    }

    public TacticalProfile profileFor(UUID saveGameId, UUID clubId, List<Player> xi) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> fromLineup(lineup, xi))
                .orElseGet(() -> defaultProfile(clubId, xi));
    }

    public TacticalProfile defaultProfile(UUID clubId, List<Player> xi) {
        Club club = clubRepository.findById(clubId).orElse(null);

        Mentality mentality = parseOrDefault(
                club != null ? club.getDefaultMentality() : null, Mentality::valueOf, Mentality.BALANCED);
        GameStyle gameStyle = parseOrDefault(
                club != null ? club.getDefaultGameStyle() : null, GameStyle::valueOf, GameStyle.EQUILIBRADO);
        String formation = club != null && club.getDefaultFormation() != null
                ? club.getDefaultFormation() : "4-4-2";
        Pressing pressing = parseOrDefault(
                club != null ? club.getDefaultPressing() : null, Pressing::valueOf, Pressing.MEDIUM);
        Marking marking = parseOrDefault(
                club != null ? club.getDefaultMarking() : null, Marking::valueOf, Marking.ZONAL);

        Map<UUID, PlayerRole> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), PlayerRole.defaultFor(p.getPosition()));
        }
        UUID bestFinisher = xi.stream().max(Comparator.comparingInt(Player::getFinishing))
                .map(Player::getId).orElse(null);
        UUID bestPasser = xi.stream().max(Comparator.comparingInt(Player::getPassing))
                .map(Player::getId).orElse(null);
        return new TacticalProfile(mentality, gameStyle, formation, pressing, marking,
                roles, bestFinisher, bestPasser, bestFinisher);
    }

    private <T extends Enum<T>> T parseOrDefault(String raw, Function<String, T> parser, T fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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
                lineup.getMentality(), lineup.getGameStyle(), lineup.getFormation(),
                lineup.getPressing(), lineup.getMarking(),
                roles, lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }
}
