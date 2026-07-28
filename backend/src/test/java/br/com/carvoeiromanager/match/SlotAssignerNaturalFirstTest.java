package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the natural-first tiering added to {@link SlotAssigner}: a genuine natural (any of
 * {@link Player#positions()}, primary or alternate) always beats a higher-rated improviser for a
 * slot, and only when no natural remains does the assignment fall back to the closest position by
 * {@link DetailedPosition#distance}. Complements the pre-existing {@link SlotAssignerTest}, which
 * covers the pinned/well-formed contract these tiering changes must not disturb.
 */
class SlotAssignerNaturalFirstTest {

    private static final Comparator<Player> BY_RATING =
            Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);

    @Test
    void aNaturalPlayerAlwaysBeatsAHigherRatedImproviser() {
        FormationSlots.Slot leSlot = FormationSlots.of("4-4-2").get(4); // LE
        Player naturalLe = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 60);
        Player improviserZag = player(Position.DEF, DetailedPosition.ZAGUEIRO, 90);

        Player[] picked = SlotAssigner.assign(List.of(leSlot), List.of(naturalLe, improviserZag), BY_RATING);

        assertThat(picked[0]).isEqualTo(naturalLe);
    }

    @Test
    void alternatePositionsCountAsNatural() {
        FormationSlots.Slot leSlot = FormationSlots.of("4-4-2").get(4); // LE
        Player altLe = player(Position.DEF, DetailedPosition.ZAGUEIRO, 70);
        altLe.setPositions(DetailedPosition.ZAGUEIRO, List.of(DetailedPosition.LATERAL_ESQUERDO));
        Player pureZag = player(Position.DEF, DetailedPosition.ZAGUEIRO, 90);

        Player[] picked = SlotAssigner.assign(List.of(leSlot), List.of(altLe, pureZag), BY_RATING);

        assertThat(picked[0]).isEqualTo(altLe);
    }

    @Test
    void fallbackPicksTheSmallestDistance() {
        // No LE anywhere in the pool: ZAGUEIRO is distance 1 from LATERAL_ESQUERDO (adjacent),
        // ATACANTE is distance 2 (not adjacent) — ZAG wins even at a much lower overall.
        FormationSlots.Slot leSlot = FormationSlots.of("4-4-2").get(4); // LE
        Player zag = player(Position.DEF, DetailedPosition.ZAGUEIRO, 60);
        Player atacante = player(Position.ATT, DetailedPosition.ATACANTE, 90);

        Player[] picked = SlotAssigner.assign(List.of(leSlot), List.of(zag, atacante), BY_RATING);

        assertThat(picked[0]).isEqualTo(zag);
    }

    @Test
    void deterministicTieBreakByRatingThenId() {
        FormationSlots.Slot leSlot = FormationSlots.of("4-4-2").get(4); // LE

        // Higher overall wins outright, regardless of id.
        Player low = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 60);
        Player high = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 90);
        assertThat(SlotAssigner.assign(List.of(leSlot), List.of(low, high), BY_RATING)[0]).isEqualTo(high);

        // Equal overall: the tie resolves by id, deterministically and identically across repeated
        // calls — the same secondary key bestXi's byRating comparator uses everywhere else.
        Player tiedA = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 75);
        Player tiedB = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 75);
        Player expected = tiedA.getId().compareTo(tiedB.getId()) >= 0 ? tiedA : tiedB;

        Player[] first = SlotAssigner.assign(List.of(leSlot), List.of(tiedA, tiedB), BY_RATING);
        Player[] second = SlotAssigner.assign(List.of(leSlot), List.of(tiedA, tiedB), BY_RATING);
        assertThat(first[0]).isEqualTo(expected);
        assertThat(second[0]).isEqualTo(expected);
    }

    @Test
    void explicitSlotsPinPlayersAndAutoFillsTheRest() {
        // wellFormed442(), except one striker ("x") gets force-pinned into the MD slot (index 5) even
        // though a genuine natural MD exists in the pool and outrates him — proving the pin overrides
        // natural-first. Every other natural occupant (gk, ld, both zagueiros, le, vol, mc, me, the
        // other striker) still fills its own exact slot; only the displaced MD — the one player whose
        // natural slot the pin stole — ends up bumped into whatever's left, proving the rest of the XI
        // fills natural-first around the pin. ATACANTE has no adjacent position among 4-4-2's other
        // slots, so the leftover striker slot's fallback can't be contested by anyone else.
        Player gk = player(Position.GK, DetailedPosition.GOLEIRO, 70);
        Player ld = player(Position.DEF, DetailedPosition.LATERAL_DIREITO, 70);
        Player zagA = player(Position.DEF, DetailedPosition.ZAGUEIRO, 72);
        Player zagB = player(Position.DEF, DetailedPosition.ZAGUEIRO, 71);
        Player le = player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 70);
        // Rated ABOVE the zagueiros so it — not either of them — wins the leftover striker slot's
        // tied-distance (both "far") fallback once its own MD slot is stolen by the pin.
        Player mdNatural = player(Position.MID, DetailedPosition.MEIA_DIREITA, 95);
        Player vol = player(Position.MID, DetailedPosition.VOLANTE, 70);
        Player mc = player(Position.MID, DetailedPosition.MEIA_CENTRAL, 70);
        Player me = player(Position.MID, DetailedPosition.MEIA_ESQUERDA, 70);
        Player x = player(Position.ATT, DetailedPosition.ATACANTE, 90); // pinned away from ATACANTE
        Player stB = player(Position.ATT, DetailedPosition.ATACANTE, 71);
        List<Player> xi = List.of(gk, ld, zagA, zagB, le, mdNatural, vol, mc, me, x, stB);

        Map<UUID, Integer> pinned = Map.of(x.getId(), 5); // MD slot
        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2", pinned);

        List<FormationSlots.Slot> slots = FormationSlots.of("4-4-2");
        assertThat(assignments.get(x.getId())).isEqualTo(slots.get(5));
        assertThat(assignments.get(x.getId()).accepted()).contains(DetailedPosition.MEIA_DIREITA);

        // Every other player, still natural to a distinct slot, keeps its exact fit.
        assertThat(assignments.get(gk.getId())).isEqualTo(slots.get(0));
        assertThat(assignments.get(ld.getId())).isEqualTo(slots.get(1));
        assertThat(assignments.get(zagA.getId())).isEqualTo(slots.get(2));
        assertThat(assignments.get(zagB.getId())).isEqualTo(slots.get(3));
        assertThat(assignments.get(le.getId())).isEqualTo(slots.get(4));
        assertThat(assignments.get(vol.getId())).isEqualTo(slots.get(6));
        assertThat(assignments.get(mc.getId())).isEqualTo(slots.get(7));
        assertThat(assignments.get(me.getId())).isEqualTo(slots.get(8));
        assertThat(assignments.get(stB.getId())).isEqualTo(slots.get(9));

        // The displaced natural MD is bumped into the only slot left over (the vacated ST slot).
        assertThat(assignments.get(mdNatural.getId())).isEqualTo(slots.get(10));
    }

    @Test
    void wellFormedNaturalXiAssignsAllExact() {
        List<Player> xi = wellFormed442();
        Map<UUID, FormationSlots.Slot> assignments = SlotAssigner.assignToSlots(xi, "4-4-2");

        assertThat(assignments).hasSize(11);
        for (Player p : xi) {
            FormationSlots.Slot slot = assignments.get(p.getId());
            assertThat(slot).as("assigned slot for %s", p.getDetailedPosition()).isNotNull();
            // Natural fit: at least one of the player's positions is accepted by the slot -> x1.0.
            assertThat(p.positions()).anyMatch(slot.accepted()::contains);
        }
    }

    // ---- helpers ----

    private static List<Player> wellFormed442() {
        List<Player> xi = new ArrayList<>();
        xi.add(player(Position.GK, DetailedPosition.GOLEIRO, 70));
        xi.add(player(Position.DEF, DetailedPosition.LATERAL_DIREITO, 70));
        xi.add(player(Position.DEF, DetailedPosition.ZAGUEIRO, 72));
        xi.add(player(Position.DEF, DetailedPosition.ZAGUEIRO, 71));
        xi.add(player(Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 70));
        xi.add(player(Position.MID, DetailedPosition.MEIA_DIREITA, 70));
        xi.add(player(Position.MID, DetailedPosition.VOLANTE, 70));
        xi.add(player(Position.MID, DetailedPosition.MEIA_CENTRAL, 70));
        xi.add(player(Position.MID, DetailedPosition.MEIA_ESQUERDA, 70));
        xi.add(player(Position.ATT, DetailedPosition.ATACANTE, 72));
        xi.add(player(Position.ATT, DetailedPosition.ATACANTE, 71));
        return xi;
    }

    private static Player player(Position pos, DetailedPosition detailed, int overall) {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, overall);
        p.setPositions(detailed, List.of());
        return p;
    }
}
