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
 * translating each geometric slot (a line + an x within that line) into the set of
 * {@link DetailedPosition}s that legitimately fill it.
 *
 * <p>Slot order is stable and mirrors the lineup starter order the frontend uses: GK first, then the
 * DEF line left→right, then MID (deepest row first), then ATT. This lets slot-aware selection
 * (see {@code LineupService#bestXi}) zip a squad against a formation positionally.
 *
 * <p>Mapping rules (kept consistent with the coarse {@link Position} band counts implied by the
 * formation code — DEF = first digit, ATT = last digit, MID = the digits in between):
 * <ul>
 *   <li><b>GK</b> → {@code GOLEIRO}.</li>
 *   <li><b>DEF</b> by count: a back-3 is three centre-backs; a back-4/5 has the outer two as
 *       full-backs (leftmost {@code LATERAL_DIREITO}, rightmost {@code LATERAL_ESQUERDO}) and the
 *       inner slots as {@code ZAGUEIRO}.</li>
 *   <li><b>MID</b>: a single midfield row uses one holder (leftmost {@code VOLANTE}) with the rest
 *       {@code MEIA_ARMADOR}; when a formation has multiple midfield rows the deepest row holds
 *       (a holding pair/single → all {@code VOLANTE}, a wider deep band → one {@code VOLANTE} + rest
 *       {@code MEIA_ARMADOR}) and the more advanced rows are {@code MEIA_ARMADOR}. Midfield slots are
 *       never wingers so the coarse MID count always matches the code's middle digits.</li>
 *   <li><b>ATT</b>: wide slots → {@code PONTA}; central striker slots accept both {@code ATACANTE}
 *       and {@code FALSO_9}.</li>
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
    private static final Slot MEI = slot(DetailedPosition.MEIA_ARMADOR);
    private static final Slot PON = slot(DetailedPosition.PONTA);
    // A central striker slot: an out-and-out striker or a false-9 both fit.
    private static final Slot ST = slot(DetailedPosition.ATACANTE, DetailedPosition.FALSO_9);

    private static final String DEFAULT_FORMATION = "4-4-2";

    private static final Map<String, List<Slot>> FORMATIONS = Map.ofEntries(
            Map.entry("4-4-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, ST, ST)),
            Map.entry("4-3-3", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, PON, ST, PON)),
            Map.entry("3-5-2", List.of(GK, ZAG, ZAG, ZAG, VOL, MEI, MEI, MEI, MEI, ST, ST)),
            Map.entry("5-3-2", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MEI, MEI, ST, ST)),
            Map.entry("4-5-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, MEI, ST)),
            Map.entry("4-2-3-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, VOL, MEI, MEI, MEI, ST)),
            Map.entry("4-1-4-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, MEI, ST)),
            Map.entry("4-3-2-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, MEI, ST)),
            Map.entry("3-4-3", List.of(GK, ZAG, ZAG, ZAG, VOL, MEI, MEI, MEI, PON, ST, PON)),
            // 4-4-1-1: the withdrawn "1" behind the striker reads as an advanced playmaker (MEIA_ARMADOR),
            // keeping coarse MID = 5 / ATT = 1 in line with the code's digits.
            Map.entry("4-4-1-1", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, MEI, ST)),
            Map.entry("5-4-1", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, ST)),
            Map.entry("4-2-4", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, PON, ST, ST, PON)),
            Map.entry("3-4-2-1", List.of(GK, ZAG, ZAG, ZAG, VOL, MEI, MEI, MEI, MEI, MEI, ST)),
            Map.entry("4-1-2-1-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, ST, ST)),
            Map.entry("5-2-3", List.of(GK, LD, ZAG, ZAG, ZAG, LE, VOL, MEI, PON, ST, PON)),
            Map.entry("4-3-1-2", List.of(GK, LD, ZAG, ZAG, LE, VOL, MEI, MEI, MEI, ST, ST)));

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
