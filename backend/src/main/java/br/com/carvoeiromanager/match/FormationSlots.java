package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ORDERED detailed-position slots that make up each supported formation. Mirrors the pitch
 * geometry the frontend renders in {@code frontend/src/features/pitch/pitchSlots.ts} ({@code LAYOUTS}),
 * translating each geometric slot (a line + an x within that line) into the single {@link DetailedPosition}
 * that legitimately fills it.
 *
 * <p>Slot order is stable and mirrors the lineup starter order the frontend uses: GK first, then the
 * DEF line left→right, then MID (deepest row first), then ATT. This lets slot-aware selection
 * (see {@code LineupService#bestXi}) zip a squad against a formation positionally.
 *
 * <p>Mapping rules (kept consistent with the coarse {@link Position} band counts implied by the
 * formation code — DEF = first digit, ATT = last digit, MID = the digits in between):
 * <ul>
 *   <li><b>GK</b> → {@code GOLEIRO}.</li>
 *   <li><b>DEF</b> by count: a back-3 is three centre-backs ({@code ZAGUEIRO}); a back-4/5 has the
 *       outer two as full-backs (leftmost {@code LATERAL_DIREITO}, rightmost {@code LATERAL_ESQUERDO})
 *       and the inner slots as {@code ZAGUEIRO}.</li>
 *   <li><b>MID</b>: a wide slot in a 4+ / flat midfield row is {@code MEIA_DIREITA} (right) or
 *       {@code MEIA_ESQUERDA} (left); central slots fill by depth — the deepest holder is
 *       {@code VOLANTE}, the next is {@code MEIA_CENTRAL}, and the most advanced is
 *       {@code MEIA_ARMADOR}. Midfield slots are never wingers so the coarse MID count always
 *       matches the code's middle digits.</li>
 *   <li><b>ATT</b>: a front-3's wide slots are {@code PONTA_DIREITA} / {@code PONTA_ESQUERDA}; the
 *       central striker slot is {@code ATACANTE}.</li>
 * </ul>
 */
public final class FormationSlots {

    private static final Logger log = LoggerFactory.getLogger(FormationSlots.class);

    /** One ordered slot of a formation, carrying the detailed positions that may fill it. */
    public record Slot(Set<DetailedPosition> accepted) {
        public Slot {
            accepted = Set.copyOf(accepted);
        }

        /**
         * The coarse band this slot belongs to. Uniform across the accepted set by construction
         * (every detailed position accepted by a slot shares the same {@link Position#coarse band}).
         */
        public Position coarse() {
            return accepted.iterator().next().coarse();
        }
    }

    private static final Slot GK = slot(DetailedPosition.GOLEIRO);
    private static final Slot LD = slot(DetailedPosition.LATERAL_DIREITO);
    private static final Slot LE = slot(DetailedPosition.LATERAL_ESQUERDO);
    private static final Slot ZAG = slot(DetailedPosition.ZAGUEIRO);
    private static final Slot VOL = slot(DetailedPosition.VOLANTE);
    private static final Slot MC = slot(DetailedPosition.MEIA_CENTRAL);
    private static final Slot MEI = slot(DetailedPosition.MEIA_ARMADOR);
    private static final Slot MD = slot(DetailedPosition.MEIA_DIREITA);
    private static final Slot ME = slot(DetailedPosition.MEIA_ESQUERDA);
    private static final Slot PD = slot(DetailedPosition.PONTA_DIREITA);
    private static final Slot PE = slot(DetailedPosition.PONTA_ESQUERDA);
    private static final Slot ST = slot(DetailedPosition.ATACANTE);

    private static final String DEFAULT_FORMATION = "4-4-2";

    private static final Map<String, List<Slot>> FORMATIONS = Map.ofEntries(
            Map.entry("4-4-2", List.of(GK, LD, ZAG, ZAG, LE, MD, VOL, MC, ME, ST, ST)),
            Map.entry("4-3-3", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MEI, PD, ST, PE)),
            Map.entry("3-5-2", List.of(GK, ZAG, ZAG, ZAG, MD, VOL, MC, MEI, ME, ST, ST)),
            Map.entry("5-3-2", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MC, MEI, ST, ST)),
            Map.entry("4-5-1", List.of(GK, LD, ZAG, ZAG, LE, MD, VOL, MC, MEI, ME, ST)),
            Map.entry("4-2-3-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, VOL, MD, MEI, ME, ST)),
            Map.entry("4-1-4-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MD, MC, MEI, ME, ST)),
            Map.entry("4-3-2-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MC, MEI, MEI, ST)),
            Map.entry("3-4-3", List.of(GK, ZAG, ZAG, ZAG, MD, VOL, MC, ME, PD, ST, PE)),
            Map.entry("4-4-1-1", List.of(GK, LD, ZAG, ZAG, LE, MD, VOL, MC, ME, MEI, ST)),
            Map.entry("5-4-1", List.of(GK, LD, ZAG, ZAG, ZAG, LE, MD, VOL, MC, ME, ST)),
            Map.entry("4-2-4", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, PD, ST, ST, PE)),
            Map.entry("3-4-2-1", List.of(GK, ZAG, ZAG, ZAG, MD, VOL, MC, ME, MEI, MEI, ST)),
            Map.entry("4-1-2-1-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MC, MEI, ST, ST)),
            Map.entry("5-2-3", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MC, PD, ST, PE)),
            Map.entry("4-3-1-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MC, MC, MEI, ST, ST)));

    private FormationSlots() {
    }

    /**
     * The 11 ordered slots for the given formation code. An unknown code falls back to the default
     * {@value #DEFAULT_FORMATION} (with a warning) so a live match can never crash on a stray code.
     */
    public static List<Slot> of(String code) {
        List<Slot> slots = code == null ? null : FORMATIONS.get(code);
        if (slots == null) {
            log.warn("Unknown formation code '{}' — falling back to {}", code, DEFAULT_FORMATION);
            return FORMATIONS.get(DEFAULT_FORMATION);
        }
        return slots;
    }

    private static Slot slot(DetailedPosition... accepted) {
        return new Slot(Set.of(accepted));
    }
}
