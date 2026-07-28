package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared greedy best-fit assignment of players to a formation's ordered {@link FormationSlots.Slot}s.
 *
 * <p>The routine is natural-first: slots are processed scarcest-first (fewest NATURAL candidates in
 * the initial pool — any of {@link Player#positions()}, primary or alternate, accepted by the slot —
 * ties by slot index), and each slot takes the best remaining NATURAL candidate by the supplied
 * comparator. A genuine natural always beats a higher-rated improviser. Only when no natural candidate
 * remains does a slot fall back to the closest player by tactical distance ({@link DetailedPosition#distance}
 * between the player's positions and the slot's accepted set), ties broken by the comparator. Scarcity
 * is computed once over the initial pool, so the whole assignment is a pure, deterministic function of
 * {@code (slots, pool)}: called twice with the same inputs it returns the identical result — except for
 * the pinned variant of {@link #assignToSlots(List, String, Map)}, where explicit pinned indexes are
 * honored verbatim and only the leftovers go through this greedy fit.
 *
 * <p>Both {@code LineupService#bestXi} (via {@link #assign}) and {@code TacticsService#profileFor}
 * (via {@link #assignToSlots}) share this logic so the selection and the position-grading never drift.
 */
final class SlotAssigner {

    private SlotAssigner() {
    }

    /**
     * Greedily assigns players from {@code pool} to {@code slots}. Returns an array indexed by slot:
     * entry {@code i} is the player assigned to {@code slots.get(i)}, or {@code null} when the pool ran
     * out (fewer players than slots). A player is assigned to at most one slot.
     */
    static Player[] assign(List<FormationSlots.Slot> slots, List<Player> pool, Comparator<Player> byRating) {
        // Scarcity = number of NATURAL candidates (any of the player's positions, primary or
        // alternate, accepted by the slot) in the INITIAL pool. Computed once so the processing
        // order never depends on fill progress.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            order.add(i);
        }
        Map<Integer, Long> tier1Count = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            FormationSlots.Slot slot = slots.get(i);
            tier1Count.put(i, pool.stream().filter(p -> isNatural(p, slot)).count());
        }
        order.sort(Comparator.<Integer>comparingLong(tier1Count::get).thenComparing(Comparator.naturalOrder()));

        Player[] picked = new Player[slots.size()];
        List<Player> available = new ArrayList<>(pool);
        for (int slotIndex : order) {
            if (available.isEmpty()) {
                break;
            }
            Player choice = pickForSlot(available, slots.get(slotIndex), byRating);
            picked[slotIndex] = choice;
            available.remove(choice);
        }
        return picked;
    }

    /**
     * Assigns the full XI to the formation's 11 ordered slots (index 0 = GK slot) and returns a
     * playerId → assigned {@link FormationSlots.Slot} map. A well-formed XI (every player fits a
     * distinct slot) assigns all-exact, so every player later grades to a x1.0 multiplier.
     * Within a tier, candidates are ordered by overall desc then id — the deterministic tie-break
     * style used across the engine — so repeated calls yield an identical map.
     *
     * <p>Defensive: if {@code xi.size() != 11} (should not happen for a valid XI) the extra slots or
     * extra players are simply left unassigned, and any player absent from the map grades to x1.0.
     */
    static Map<UUID, FormationSlots.Slot> assignToSlots(List<Player> xi, String formation) {
        return assignToSlots(xi, formation, Map.of());
    }

    /**
     * Like {@link #assignToSlots(List, String)} but honoring explicit pinned assignments first:
     * every XI member present in {@code pinnedSlotIndexes} (with a valid, unclaimed index) gets
     * exactly that slot; everyone else is greedily assigned to the remaining slots. A substitute
     * who came on mid-match is the common leftover — he inherits the vacated slot.
     */
    static Map<UUID, FormationSlots.Slot> assignToSlots(List<Player> xi, String formation,
                                                        Map<UUID, Integer> pinnedSlotIndexes) {
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Map<UUID, FormationSlots.Slot> assignments = new HashMap<>();
        Set<Integer> taken = new HashSet<>();
        List<Player> leftovers = new ArrayList<>();
        for (Player p : xi) {
            Integer idx = pinnedSlotIndexes.get(p.getId());
            if (idx != null && idx >= 0 && idx < slots.size() && taken.add(idx)) {
                assignments.put(p.getId(), slots.get(idx));
            } else {
                leftovers.add(p);
            }
        }
        List<FormationSlots.Slot> freeSlots = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (!taken.contains(i)) {
                freeSlots.add(slots.get(i));
            }
        }
        Comparator<Player> byRating =
                Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);
        Player[] picked = assign(freeSlots, leftovers, byRating);
        for (int i = 0; i < picked.length; i++) {
            if (picked[i] != null) {
                assignments.put(picked[i].getId(), freeSlots.get(i));
            }
        }
        return assignments;
    }

    /**
     * Best candidate for a single slot from {@code available}: NATURAL candidates (any of the
     * player's positions, primary or alternate, accepted by the slot) always win, best by
     * {@code byRating} — a genuine natural beats a higher-rated improviser. When no natural remains,
     * falls back to the smallest tactical distance between the player's positions and the slot's
     * accepted set, ties broken by {@code byRating}.
     */
    private static Player pickForSlot(List<Player> available, FormationSlots.Slot slot, Comparator<Player> byRating) {
        return available.stream().filter(p -> isNatural(p, slot)).max(byRating)
                .orElseGet(() -> available.stream()
                        .min(Comparator.comparingInt((Player p) -> distanceTo(p, slot))
                                .thenComparing(byRating.reversed()))
                        .orElseThrow());
    }

    /** Whether any of the player's positions (primary or alternate) is accepted by the slot. */
    private static boolean isNatural(Player p, FormationSlots.Slot slot) {
        return p.positions().stream().anyMatch(slot.accepted()::contains);
    }

    /** Smallest tactical distance between any of the player's positions and the slot's accepted set. */
    private static int distanceTo(Player p, FormationSlots.Slot slot) {
        int best = Integer.MAX_VALUE;
        for (DetailedPosition mine : p.positions()) {
            for (DetailedPosition acc : slot.accepted()) {
                best = Math.min(best, mine.distance(acc));
            }
        }
        return best;
    }
}
