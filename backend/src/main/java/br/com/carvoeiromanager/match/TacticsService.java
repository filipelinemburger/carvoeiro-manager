package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;
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
        AttackZone attackZone = parseOrDefault(
                club != null ? club.getDefaultAttackZone() : null, AttackZone::valueOf, AttackZone.MISTO);
        String formation = club != null && club.getDefaultFormation() != null
                ? club.getDefaultFormation() : "4-4-2";
        Pressing pressing = parseOrDefault(
                club != null ? club.getDefaultPressing() : null, Pressing::valueOf, Pressing.MEDIUM);
        Marking marking = parseOrDefault(
                club != null ? club.getDefaultMarking() : null, Marking::valueOf, Marking.ZONAL);

        // Grade each starter against the formation the AI actually plays (same club-default-else-4-4-2
        // resolution bestXi's formationFor uses), so the bestXi-picked XI assigns all-exact → x1.0.
        Map<UUID, FormationSlots.Slot> slotAssignments = SlotAssigner.assignToSlots(xi, formation);
        // AI/default clubs never persist explicit roles, so every starter gets its best FAMILIAR
        // archetype for the slot it's actually assigned to (defaultProfile has no persisted rows to
        // fall back to, unlike fromLineup).
        Map<UUID, RoleAssignment> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), bestAssignmentFor(p, slotAssignments.get(p.getId())));
        }
        UUID bestFinisher = xi.stream().max(Comparator.comparingInt(Player::getFinishing))
                .map(Player::getId).orElse(null);
        UUID bestPasser = xi.stream().max(Comparator.comparingInt(Player::getPassing))
                .map(Player::getId).orElse(null);
        // AI/default clubs carry no per-player instructions — an empty map is the neutral no-op.
        return new TacticalProfile(mentality, gameStyle, attackZone, formation, pressing, marking,
                roles, bestFinisher, bestPasser, bestFinisher, Map.of(), slotAssignments);
    }

    /**
     * The best archetype for a player in a slot: highest familiarity among archetypes valid for the
     * slot's position (ties by familiar-CSV order), else the slot position's default. Focus = that
     * archetype's default for the slot position. A null slot falls back to the player's primary.
     *
     * <p>Fixes a live balance bug: player generation deliberately makes specialists (e.g. a
     * VOLANTE/MEIA_CENTRAL with PASSE &gt;= 68 is generated as DEEP_LYING_PLAYMAKER++), but the old
     * position-default-only assignment ignored that and handed them HOLDING/BOX_TO_BOX instead —
     * familiarity 0, a x0.92 penalty on both attack and defense contributions. This picks the
     * archetype the player actually trained in, whenever one is valid for the slot.
     */
    static RoleAssignment bestAssignmentFor(Player p, FormationSlots.Slot slot) {
        DetailedPosition pos = slot != null ? slot.accepted().iterator().next() : p.getDetailedPosition();
        RoleArchetype best = null;
        int bestLevel = 0;
        for (Map.Entry<RoleArchetype, Integer> e : p.familiarRoles().entrySet()) {   // insertion order
            if (e.getKey().validFor(pos) && e.getValue() > bestLevel) {
                best = e.getKey();
                bestLevel = e.getValue();
            }
        }
        if (best == null) {
            best = RoleArchetype.defaultFor(pos);
        }
        return new RoleAssignment(best, best.defaultFocusFor(pos));
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
        Map<UUID, RoleAssignment> persisted = new HashMap<>();
        Map<UUID, PlayerInstruction> instructions = new HashMap<>();
        Map<UUID, Integer> pinnedSlots = new HashMap<>();
        lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .forEach(lp -> {
                    persisted.put(lp.getPlayerId(), new RoleAssignment(lp.getArchetype(), lp.getFocus()));
                    // Only starters with an explicit instruction enter the map; the rest resolve to
                    // "no instruction", so a lineup with none set stays byte-identical.
                    if (lp.getInstruction() != null) {
                        instructions.put(lp.getPlayerId(), lp.getInstruction());
                    }
                    if (lp.getSlotIndex() != null) {
                        pinnedSlots.put(lp.getPlayerId(), lp.getSlotIndex());
                    }
                });
        // Persisted (drag-and-drop) slots are honored verbatim; XI members without one — subs
        // who came on, legacy lineups — fill the remaining slots greedily.
        Map<UUID, FormationSlots.Slot> slotAssignments =
                SlotAssigner.assignToSlots(xi, lineup.getFormation(), pinnedSlots);
        // Fill any XI member missing a persisted assignment with its best familiar archetype for the
        // slot it's actually assigned to (a manager who set nothing still gets a familiar-aware pick,
        // not a blunt position default).
        Map<UUID, RoleAssignment> roles = new HashMap<>();
        for (Player p : xi) {
            roles.put(p.getId(), persisted.getOrDefault(p.getId(),
                    bestAssignmentFor(p, slotAssignments.get(p.getId()))));
        }
        return new TacticalProfile(
                lineup.getMentality(), lineup.getGameStyle(), lineup.getAttackZone(), lineup.getFormation(),
                lineup.getPressing(), lineup.getMarking(),
                roles, lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId(),
                instructions, slotAssignments);
    }
}
