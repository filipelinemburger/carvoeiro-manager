package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared greedy best-fit assignment of players to a formation's ordered {@link FormationSlots.Slot}s.
 *
 * <p>The routine mirrors the tiering + scarcity heuristic used to pick a best XI: slots are processed
 * scarcest-first (fewest tier-1 exact-detailed candidates in the initial pool, ties by slot index),
 * and each slot takes the best remaining candidate by tier — tier 1 = the slot accepts the player's
 * detailed position, tier 2 = same coarse band, tier 3 = anyone left — breaking ties within a tier by
 * the supplied comparator. Scarcity is computed once over the initial pool, so the whole assignment is
 * a pure, deterministic function of {@code (slots, pool)}: called twice with the same inputs it returns
 * the identical result.
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
        // Scarcity = number of tier-1 (exact detailed-position) candidates in the INITIAL pool.
        // Computed once so the processing order never depends on fill progress.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            order.add(i);
        }
        Map<Integer, Long> tier1Count = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            Set<DetailedPosition> accepted = slots.get(i).accepted();
            tier1Count.put(i, pool.stream().filter(p -> accepted.contains(p.getDetailedPosition())).count());
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
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Comparator<Player> byRating =
                Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);
        Player[] picked = assign(slots, xi, byRating);
        Map<UUID, FormationSlots.Slot> assignments = new HashMap<>();
        for (int i = 0; i < picked.length; i++) {
            if (picked[i] != null) {
                assignments.put(picked[i].getId(), slots.get(i));
            }
        }
        return assignments;
    }

    /**
     * Best candidate for a single slot from {@code available}, by tier then {@code byRating}:
     * tier 1 = detailed position accepted by the slot; tier 2 = same coarse band; tier 3 = anyone.
     */
    private static Player pickForSlot(List<Player> available, FormationSlots.Slot slot, Comparator<Player> byRating) {
        Set<DetailedPosition> accepted = slot.accepted();
        Position band = slot.coarse();
        return available.stream().filter(p -> accepted.contains(p.getDetailedPosition())).max(byRating)
                .or(() -> available.stream().filter(p -> p.getPosition() == band).max(byRating))
                .orElseGet(() -> available.stream().max(byRating).orElseThrow());
    }
}
