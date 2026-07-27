package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit proof of the shared greedy best-fit {@link SlotAssigner}. The load-bearing determinism
 * guarantee: a well-formed XI (every player fits a distinct formation slot) assigns all-exact, so
 * every player later grades to a x1.0 position-fit multiplier and the engine stays byte-identical.
 */
class SlotAssignerTest {

    @Test
    void wellFormedXiAssignsEveryPlayerToAnExactSlot() {
        List<Player> xi = wellFormed442();
        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2");

        assertThat(assignments).hasSize(11);
        for (Player p : xi) {
            FormationSlots.Slot slot = assignments.get(p.getId());
            assertThat(slot).as("assigned slot for %s", p.getDetailedPosition()).isNotNull();
            // Exact fit: the slot accepts the player's detailed position → x1.0.
            assertThat(slot.accepted()).contains(p.getDetailedPosition());
        }
    }

    @Test
    void surplusAtOnePositionForcesADeterministicSameBandAssignment() {
        // A 4-4-2 back line is LD, ZAG, ZAG, LE. Field FOUR zagueiros and NO laterals: the two
        // full-back slots have no tier-1 (exact) candidate, so each must take a zagueiro instead
        // (same DEF band, wrong detailed). Two zagueiros still land in the ZAG slots exactly.
        List<Player> xi = new ArrayList<>();
        xi.add(detailed(Position.GK, DetailedPosition.GOLEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.ZAGUEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.ZAGUEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.ZAGUEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.ZAGUEIRO));
        xi.add(detailed(Position.MID, DetailedPosition.VOLANTE));
        xi.add(detailed(Position.MID, DetailedPosition.MEIA_ARMADOR));
        xi.add(detailed(Position.MID, DetailedPosition.MEIA_ARMADOR));
        xi.add(detailed(Position.MID, DetailedPosition.MEIA_ARMADOR));
        xi.add(detailed(Position.ATT, DetailedPosition.ATACANTE));
        xi.add(detailed(Position.ATT, DetailedPosition.ATACANTE));

        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2");
        assertThat(assignments).hasSize(11);

        long zagExact = xi.stream()
                .filter(p -> p.getDetailedPosition() == DetailedPosition.ZAGUEIRO)
                .filter(p -> assignments.get(p.getId()).accepted().contains(DetailedPosition.ZAGUEIRO))
                .count();
        long zagSameBandMisfit = xi.stream()
                .filter(p -> p.getDetailedPosition() == DetailedPosition.ZAGUEIRO)
                .filter(p -> {
                    FormationSlots.Slot s = assignments.get(p.getId());
                    return !s.accepted().contains(DetailedPosition.ZAGUEIRO) && s.coarse() == Position.DEF;
                })
                .count();
        assertThat(zagExact).isEqualTo(2);
        assertThat(zagSameBandMisfit).isEqualTo(2);
    }

    @Test
    void assignToSlotsIsDeterministicAcrossRepeatedCalls() {
        List<Player> xi = wellFormed442();
        assertThat(SlotAssigner.assignToSlots(xi, "4-4-2"))
                .isEqualTo(SlotAssigner.assignToSlots(xi, "4-4-2"));
    }

    @Test
    void pinnedAssignmentsAreHonoredVerbatimEvenOutOfPosition() {
        List<Player> xi = wellFormed442();
        // Pin a natural ZAGUEIRO into the last slot (a striker slot in 4-4-2): greedy would
        // never do this while his exact slot is free, so honoring it proves the override.
        Player zagueiro = xi.stream()
                .filter(p -> p.getDetailedPosition() == DetailedPosition.ZAGUEIRO)
                .findFirst().orElseThrow();
        Map<UUID, Integer> pinned = Map.of(zagueiro.getId(), 10);

        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2", pinned);

        assertThat(assignments).hasSize(11);
        assertThat(assignments.get(zagueiro.getId()))
                .isEqualTo(FormationSlots.of("4-4-2").get(10));
        assertThat(assignments.get(zagueiro.getId()).accepted())
                .contains(DetailedPosition.ATACANTE);
    }

    @Test
    void leftoverPlayerInheritsTheSingleVacatedSlot() {
        List<Player> xi = wellFormed442();
        // Pin 10 of the 11 to slots 0..9; the unpinned one must get slot 10.
        Map<UUID, Integer> pinned = new java.util.HashMap<>();
        for (int i = 0; i < 10; i++) {
            pinned.put(xi.get(i).getId(), i);
        }
        Player leftover = xi.get(10);

        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2", pinned);

        assertThat(assignments).hasSize(11);
        assertThat(assignments.get(leftover.getId()))
                .isEqualTo(FormationSlots.of("4-4-2").get(10));
    }

    @Test
    void emptyPinnedMapMatchesTheLegacyGreedyAssignment() {
        List<Player> xi = wellFormed442();
        assertThat(SlotAssigner.assignToSlots(xi, "4-4-2", Map.of()))
                .isEqualTo(SlotAssigner.assignToSlots(xi, "4-4-2"));
    }

    // ---- helpers ----

    private static List<Player> wellFormed442() {
        // 4-4-2's MID row is four all-distinct slots (MD, VOL, MC, ME), so each gets its own candidate.
        List<Player> xi = new ArrayList<>();
        xi.add(detailed(Position.GK, DetailedPosition.GOLEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.LATERAL_DIREITO));
        xi.add(detailed(Position.DEF, DetailedPosition.ZAGUEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.ZAGUEIRO));
        xi.add(detailed(Position.DEF, DetailedPosition.LATERAL_ESQUERDO));
        xi.add(detailed(Position.MID, DetailedPosition.MEIA_DIREITA));
        xi.add(detailed(Position.MID, DetailedPosition.VOLANTE));
        xi.add(detailed(Position.MID, DetailedPosition.MEIA_CENTRAL));
        xi.add(detailed(Position.MID, DetailedPosition.MEIA_ESQUERDA));
        xi.add(detailed(Position.ATT, DetailedPosition.ATACANTE));
        xi.add(detailed(Position.ATT, DetailedPosition.ATACANTE));
        return xi;
    }

    private static Player detailed(Position pos, DetailedPosition detailed) {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, 70);
        p.setDetailedPosition(detailed);
        return p;
    }
}
