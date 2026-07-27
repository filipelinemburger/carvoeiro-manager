package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormationSlotsTest {

    private static final String[] ALL_CODES = {
            "4-4-2", "4-3-3", "3-5-2", "5-3-2", "4-5-1", "4-2-3-1", "4-1-4-1", "4-3-2-1",
            "3-4-3", "4-4-1-1", "5-4-1", "4-2-4", "3-4-2-1", "4-1-2-1-2", "5-2-3", "4-3-1-2"
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "4-4-2", "4-3-3", "3-5-2", "5-3-2", "4-5-1", "4-2-3-1", "4-1-4-1", "4-3-2-1",
            "3-4-3", "4-4-1-1", "5-4-1", "4-2-4", "3-4-2-1", "4-1-2-1-2", "5-2-3", "4-3-1-2"
    })
    void everyFormationHasElevenSlotsWithBandCountsMatchingTheCode(String code) {
        // Given the coarse band counts implied by parsing the code:
        //   GK = 1, DEF = first digit, ATT = last digit, MID = the digits in between.
        int[] digits = Arrays.stream(code.split("-")).mapToInt(Integer::parseInt).toArray();
        int expectedDef = digits[0];
        int expectedAtt = digits[digits.length - 1];
        int expectedMid = 0;
        for (int i = 1; i < digits.length - 1; i++) {
            expectedMid += digits[i];
        }

        // When
        List<FormationSlots.Slot> slots = FormationSlots.of(code);

        // Then there are exactly 11 slots
        assertThat(slots).hasSize(11);

        // ...and the per-coarse-band counts line up with the parsed code.
        Map<Position, Long> byBand = new EnumMap<>(Position.class);
        for (FormationSlots.Slot s : slots) {
            byBand.merge(s.coarse(), 1L, Long::sum);
        }
        assertThat(byBand.getOrDefault(Position.GK, 0L)).as("GK count for %s", code).isEqualTo(1L);
        assertThat(byBand.getOrDefault(Position.DEF, 0L)).as("DEF count for %s", code).isEqualTo(expectedDef);
        assertThat(byBand.getOrDefault(Position.MID, 0L)).as("MID count for %s", code).isEqualTo(expectedMid);
        assertThat(byBand.getOrDefault(Position.ATT, 0L)).as("ATT count for %s", code).isEqualTo(expectedAtt);

        // The first slot is always the goalkeeper.
        assertThat(slots.get(0).accepted()).containsExactly(DetailedPosition.GOLEIRO);
    }

    @Test
    void fourThreeThreeMatchesThePlannedSlotList() {
        // The worked example from the plan.
        List<FormationSlots.Slot> slots = FormationSlots.of("4-3-3");
        assertThat(accepted(slots)).containsExactly(
                set(DetailedPosition.GOLEIRO),
                set(DetailedPosition.LATERAL_DIREITO),
                set(DetailedPosition.ZAGUEIRO),
                set(DetailedPosition.ZAGUEIRO),
                set(DetailedPosition.LATERAL_ESQUERDO),
                set(DetailedPosition.VOLANTE),
                set(DetailedPosition.MEIA_CENTRAL),
                set(DetailedPosition.MEIA_ARMADOR),
                set(DetailedPosition.PONTA_DIREITA),
                set(DetailedPosition.ATACANTE),
                set(DetailedPosition.PONTA_ESQUERDA));
    }

    @Test
    void backFourPutsFullBacksOnTheOutsideCentreBacksInside() {
        // 4-4-2 back line, ordered left→right: LD, ZAG, ZAG, LE.
        List<FormationSlots.Slot> slots = FormationSlots.of("4-4-2");
        assertThat(slots.get(1).accepted()).containsExactly(DetailedPosition.LATERAL_DIREITO);
        assertThat(slots.get(2).accepted()).containsExactly(DetailedPosition.ZAGUEIRO);
        assertThat(slots.get(3).accepted()).containsExactly(DetailedPosition.ZAGUEIRO);
        assertThat(slots.get(4).accepted()).containsExactly(DetailedPosition.LATERAL_ESQUERDO);
    }

    @Test
    void unknownFormationFallsBackToFourFourTwo() {
        assertThat(FormationSlots.of("9-9-9")).isEqualTo(FormationSlots.of("4-4-2"));
        assertThat(FormationSlots.of(null)).isEqualTo(FormationSlots.of("4-4-2"));
    }

    @Test
    void centralStrikerSlotAcceptsOnlyAtacante() {
        // 4-5-1's lone forward is a central striker slot — single-position now that FALSO_9 is
        // retired (a false-9 archetype survives only as a familiar-role flavour, not a slot value).
        List<FormationSlots.Slot> slots = FormationSlots.of("4-5-1");
        assertThat(slots.get(10).accepted()).containsExactly(DetailedPosition.ATACANTE);
    }

    private static List<java.util.Set<DetailedPosition>> accepted(List<FormationSlots.Slot> slots) {
        return slots.stream().map(FormationSlots.Slot::accepted).toList();
    }

    private static java.util.Set<DetailedPosition> set(DetailedPosition... ps) {
        return java.util.Set.of(ps);
    }
}
