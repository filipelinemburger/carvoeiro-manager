package br.com.carvoeiromanager.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A fine-grained playing position that refines the coarse {@link Position}. Every player carries one:
 * seeded players and free agents get one at career creation (or via the V39 backfill for legacy saves),
 * and every generated player (newgen / youth prospect) gets one at construction.
 *
 * <p>{@link #coarse()} always maps back to the owning {@link Position}, so a detailed position never
 * contradicts a player's coarse position.
 */
public enum DetailedPosition {

    GOLEIRO(Position.GK, "Goleiro", "GOL"),
    ZAGUEIRO(Position.DEF, "Zagueiro", "ZAG"),
    LATERAL_DIREITO(Position.DEF, "Lateral direito", "LD"),
    LATERAL_ESQUERDO(Position.DEF, "Lateral esquerdo", "LE"),
    VOLANTE(Position.MID, "Volante", "VOL"),
    MEIA_CENTRAL(Position.MID, "Meia central", "MC"),
    MEIA_ARMADOR(Position.MID, "Meia armador", "MEI"),
    MEIA_DIREITA(Position.MID, "Meia direita", "MD"),
    MEIA_ESQUERDA(Position.MID, "Meia esquerda", "ME"),
    PONTA_DIREITA(Position.ATT, "Ponta direita", "PD"),
    PONTA_ESQUERDA(Position.ATT, "Ponta esquerda", "PE"),
    ATACANTE(Position.ATT, "Atacante", "ATA");

    private final Position coarse;
    private final String label;
    private final String shortLabel;

    DetailedPosition(Position coarse, String label, String shortLabel) {
        this.coarse = coarse;
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public Position coarse() {
        return coarse;
    }

    public String label() {
        return label;
    }

    public String shortLabel() {
        return shortLabel;
    }

    /** Unordered adjacent (1-step) pairs of the position graph — see spec §4.1. */
    private static final Set<Set<DetailedPosition>> ADJACENT = Set.of(
            Set.of(LATERAL_DIREITO, MEIA_DIREITA), Set.of(LATERAL_ESQUERDO, MEIA_ESQUERDA),
            Set.of(MEIA_DIREITA, PONTA_DIREITA), Set.of(MEIA_ESQUERDA, PONTA_ESQUERDA),
            Set.of(VOLANTE, MEIA_CENTRAL), Set.of(MEIA_CENTRAL, MEIA_ARMADOR),
            Set.of(MEIA_ARMADOR, ATACANTE), Set.of(ZAGUEIRO, VOLANTE),
            Set.of(LATERAL_DIREITO, ZAGUEIRO), Set.of(LATERAL_ESQUERDO, ZAGUEIRO),
            Set.of(PONTA_DIREITA, ATACANTE), Set.of(PONTA_ESQUERDA, ATACANTE));

    /**
     * Tactical distance between positions: 0 = same, 1 = adjacent (natural improvisation),
     * 2 = far, 3 = goalkeeper vs any outfield position (either direction).
     */
    public int distance(DetailedPosition other) {
        if (this == other) {
            return 0;
        }
        if (this == GOLEIRO || other == GOLEIRO) {
            return 3;
        }
        return ADJACENT.contains(Set.of(this, other)) ? 1 : 2;
    }

    /** Distance-1 neighbours in declaration order (never contains GOLEIRO for outfield callers). */
    public List<DetailedPosition> adjacent() {
        return Arrays.stream(values()).filter(p -> distance(p) == 1).toList();
    }

    /**
     * Picks a coarse-consistent detailed position for a player, adding realistic variety within a
     * coarse position. Draws from {@code rng}; callers that must not disturb a shared generation
     * stream pass a per-player {@link Random} seeded off the player's id.
     *
     * <p>The result always satisfies {@code defaultFor(position, rng).coarse() == position}.
     *
     * @param position the coarse position the detailed value must belong to
     * @param rng      the source of variety
     */
    public static DetailedPosition defaultFor(Position position, Random rng) {
        return switch (position) {
            case GK -> GOLEIRO;
            case DEF -> {
                int r = rng.nextInt(10);            // 60% ZAG, 20% LD, 20% LE
                if (r < 6) yield ZAGUEIRO;
                yield r < 8 ? LATERAL_DIREITO : LATERAL_ESQUERDO;
            }
            case MID -> {
                int r = rng.nextInt(10);            // 30% VOL, 30% MC, 20% MEI, 10% MD, 10% ME
                if (r < 3) yield VOLANTE;
                if (r < 6) yield MEIA_CENTRAL;
                if (r < 8) yield MEIA_ARMADOR;
                yield r == 8 ? MEIA_DIREITA : MEIA_ESQUERDA;
            }
            case ATT -> {
                int r = rng.nextInt(10);            // 20% PD, 20% PE, 60% ATA
                if (r < 2) yield PONTA_DIREITA;
                if (r < 4) yield PONTA_ESQUERDA;
                yield ATACANTE;
            }
        };
    }

    /**
     * 0–2 alternate positions for a generated player: ~30% one alternate, ~5% two, drawn from the
     * primary's adjacent outfield neighbours. Goalkeepers never get (or are) alternates. Draws come
     * from the caller's per-player Random, so no shared stream is disturbed.
     */
    public static List<DetailedPosition> alternatesFor(DetailedPosition primary, Random rng) {
        if (primary == GOLEIRO) {
            return List.of();
        }
        int roll = rng.nextInt(100);
        int count = roll < 5 ? 2 : roll < 35 ? 1 : 0;
        List<DetailedPosition> pool = new ArrayList<>(primary.adjacent());
        List<DetailedPosition> alts = new ArrayList<>();
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            alts.add(pool.remove(rng.nextInt(pool.size())));
        }
        return alts;
    }
}
