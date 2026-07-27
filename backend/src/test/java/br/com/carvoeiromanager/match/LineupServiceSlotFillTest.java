package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the slot-aware outfield fill (no Spring / DB): builds hand-crafted squads and
 * exercises {@link LineupService#fillOutfieldSlots} directly.
 */
class LineupServiceSlotFillTest {

    // Rank candidates by overall (ascending, so .max wins the highest), id tie-break — mirrors the
    // deterministic comparator bestXi uses.
    private static final Comparator<Player> BY_OVERALL =
            Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);

    private static Player player(String name, Position pos, DetailedPosition dp, int overall) {
        Player p = Player.create(UUID.randomUUID(), UUID.randomUUID(), name, pos, 25, "BRA",
                70, 70, 70, 70, pos == Position.GK ? 70 : 10, 70, overall);
        p.setDetailedPosition(dp);
        return p;
    }

    @Test
    void exactDetailedMatchWinsItsSlotOverAHigherRatedSameBandPlayer() {
        // Given a back-4's leftmost slot accepts LATERAL_DIREITO. A low-rated LATERAL_DIREITO and a
        // high-rated ZAGUEIRO (same DEF band) both compete for it.
        Player lowLateral = player("Lateral", Position.DEF, DetailedPosition.LATERAL_DIREITO, 60);
        Player highZagueiro = player("Zaga-A", Position.DEF, DetailedPosition.ZAGUEIRO, 90);
        List<Player> pool = new ArrayList<>(List.of(
                lowLateral,
                highZagueiro,
                player("Zaga-B", Position.DEF, DetailedPosition.ZAGUEIRO, 75),
                player("LatEsq", Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 70),
                player("Volante", Position.MID, DetailedPosition.VOLANTE, 70),
                player("Meia-1", Position.MID, DetailedPosition.MEIA_ARMADOR, 70),
                player("Meia-2", Position.MID, DetailedPosition.MEIA_ARMADOR, 68),
                player("Meia-3", Position.MID, DetailedPosition.MEIA_ARMADOR, 66),
                player("Atk-1", Position.ATT, DetailedPosition.ATACANTE, 72),
                player("Atk-2", Position.ATT, DetailedPosition.ATACANTE, 71)));

        // When the 4-4-2 outfield slots are filled (result is in slot order; index 0 = leftmost DEF)
        List<Player> filled = LineupService.fillOutfieldSlots(pool, "4-4-2", BY_OVERALL);

        // Then the leftmost DEF slot is the exact-match LATERAL_DIREITO (tier 1), not the 90-rated
        // ZAGUEIRO (tier 2) — tier beats overall.
        assertThat(filled).hasSize(10);
        assertThat(filled.get(0)).isEqualTo(lowLateral);
        // ...and the high zagueiro still makes the XI, in a centre-back slot.
        assertThat(filled).contains(highZagueiro);
    }

    @Test
    void slotsWithNoBandMatchFallBackToAnyoneLeftSoTheXiIsAlwaysFull() {
        // Given a pool of ten centre-backs only — no midfielders, no forwards.
        List<Player> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pool.add(player("Zaga-" + i, Position.DEF, DetailedPosition.ZAGUEIRO, 80 - i));
        }

        // When filling a 4-4-2 (which wants MID and ATT slots too)
        List<Player> filled = LineupService.fillOutfieldSlots(pool, "4-4-2", BY_OVERALL);

        // Then every slot is filled from the tier-3 fallback: ten distinct players, no gaps.
        assertThat(filled).hasSize(10);
        assertThat(filled).doesNotContainNull();
        assertThat(filled).containsExactlyInAnyOrderElementsOf(pool);
    }

    @Test
    void fillIsDeterministicAcrossRepeatedCalls() {
        // Given a mixed pool. 4-3-3's outfield slots are LD, ZAG, ZAG, LE, VOL, MC, MEI, PD, ST, PE
        // — ten distinct slots (only ZAG repeats) — so M1/M2 each exact-fit a different central-mid
        // slot (MC / MEI) and Pon1/Pon2 each exact-fit a different wing slot (PD / PE).
        List<Player> pool = new ArrayList<>(List.of(
                player("LD", Position.DEF, DetailedPosition.LATERAL_DIREITO, 71),
                player("LE", Position.DEF, DetailedPosition.LATERAL_ESQUERDO, 72),
                player("Z1", Position.DEF, DetailedPosition.ZAGUEIRO, 80),
                player("Z2", Position.DEF, DetailedPosition.ZAGUEIRO, 79),
                player("V", Position.MID, DetailedPosition.VOLANTE, 78),
                player("M1", Position.MID, DetailedPosition.MEIA_CENTRAL, 77),
                player("M2", Position.MID, DetailedPosition.MEIA_ARMADOR, 76),
                player("Pon1", Position.ATT, DetailedPosition.PONTA_DIREITA, 75),
                player("Pon2", Position.ATT, DetailedPosition.PONTA_ESQUERDA, 74),
                player("St", Position.ATT, DetailedPosition.ATACANTE, 82),
                // one extra bench-grade candidate (same detailed position as M2) that must lose the
                // single MEI slot and be dropped identically both times
                player("M3", Position.MID, DetailedPosition.MEIA_ARMADOR, 50)));

        // When filled twice
        List<Player> first = LineupService.fillOutfieldSlots(pool, "4-3-3", BY_OVERALL);
        List<Player> second = LineupService.fillOutfieldSlots(pool, "4-3-3", BY_OVERALL);

        // Then the exact same players, in the exact same order.
        assertThat(idsOf(first)).isEqualTo(idsOf(second));
        assertThat(first).hasSize(10);
    }

    private static List<UUID> idsOf(List<Player> players) {
        return players.stream().map(Player::getId).toList();
    }
}
